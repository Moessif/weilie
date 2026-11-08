# -*- coding: utf-8 -*-
"""生成培训模式回放数据 assets/training/demo_session.jsonl
剧本：学警从弱信号区走进目标基站覆盖核心，绕两圈观察，再走出边界。
RSRP 模型：-60 - 0.09*d(米) + 噪声；目标小区 ECI 123456789（与新建任务页示例一致）。
"""
import json
import math
import random
from pathlib import Path

random.seed(42)
LAT0, LON0 = 31.2304, 121.4737  # 目标基站位置（示意）
ECI = 123456789
M_PER_DEG_LAT = 110540.0
M_PER_DEG_LON = 111320.0 * math.cos(math.radians(LAT0))

# 路线：时间序 [(朝向, 距离米)] —— 由远及近，绕核心两圈（r=250m、r=450m），再远离
route = []
# 阶段1：从 800m 外走近核心（弱→强）
for i in range(40):
    d = 800 - i * 16  # 800 -> 176
    route.append(("approach", d))
# 阶段2：绕内圈 r≈220m 一圈
for a in range(0, 360, 6):
    route.append(("inner", 220 + 12 * math.sin(math.radians(a * 3))))
# 阶段3：绕外圈 r≈520m 一圈（边界附近，信号中等）
for a in range(0, 360, 6):
    route.append(("outer", 520 + 25 * math.sin(math.radians(a * 2))))
# 阶段4：走出覆盖（520 -> 1000，信号跌穿 -110）
for i in range(40):
    route.append(("leave", 545 + i * 12))

samples = []
t0 = 1793990400000  # 固定起始时戳，便于回放稳定
ts = t0
lat, lon = LAT0, LON0
bearing = 0.0
prev = None
for idx, (phase, d) in enumerate(route):
    # 沿路线推进位置：approach 沿一条直线，圆圈按角度
    if phase == "approach":
        ang = math.radians(205)
        lat = LAT0 + d * math.cos(ang) / M_PER_DEG_LAT
        lon = LON0 + d * math.sin(ang) / M_PER_DEG_LON
    elif phase in ("inner", "outer"):
        r = d
        ang = math.radians((idx * 6) % 360)
        lat = LAT0 + r * math.cos(ang) / M_PER_DEG_LAT
        lon = LON0 + r * math.sin(ang) / M_PER_DEG_LON
    else:  # leave
        ang = math.radians(30)
        lat = LAT0 + d * math.cos(ang) / M_PER_DEG_LAT
        lon = LON0 + d * math.sin(ang) / M_PER_DEG_LON

    rsrp = int(max(-125, -60 - 0.09 * d + random.gauss(0, 3)))
    rsrq = int(max(-20, -8 - (d / 800.0) * 8 + random.gauss(0, 1)))
    sinr = int(max(-3, 28 - (d / 800.0) * 30 + random.gauss(0, 2)))
    rssi = int(rsrp + 27 + random.gauss(0, 4))
    neighbors = sorted(random.sample([47108, 47109, 88231, 88232, 95277, 95278, 31003], k=random.randint(4, 6)))
    rec = {
        "tsEpochMs": ts, "lat": round(lat, 7), "lon": round(lon, 7),
        "accuracyM": round(random.uniform(4.0, 9.0), 1), "provider": "gps",
        "servingRat": "LTE", "servingMcc": "460", "servingMnc": "00",
        "servingLacTac": 8514, "servingCi": ECI, "servingPci": 117,
        "servingEarfcn": 1650, "servingBand": 3,
        "rsrp": rsrp, "rsrq": rsrq, "sinr": sinr, "rssi": rssi,
        "neighborCount": len(neighbors),
        "neighborCis": [str(c) for c in neighbors],
        "wifiCount": random.randint(1, 6),
        "onTarget": True, "targetSeen": True, "targetRsrp": rsrp,
    }
    samples.append(rec)
    ts += 1000

out = Path(r"E:\0workspace\dshnext\Marches\GAKJ\weilie\android\app\src\main\assets\training")
out.mkdir(parents=True, exist_ok=True)
with open(out / "demo_session.jsonl", "w", encoding="utf-8") as f:
    for s in samples:
        f.write(json.dumps(s, ensure_ascii=False) + "\n")

# 阶段解说卡（供回放 UI 用）
guide = [
    {"from": 0, "to": 39, "title": "靠近目标", "text": "现在离目标基站约 800 米，信号很弱（RSRP 低于 -110dBm）。注意地图上还没出现覆盖边界，我们需要走进它的实际覆盖范围开始打点。"},
    {"from": 40, "to": 99, "title": "内圈绕行", "text": "信号变强了！现在在基站核心覆盖区内绕行一圈，每一秒自动打一个点，系统正在用这些点拟合覆盖边界。"},
    {"from": 100, "to": 159, "title": "外圈探边", "text": "扩大半径到 500 米左右再绕一圈——信号降到中等，边界多边形正在收敛。测量要覆盖每个方向，置信度才高。"},
    {"from": 160, "to": 199, "title": "走出边界", "text": "信号跌破阈值了！这说明我们离开了目标基站的覆盖范围。至此边界已经围出来，可以查看结果并导出。"},
]
with open(out / "demo_guide.json", "w", encoding="utf-8") as f:
    json.dump(guide, f, ensure_ascii=False, indent=2)

print(f"OK: {len(samples)} samples -> {out / 'demo_session.jsonl'}")
print(f"rsrp range: {min(s['rsrp'] for s in samples)} .. {max(s['rsrp'] for s in samples)}")
