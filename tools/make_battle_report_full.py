# -*- coding: utf-8 -*-
"""完整版战报海报：v0.1 -> v0.11.2，全部版本更新记录。"""
import os
from PIL import Image, ImageDraw, ImageFont

W = 1400
M = 70  # 左右边距

BG = (11, 16, 32)
BG2 = (16, 23, 48)
LINE = (33, 48, 82)
TEXT = (230, 237, 247)
DIM = (148, 163, 184)
FAINT = (100, 116, 139)

FONT = r"C:\Windows\Fonts\msyh.ttc"
FONTB = r"C:\Windows\Fonts\msyhbd.ttc"

C_CYAN = (56, 189, 248)
C_GREEN = (74, 222, 128)
C_AMBER = (245, 158, 11)
C_PURPLE = (167, 139, 250)
C_PINK = (244, 114, 182)
C_BLUE = (96, 165, 250)


def f(size, bold=False):
    return ImageFont.truetype(FONTB if bold else FONT, size)


# ---------- 数据 ----------
# 每天：(标题, 副标题, 颜色, [版本块])
# 版本块：(版本, 时间, 提交, [(条目, 是否高亮), ...])
DAYS = [
    ("Day 1 · 11-06 周四", "凌晨跑完五个里程碑，晚上接百度地图，零点前连发三个版本", C_CYAN, [
        ("v0.1.0", "01:27", "c0affc0", [
            ("核心引擎全绿：采集/解析/拟合/导出/同步 + Compose 设计系统，首版 APK 17.5 MB", True),
            ("五个里程碑连跑：服务端 24/24 冒烟、全量 UI 10 屏、APK 归档、模拟器端到端 112 样本", False),
        ]),
        ("v0.2.0", "17:41", "067503f", [
            ("形式整改：STRICT_SPEC + 申报书 docx（官方模板直填）+ 培训回放 200 样本", False),
            ("协议 v1.1（zones/promote/replace）+ 培训解说卡 + 地图崩溃修复（sinr Int→%.1f）", False),
        ]),
        ("v0.2.1", "17:49", "b26f0b1", [
            ("地图页顶部布局重构：图层/目标按钮与数据栏同行互不遮挡", False),
        ]),
        ("v0.2.2", "23:40", "7648aba", [
            ("百度地图引擎接入：mapGlass 浮层、数据栏双槽折叠、RSRP 趋势、相机推送兜底", True),
            ("修覆盖物不显示（map.clear 竞态→自记账 remove）+ WGS84/BD09 换算 + zoom 偏移校准", False),
            ("基站编号语义层（5G NSA / NCI+HEX 分解 / 多格式输入）+ 二维码崩溃 + 房间离线修复", False),
        ]),
        ("v0.2.3", "23:52", "7b2caf3", [
            ("拍照权限门；震动/切基站提醒改点击直切；按钮统一 44dp；NCI 真实样例 5677244420", False),
            ("解析结果显示十六进制与 gNB/CID 分解", False),
        ]),
    ]),
    ("Day 2 · 11-07 周五", "取证相机、在线验真、主题系统与多人协同 P1", C_GREEN, [
        ("v0.2.4", "00:31", "0c35148", [
            ("工具箱 A1–A3 方块化；长按拖拽面板半透明；顶部删除区重做 + 落点 55% 禁区", False),
        ]),
        ("v0.3.0", "09:14", "1806125", [
            ("取证相机：全屏 CameraX + 实时水印 + 设备类型 chips + GPS 落点 + 烧水印", True),
            ("照片标点回填与点击查看（Marker 改 Circle 修吞点击）；双卡 SIM 制式层；标点详情 Sheet", False),
        ]),
        ("v0.4.0", "10:29", "2757e2b", [
            ("微信式水印：大字时间/逆地理地址/可编辑备注/5 类可疑对象", True),
            ("系统 Geocoder 兜底（AK 230 不再阻塞）；双卡 5G NSA 显示", False),
            ("照片 SHA256 在线验真（HMAC 签名/验证码/二维码网页验真）+ 上传比对", False),
            ("主题系统：明暗 + 10 预设色 + 自定义色相/饱和度；跨主题适配", False),
        ]),
        ("v0.4.2", "22:45", "347bc35", [
            ("缩放范围按百度真实范围回填，消除「放到最大后点放大反而变小」", False),
            ("QA 两轮：专家面板七分组/普通模式不露 dBm/图层浮层/卫星图层/工具箱全高", False),
            ("权限说明弹窗；A2 数据区放大（6 行 12sp + 110dp 带刻度折线）", False),
        ]),
        ("v0.4.3", "23:38", "a95ef1d", [
            ("多人协同 P1：标记同步/位置上报/任务圈去重/拍照打点/观战标注/队长恢复", True),
            ("Loc/SamplePing 实时更新；恢复会话元数据、回放末尾、照片标题中文化", False),
        ]),
    ]),
    ("Day 3 · 11-08 周六", "00:06 发版收尾，晚上一口气实现「新功能十章」", C_AMBER, [
        ("v0.4.4", "00:06", "848a4a0", [
            ("首启向导新增权限检查页：5 项授权 + 自检 + 一键全授", True),
            ("双卡同小区去重；5G NSA 显示；工具箱压到 28%；缩放步长 0.24 级", False),
        ]),
        ("v0.4.5", "00:42", "2049b8b", [
            ("已连接基站展开：5G NR 小区与 LTE 锚点分行，不再只显示 LTE", True),
            ("工具箱改 5 列网格，两行十工具一屏全见；面板 32%（约 40% 屏）", False),
        ]),
        ("v0.5.0", "23:33", "5b62fb3", [
            ("打点分类：主区实心 / 邻区彩色 / 脱离空心；GPS 双重安检（50m / 3m）", True),
            ("高精度面积：Alpha Shape 凹包、边缘内缩、Douglas–Peucker 抽稀", True),
            ("多人：点地图落标点、自由绘制任务范围并分配；标点导航", False),
            ("信号热力图（IDW + GroundOverlay）；带底图导出；理论覆盖区（圆/扇/环带）", False),
            ("静默跟踪：主区 1s / 邻区 0.3s / 消失持续震动，前台服务保活", False),
        ]),
        ("v0.5.1", "23:56", "6f6b7db", [
            ("空心点不参与面积；DP 抽稀；边缘内缩；GPS 门槛可调；单人标点导航；热力阈值可配", False),
        ]),
    ]),
    ("Day 4 · 11-09 周日", "基站库、闪退修复、卡尔曼重写与历史记录管理", C_PURPLE, [
        ("v0.5.2", "07:04", "6eff839", [
            ("修高密度屏闪退：比例尺宽度溢出 Compose Constraints 262143px 上限", True),
            ("比例尺 clamp 16–150dp；自动取景缩放下限 18 级", False),
        ]),
        ("v0.6.0", "16:43", "5f09023", [
            ("服务端 OpenCelliD 代理：瓦片缓存 + 单小区缓存 + /api/cells/{near,cell,stats}", True),
            ("基站库图层；按基站为中心扇形/等面积圈层划区下发", False),
        ]),
        ("v0.6.1", "21:29", "d8af0ab", [
            ("打点滞后修复：纯位置卡尔曼改匀速（CV）模型（q=1.6、r=1.0、3σ 重捕获）", True),
            ("GPS 重合默认 1 米；首页重复登录入口删除；命中态徽标；5G 锚点文案", False),
        ]),
        ("v0.6.2", "22:39", "731a077", [
            ("历史记录长按删除 / 选择删除 / 全部删除", False),
        ]),
        ("v0.7.0", "23:39", "68ff060", [
            ("任务参数校验 + 目标清单（分别输入、保存、选用）", True),
            ("说明气泡：目标参数/判定阈值/热力参数/静默跟踪各一枚「!」", False),
            ("标点命名、可见范围、删除；静默跟踪振动强度 1–5", False),
        ]),
    ]),
    ("Day 5 · 11-10 周一", "真机打磨：数据层重构后一夜四个版本", C_PINK, [
        ("v0.8.0", "17:31", "25973c7", [
            ("基站数据层重构：按数据卡 + PLMN 取小区；SA/NSA 状态机；邻区原因提示", True),
            ("未命中轨迹灰色分段；打点间隔手动输入 0.2–600s；RSRP 同源", False),
        ]),
        ("v0.8.1", "20:37", "10c71f1", [
            ("真机五问：标点删除入口/旗子命名/按钮误触/面积默认开/换任务清理", True),
            ("修邻区数恒 0、地图巨大蓝斑（zoom=0）、继续任务目标丢失", False),
        ]),
        ("v0.9.0", "22:12", "baa191a", [
            ("多人自由绘制任务范围：单指画/双指拖/撤销重画完成 + 分配", True),
            ("跨设备取证记录：photoCode 进协议 + 远程详情（验证码/坐标/哈希/签名）", True),
            ("单人删标点即时生效；采样点改固定像素图标", False),
        ]),
        ("v0.10.0", "23:43", "c5e47cf", [
            ("七项：拍照标点删除+确认、命中热区收紧、多人叠加图层补齐", True),
            ("浅色主题系统栏/底色；任务区以自身定位为中心；定位到队员；emoji 图标", False),
        ]),
        ("v0.11.0", "23:58", "7ae2c1a", [
            ("自由绘制实时预览：轨迹折线 + 多边形指纹 + 实时面积（m²/公顷）", True),
            ("拍照标点按任务隔离（PhotoRecord.taskId）；取消浅色只留深色", False),
        ]),
    ]),
    ("Day 6 · 11-11 周二", "收口：双指手势重写 + 模拟实测区域", C_BLUE, [
        ("v0.11.1", "06:50", "7afcd52", [
            ("双指手势重写：起点锚定 + 投影实测米/像素 + 手动捏合缩放", True),
            ("修「双指一碰就飘、无法缩放」（理论米/像素 × 屏幕密度 + 逐帧累加）", False),
        ]),
        ("v0.11.2", "07:10", "aed1ec9", [
            ("模拟实测区域：轨迹点画圆/方块连成片，半径 5–200m 可调", True),
            ("进图巨点修复（渲染半径改用相机 zoom）；TrackSanity 飞点剔除；图层面板可滚动", False),
        ]),
    ]),
]

# ---------- 配色/尺寸 ----------
VERSION_COLOR = {
    "v0.1.0": C_CYAN, "v0.2.0": C_CYAN, "v0.2.1": C_CYAN, "v0.2.2": C_CYAN, "v0.2.3": C_CYAN,
    "v0.2.4": C_GREEN, "v0.3.0": C_GREEN, "v0.4.0": C_GREEN, "v0.4.2": C_GREEN, "v0.4.3": C_GREEN,
    "v0.4.4": C_AMBER, "v0.4.5": C_AMBER, "v0.5.0": C_AMBER, "v0.5.1": C_AMBER,
    "v0.5.2": C_PURPLE, "v0.6.0": C_PURPLE, "v0.6.1": C_PURPLE, "v0.6.2": C_PURPLE, "v0.7.0": C_PURPLE,
    "v0.8.0": C_PINK, "v0.8.1": C_PINK, "v0.9.0": C_PINK, "v0.10.0": C_PINK, "v0.11.0": C_PINK,
    "v0.11.1": C_BLUE, "v0.11.2": C_BLUE,
}

TITLE_F = f(32, True)      # 版本块标题
META_F = f(19)             # 时间/哈希
BODY_F = f(22)             # 条目
DAY_F = f(38, True)
DAYSUB_F = f(21)

PAD_X = 26
PAD_Y = 20
LINE_H = 33


def wrap_cn(text, font, maxw):
    lines, cur = [], ""
    for ch in text:
        if font.getlength(cur + ch) <= maxw:
            cur += ch
        else:
            lines.append(cur)
            cur = ch
    if cur:
        lines.append(cur)
    return lines or [""]


# 预排版，计算总高度
blocks = []
y = 0
for day_title, day_sub, day_color, versions in DAYS:
    blocks.append(("day", day_title, day_sub, day_color, 0))
    y += 96
    for ver, tm, gh, items in versions:
        content_w = W - 2 * M - 2 * PAD_X - 8
        lines = []
        for text, hi in items:
            for ln in wrap_cn(text, BODY_F, content_w - 8):
                lines.append((ln, hi))
        bh = PAD_Y * 2 + 44 + 10 + len(lines) * LINE_H
        blocks.append(("ver", ver, tm, gh, bh, lines))
        y += bh + 16
    y += 26

TOP = 0
# 头部高度
HEADER_H = 700
FOOTER_H = 150
H = HEADER_H + y + FOOTER_H + 40

img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)

# 顶部渐变条
for i in range(8):
    t = i / 7
    col = (int(34 + (56 - 34) * t), int(211 + (189 - 211) * t), int(238 + (248 - 238) * t))
    d.rectangle([0, i, W, i], fill=col)

# ---------- 头部 ----------
d.text((M, 46), "战  报 · 完整版", font=f(76, True), fill=TEXT)
d.text((M, 150), "围猎  v0.1  →  v0.11.2", font=f(44, True), fill=C_CYAN)
d.text((M, 212), "2026-11-06 01:27  —  2026-11-11 07:15   ·   5 天 5 小时 48 分   ·   全 部 版 本 更 新 记 录",
       font=f(23), fill=DIM)

cards = [
    ("38", "次提交", C_CYAN),
    ("26", "个版本", C_GREEN),
    ("162", "个单元测试", C_PURPLE),
    ("23,006", "行客户端代码", C_AMBER),
    ("808", "个真机采样点", C_PINK),
]
cw = (W - 2 * M - 4 * 14) // 5
ch = 132
x0, y0 = M, 276
for i, (num, label, color) in enumerate(cards):
    x = x0 + i * (cw + 14)
    d.rounded_rectangle([x, y0, x + cw, y0 + ch], radius=16, fill=BG2, outline=LINE, width=2)
    d.text((x + 18, y0 + 20), num, font=f(40, True), fill=color)
    d.text((x + 18, y0 + 84), label, font=f(19), fill=DIM)

# 说明行
d.text((M, 432), "五天里交出的 26 个版本 —— 每一版的更新，按时间先后全部记在这里",
       font=f(22), fill=TEXT)
d.text((M, 470), "配色：青色 = 起跑   绿色 = 取证与协同   橙色 = 十章   紫色 = 基站库与细节   粉色 = 真机打磨   蓝色 = 收口",
       font=f(18), fill=FAINT)

# 分隔线
d.line([M, 520, W - M, 520], fill=LINE, width=2)

# ---------- 正文 ----------
cur_y = HEADER_H
for blk in blocks:
    if blk[0] == "day":
        _, title, sub, color, _ = blk
        d.rounded_rectangle([M, cur_y + 10, M + 8, cur_y + 64], radius=4, fill=color)
        d.text((M + 24, cur_y + 6), title, font=DAY_F, fill=color)
        d.text((M + 24, cur_y + 56), sub, font=DAYSUB_F, fill=DIM)
        cur_y += 96
        continue

    _, ver, tm, gh, bh, lines = blk
    color = VERSION_COLOR.get(ver, C_CYAN)
    d.rounded_rectangle([M, cur_y, W - M, cur_y + bh], radius=14, fill=BG2, outline=(26, 38, 66), width=1)
    d.rounded_rectangle([M, cur_y, M + 6, cur_y + bh], radius=3, fill=color)
    d.text((M + PAD_X, cur_y + PAD_Y - 2), ver, font=TITLE_F, fill=color)
    tx = M + PAD_X + d.textlength(ver, font=TITLE_F) + 16
    d.text((tx, cur_y + PAD_Y + 8), tm, font=META_F, fill=DIM)
    d.text((W - M - PAD_X - 96, cur_y + PAD_Y + 8), gh, font=META_F, fill=FAINT)
    ly = cur_y + PAD_Y + 44
    for text, hi in lines:
        if hi:
            d.rounded_rectangle([M + PAD_X - 12, ly + 9, M + PAD_X - 6, ly + 15], radius=3, fill=color)
            d.text((M + PAD_X, ly), text, font=BODY_F, fill=TEXT)
        else:
            d.text((M + PAD_X, ly), text, font=BODY_F, fill=(196, 208, 226))
        ly += LINE_H
    cur_y += bh + 16

# ---------- 页脚 ----------
fy = H - FOOTER_H
d.line([M, fy, W - M, fy], fill=LINE, width=2)
d.text((M, fy + 26), "github.com/Moessif/weilie", font=f(24, True), fill=C_CYAN)
d.text((M, fy + 66), "围猎 · 普通手机基站覆盖实测与搜捕协同系统    ｜    最新：v0.11.2（versionCode 25）    ｜    服务端：47.89.181.85:8080",
       font=f(18), fill=DIM)
d.text((M, fy + 98), "由 38 次提交、26 个版本、162 个单元测试、808 个真机采样点，以及五个不睡的凌晨组成",
       font=f(18), fill=FAINT)

out = r"E:\0workspace\dshnext\Marches\GAKJ\weilie\dist\战报-v0.1-v0.11.2-完整版.png"
img.save(out, "PNG")
print(out, img.size)
