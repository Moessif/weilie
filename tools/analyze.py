# -*- coding: utf-8 -*-
"""
围猎 · 试验数据分析脚本
用法:  python tools/analyze.py <sessions目录或某个.jsonl文件> [-o 输出目录]
功能:  读取 App 导出的 JSONL 采样文件（SessionStore 格式），输出:
       1. 每场次统计摘要（样本数/时长/目标命中数/RSRP 均值/扇区覆盖度/拟合置信度）
       2. 逐场次图表: 轨迹+RSRP 着色散点图、RSRP 直方图、16扇区覆盖雷达
       3. 汇总 CSV（可直接回填研究报告表 5-3 / 采集计划记录表）
依赖:  pip install matplotlib  (可选: --fit 启用边界拟合图需要 shapely)
"""
import argparse
import json
import math
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    HAS_MPL = True
except ImportError:
    HAS_MPL = False

R_EARTH = 6371000.0
THRESHOLD_DBM = -110.0  # 与 App 默认一致，可用 --threshold 覆盖


def haversine(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R_EARTH * math.asin(math.sqrt(a))


def direction_bin(lat0, lon0, lat, lon):
    """相对场次质心的 16 扇区编号 0..15（北=0，顺时针）"""
    brg = math.degrees(math.atan2(lon - lon0, lat - lat0))  # 以北为 0
    return int(((brg + 360) % 360) // 22.5)


def load_jsonl(path):
    samples = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            if obj.get("lat") is not None and obj.get("lon") is not None:
                samples.append(obj)
    return samples


def analyze_session(jsonl_path, out_dir, threshold, make_plots=True):
    s = load_jsonl(jsonl_path)
    if not s:
        print(f"[跳过] {jsonl_path.name}: 无有效样本")
        return None

    lat0 = sum(x["lat"] for x in s) / len(s)
    lon0 = sum(x["lon"] for x in s) / len(s)

    rsrps = [x["rsrp"] for x in s if x.get("rsrp") is not None]
    on_target = sum(1 for x in s if x.get("onTarget"))
    target_seen = sum(1 for x in s if x.get("targetSeen"))
    accs = [x["accuracyM"] for x in s if x.get("accuracyM") is not None]

    dirs = {direction_bin(lat0, lon0, x["lat"], x["lon"]) for x in s
            if haversine(lat0, lon0, x["lat"], x["lon"]) > 30}
    sectors = len(dirs)

    ts0, ts1 = s[0].get("tsEpochMs"), s[-1].get("tsEpochMs")
    dur_min = (ts1 - ts0) / 60000.0 if ts0 and ts1 else 0.0

    track_km = 0.0
    for a, b in zip(s, s[1:]):
        track_km += haversine(a["lat"], a["lon"], b["lat"], b["lon"]) / 1000.0

    # 内点占比（RSRP >= 阈值）
    inliers = sum(1 for r in rsrps if r >= threshold)
    # 简化置信度（与 App 公式同族）: 0.5*样本因子 + 0.5*方向覆盖度
    conf = 100 * (0.5 * min(1.0, len(s) / 120.0) + 0.5 * sectors / 16.0)

    row = {
        "场次": jsonl_path.stem,
        "样本数": len(s),
        "时长min": round(dur_min, 1),
        "轨迹km": round(track_km, 2),
        "目标驻留样本": on_target,
        "目标可见样本": target_seen,
        "RSRP均值": round(sum(rsrps) / len(rsrps), 1) if rsrps else "",
        "RSRP中位": sorted(rsrps)[len(rsrps) // 2] if rsrps else "",
        "内点(≥阈值)": f"{inliers}/{len(rsrps)}",
        "平均精度m": round(sum(accs) / len(accs), 1) if accs else "",
        "扇区覆盖/16": sectors,
        "置信度": round(conf),
        "阈值dBm": threshold,
    }

    if make_plots and HAS_MPL:
        fig, axes = plt.subplots(1, 3, figsize=(16, 5))
        ax = axes[0]
        sc = ax.scatter([x["lon"] for x in s], [x["lat"] for x in s],
                        c=[x.get("rsrp") or -140 for x in s], cmap="RdYlGn",
                        s=12, vmin=-120, vmax=-60)
        ax.plot(lon0, lat0, "r*", ms=14, label="质心")
        ax.set_title("轨迹 (RSRP 着色)")
        ax.set_xlabel("经度"); ax.set_ylabel("纬度"); ax.legend(); fig.colorbar(sc, ax=ax)

        ax = axes[1]
        if rsrps:
            ax.hist(rsrps, bins=24, color="#22D3EE", edgecolor="#0B1020")
            ax.axvline(threshold, color="red", ls="--", label=f"阈值 {threshold}")
            ax.set_title("RSRP 分布"); ax.set_xlabel("dBm"); ax.legend()

        ax = axes[2]
        ax.set_theta_zero_location("N"); ax.set_theta_direction(-1)
        theta = [i * math.pi / 8 for i in range(16)] + [0]
        counts = Counter(direction_bin(lat0, lon0, x["lat"], x["lon"]) for x in s
                         if haversine(lat0, lon0, x["lat"], x["lon"]) > 30)
        vals = [counts.get(i, 0) for i in range(16)] + [counts.get(0, 0)]
        ax.plot(theta, vals, color="#A78BFA"); ax.fill(theta, vals, alpha=0.3, color="#A78BFA")
        ax.set_title("16 扇区样本分布")

        fig.suptitle(f"围猎场次分析 · {jsonl_path.stem} · 置信度 {row['置信度']}")
        fig.tight_layout()
        out_png = out_dir / f"{jsonl_path.stem}.png"
        fig.savefig(out_png, dpi=130)
        plt.close(fig)
        print(f"[图表] {out_png}")

    return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input", help="sessions 目录或单个 .jsonl")
    ap.add_argument("-o", "--out", default="analysis_out", help="输出目录")
    ap.add_argument("--threshold", type=int, default=THRESHOLD_DBM)
    ap.add_argument("--no-plots", action="store_true")
    a = ap.parse_args()

    src = Path(a.input)
    files = sorted(src.glob("*.jsonl")) if src.is_dir() else [src]
    if not files:
        print("未找到 .jsonl 文件", file=sys.stderr)
        sys.exit(1)

    out_dir = Path(a.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    rows = [r for f in files if (r := analyze_session(f, out_dir, a.threshold, not a.no_plots))]
    if rows:
        import csv
        csv_path = out_dir / "summary.csv"
        with open(csv_path, "w", newline="", encoding="utf-8-sig") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)
        print(f"\n[汇总] {csv_path}")
        for r in rows:
            print(f"  {r['场次']}: {r['样本数']}样本 目标驻留{r['目标驻留样本']} "
                  f"扇区{r['扇区覆盖/16']}/16 置信度{r['置信度']}")


if __name__ == "__main__":
    main()
