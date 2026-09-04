"""生成 BREAKFRONT 启动开屏品牌图（覆盖 Mojang Studios 开屏）。
输出 512x512 PNG -> client/src/main/resources/assets/minecraft/textures/gui/title/mojangstudios.png
矢量风：深色底 + 斜向色带 + 菱形徽标 + 字距标题。仅本机生成用（成品入库）。
"""
from PIL import Image, ImageDraw, ImageFont
from pathlib import Path

W = H = 512
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "client/src/main/resources/assets/minecraft/textures/gui/title/mojangstudios.png"

BG0 = (10, 13, 18)
BG1 = (18, 23, 31)
ORANGE = (232, 98, 44)
AMBER = (232, 185, 60)
WHITE = (242, 244, 248)
GREY = (90, 100, 115)

FW = "C:/Windows/Fonts/arialbd.ttf"
FC = "C:/Windows/Fonts/msyhbd.ttc"  # 微软雅黑粗体（中文）

img = Image.new("RGBA", (W, H))
dr = ImageDraw.Draw(img)

# 垂直渐变
for y in range(H):
    t = y / H
    c = tuple(int(BG0[i] + (BG1[i] - BG0[i]) * t) for i in range(3))
    dr.line([(0, y), (W, y)], fill=c + (255,))

# 斜向装饰带（BF 层次感）
def poly(x, y, w, h, slant, fill):
    dr.polygon([(x + slant, y), (x + w + slant, y), (x + w, y + h), (x, y + h)], fill=fill)

poly(-120, 300, 340, 6, 60, (255, 255, 255, 26))
poly(330, -40, 260, 5, -50, (255, 255, 255, 18))
poly(-60, 90, 220, 3, 40, (255, 255, 255, 12))
# 左缘主色带
dr.rectangle([0, 0, 6, H], fill=ORANGE + (255,))
dr.rectangle([6, 0, 9, H], fill=AMBER + (170,))

# 徽标：菱形轮廓 + 实心小菱形
cx, cy = 110, 130
half = 46
dr.polygon([(cx, cy - half), (cx + half, cy), (cx, cy + half), (cx - half, cy)], outline=(255, 255, 255, 60), width=2)
dr.polygon([(cx, cy - 20), (cx + 20, cy), (cx, cy + 20), (cx - 20, cy)], fill=ORANGE + (255,))

# 标题（字距用逐字绘制）
f_title = ImageFont.truetype(FW, 46)
f_sub = ImageFont.truetype(FC, 22)
f_foot = ImageFont.truetype(FW, 12)

x0 = 40
y0 = 190
spacing = 50
for ch in "BREAKFRONT":
    dr.text((x0, y0), ch, font=f_title, fill=WHITE + (255,))
    x0 += dr.textlength(ch, font=f_title) + 10

dr.text((48, 272), "破阵前线 · 大战场对战", font=f_sub, fill=GREY + (255,))
poly(48, 320, 168, 6, 16, ORANGE + (255,))

dr.text((30, 468), "FABRIC MODPACK  ·  COMMUNITY BATTLEFIELD", font=f_foot, fill=(120, 128, 140, 255))
dr.text((512 - dr.textlength("BREAKFRONT", font=f_foot) - 30, 468), "BREAKFRONT", font=f_foot, fill=GREY + (255,))

OUT.parent.mkdir(parents=True, exist_ok=True)
img.save(OUT)
print("saved", OUT, img.size)
