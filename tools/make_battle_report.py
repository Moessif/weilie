import os
from PIL import Image, ImageDraw, ImageFont

W, H = 1240, 1990
BG = (11, 16, 32)
BG2 = (17, 24, 52)
CYAN = (56, 189, 248)
GREEN = (74, 222, 128)
AMBER = (245, 158, 11)
PURPLE = (167, 139, 250)
TEXT = (230, 237, 247)
DIM = (148, 163, 184)

FONT = r"C:\Windows\Fonts\msyh.ttc"
FONTB = r"C:\Windows\Fonts\msyhbd.ttc"

def f(size, bold=False):
    return ImageFont.truetype(FONTB if bold else FONT, size)

img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)

# 顶部青色渐变条
for y in range(6):
    d.rectangle([0, y, W, y], fill=(34, 211, 238))

# 标题
d.text((64, 64), "战  报", font=f(78, True), fill=TEXT)
d.text((64, 168), "围猎 · 从 v0.1 到 v0.11.2", font=f(40, True), fill=CYAN)
d.text((64, 226), "2026-11-06 01:27  —  2026-11-11 07:10   ·   5 天 5 小时 43 分", font=f(24), fill=DIM)

# 数据卡
cards = [
    ("38", "次提交", CYAN),
    ("24", "个版本", GREEN),
    ("162", "个单元测试", PURPLE),
    ("808", "个真机采样点", AMBER),
    ("23,006", "行客户端代码", CYAN),
]
cw, ch, gap = 216, 150, 14
x0, y0 = 64, 290
for i, (num, label, color) in enumerate(cards):
    x = x0 + i * (cw + gap)
    d.rounded_rectangle([x, y0, x + cw, y0 + ch], radius=18, fill=BG2, outline=(35, 52, 88), width=2)
    d.text((x + 22, y0 + 24), num, font=f(46, True), fill=color)
    d.text((x + 22, y0 + 96), label, font=f(20), fill=DIM)

# 提交时刻分布
y = 490
d.text((64, y), "五天里的提交时刻（38 次）", font=f(30, True), fill=TEXT)
buckets = [
    ("00-03", 4), ("03-06", 6), ("06-09", 3), ("09-12", 3),
    ("12-15", 1), ("15-18", 5), ("18-21", 6), ("21-24", 10),
]
bx, by = 64, y + 60
bw, bh = 128, 190
maxv = max(v for _, v in buckets)
for i, (label, v) in enumerate(buckets):
    x = bx + i * (bw + 12)
    h = max(8, int(bh * v / maxv))
    color = AMBER if i in (0, 1, 7) else CYAN
    d.rounded_rectangle([x, by + (bh - h), x + bw, by + bh], radius=10, fill=(color[0]//4, color[1]//4, color[2]//4))
    d.rounded_rectangle([x, by + (bh - h), x + bw, by + bh], radius=10, outline=color, width=2)
    d.text((x + 18, by + bh - h + 10), str(v), font=f(30, True), fill=color)
    d.text((x + 26, by + bh + 14), label, font=f(20), fill=DIM)
d.text((W - 360, y), "凌晨 00–06 点占 26%", font=f(22), fill=AMBER)

# 关键时间线
y = 850
d.text((64, y), "关键节点", font=f(30, True), fill=TEXT)
rows = [
    ("11-06 01:27", "五个里程碑连跑：引擎 → 服务端 → 全量 UI → APK → 端到端", CYAN),
    ("11-07 09:14", "v0.3.0 取证相机；10:29 v0.4.0 水印 + 验真 + 主题", GREEN),
    ("11-08 23:33", "v0.5.0 一口气实现「新功能十章」", PURPLE),
    ("11-09 07:04", "v0.5.2 高密度屏闪退修复；中午 13:20 做服务端基站库", AMBER),
    ("11-10 22:12", "v0.9.0 自由绘制；23:43 v0.10.0；23:58 v0.11.0", CYAN),
    ("11-11 06:50", "v0.11.1 双指手势重写；07:10 v0.11.2 模拟实测区域", GREEN),
]
ry = y + 56
for t, s, c in rows:
    d.text((64, ry), t, font=f(24, True), fill=c)
    d.text((280, ry), s, font=f(23), fill=TEXT)
    ry += 62

# 你的作战时段
y = ry + 10
d.text((64, y), "你的作战时段（真机任务记录）", font=f(30, True), fill=TEXT)
tiles = [
    ("06:40–07:15", "清晨走测\n连续三天", GREEN),
    ("10:14 / 13:20", "上午实测\n中午不睡", CYAN),
    ("17:00–20:15", "傍晚第二轮", AMBER),
    ("21:00–23:47", "夜间主战场\n单次最多 165 点", PURPLE),
]
tw, th = 262, 170
tx, ty = 64, y + 60
for i, (t, s, c) in enumerate(tiles):
    x = tx + i * (tw + 14)
    d.rounded_rectangle([x, ty, x + tw, ty + th], radius=16, fill=BG2, outline=c, width=2)
    d.text((x + 20, ty + 20), t, font=f(26, True), fill=c)
    d.multiline_text((x + 20, ty + 70), s, font=f(22), fill=TEXT, spacing=8)

# 成绩单
y = ty + th + 56
d.text((64, y), "成绩单", font=f(30, True), fill=TEXT)
lines = [
    ("版本", "v0.1 → v0.11.2，24 个版本，16 个 APK 留档"),
    ("测试", "21 个测试类 / 162 个用例全绿"),
    ("文档", "24 份 Markdown + 申报书 docx + 答辩脚本"),
    ("交付", "GitHub 源码 + 服务端 jar，APK 走 Release v0.11.2"),
]
ly = y + 54
for k, v in lines:
    d.text((64, ly), k, font=f(24, True), fill=CYAN)
    d.text((190, ly), v, font=f(23), fill=TEXT)
    ly += 48

# 页脚
d.text((64, H - 110), "github.com/Moessif/weilie", font=f(24, True), fill=CYAN)
d.text((64, H - 66), "围猎 · 普通手机基站覆盖实测与搜捕协同系统", font=f(20), fill=DIM)

out = r"E:\0workspace\dshnext\Marches\GAKJ\weilie\dist\战报-v0.1-v0.11.2.png"
img.save(out, "PNG")
print(out)
