"""生成 BREAKFRONT 专属 UI 材质包（只改 GUI，不动方块贴图）。

产物：client/src/main/resources/resourcepacks/bf_ui/
  pack.mcmeta
  assets/minecraft/textures/gui/sprites/widget/*.png (+ 九宫格 .mcmeta，见 MC_META)
  assets/minecraft/textures/gui/{menu,menu_list,inworld_menu,inworld_menu_list}_background.png
  assets/minecraft/textures/gui/{header, inworld_header, inworld_footer}_separator.png
  assets/minecraft/textures/gui/{tab_header_background,title/background/panorama_overlay}.png

本脚本**不依赖任何 Mojang 资产**（九宫格切边数字已内联在 MC_META），克隆即可重跑；
仓库里也不要提交从原版 jar 抽出的参照贴图（见 .gitignore）。

为什么放 resourcepacks/ 而非 assets/ 根：这里用 Fabric 的「内置资源包」机制
（ResourceManagerHelper.registerBuiltinResourcePack，见 BreakfrontClient），
由官方 API 保证覆盖原版贴图，不依赖「模组 assets/minecraft 能否覆盖原版」这一
语义模糊点。路径必须是 resourcepacks/<id>/...，pack.mcmeta 的 pack_format=34（1.21.1）。

设计语言严格对齐 BfTheme（2042 深空冷底 #070B12 / 面板 #131E2C / 描边 #22364A /
荧光青 #35E6D2 / 冷青高光 #6FE8FF / 文本 #EAF2F8）。全部为硬边像素绘制，
不使用抗锯齿——MC 的 GUI 缩放是整数倍 NEAREST，抗锯齿反而会糊边。

用法：python scripts/gen_bf_ui_pack.py [--preview]
  --preview 额外输出 docs/bf-ui-preview.png（放大对比图，供人眼验收）
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys

from PIL import Image

# --------------------------------------------------------------------------- #
# 设计令牌（与 client/.../bf/BfTheme.java 保持一致）
# --------------------------------------------------------------------------- #
BG_DEEP = (7, 11, 18, 255)          # #070B12
BG_UP = (16, 26, 38, 255)           # #101A26
PANEL = (19, 30, 44, 0xE6)          # #131E2C
PANEL_SOLID = (19, 30, 44, 255)
PANEL_HI = (27, 42, 60, 0xF0)
PANEL_HI2 = (30, 50, 66, 0xF2)
LINE = (34, 54, 74, 255)            # #22364A
LINE_HI = (53, 81, 107, 255)        # #35516B
TEAL = (53, 230, 210, 255)          # #35E6D2
TEAL_DIM = (53, 230, 210, 0x80)
CYAN = (111, 232, 255, 255)         # #6FE8FF
TEXT = (234, 242, 248, 255)         # #EAF2F8
MUTED = (126, 147, 168, 255)        # #7E93A8
RED = (255, 74, 60, 255)            # #FF4A3C
GREEN = (62, 232, 140, 255)         # #3EE88C
AMBER = (232, 185, 60, 255)         # #E8B93C
SHADOW = (8, 13, 20, 255)
INSET = (10, 16, 23, 0xF2)          # 输入框内部
BLANK = (0, 0, 0, 0)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "client", "src", "main", "resources",
                   "resourcepacks", "bf_ui")
TP = os.path.join(OUT, "assets", "minecraft", "textures", "gui")

PACK_MC = """{
  "pack": {
    "pack_format": 34,
    "description": "BREAKFRONT 破阵前线 · UI 主题"
  }
}
"""


# --------------------------------------------------------------------------- #
# 绘制原语（全部硬边）
# --------------------------------------------------------------------------- #
def canvas(w: int, h: int) -> Image.Image:
    return Image.new("RGBA", (w, h), BLANK)


def px(im, x, y, c):
    if 0 <= x < im.width and 0 <= y < im.height:
        im.putpixel((x, y), c)


def hline(im, x0, x1, y, c):
    for x in range(x0, x1 + 1):
        px(im, x, y, c)


def vline(im, x, y0, y1, c):
    for y in range(y0, y1 + 1):
        px(im, x, y, c)


def frame(im, x0, y0, x1, y1, c):
    hline(im, x0, x1, y0, c)
    hline(im, x0, x1, y1, c)
    vline(im, x0, y0, y1, c)
    vline(im, x1, y0, y1, c)


def fill(im, x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px(im, x, y, c)


def blend(dst, src):
    """把 src 以 alpha 叠到 dst 上（仅用于生成期合成，不参与运行时）。"""
    return Image.alpha_composite(dst, src)


def save(im: Image.Image, rel: str):
    p = os.path.join(TP, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    im.save(p, "PNG", optimize=True)


# --------------------------------------------------------------------------- #
# 九宫格/缩放元数据（从原版 1.21.1 客户端 jar 读出后内联）
# --------------------------------------------------------------------------- #
# 内联而不是每次从原版 jar 抄：这些只是切边数字（非受版权保护），内联后生成器
# **不依赖任何 Mojang 资产**，仓库克隆下来即可直接重跑（仓库要公开，原版贴图不入库）。
# 格式：rel -> (width, height, border)；border 为 int 表示四边同值。
MC_META: dict[str, tuple[int, int, int | dict]] = {
    "sprites/widget/button.png": (200, 20, 3),
    "sprites/widget/button_highlighted.png": (200, 20, 3),
    "sprites/widget/button_disabled.png": (200, 20, 1),
    "sprites/widget/slider.png": (200, 20, 1),
    "sprites/widget/slider_highlighted.png": (200, 20, 1),
    "sprites/widget/slider_handle.png": (8, 20, {"left": 2, "top": 2, "right": 2, "bottom": 3}),
    "sprites/widget/slider_handle_highlighted.png": (8, 20, {"left": 2, "top": 2, "right": 2, "bottom": 3}),
    "sprites/widget/text_field.png": (200, 20, 1),
    "sprites/widget/text_field_highlighted.png": (200, 20, 1),
    "sprites/widget/tab.png": (130, 24, {"left": 2, "top": 2, "right": 2, "bottom": 0}),
    "sprites/widget/tab_highlighted.png": (130, 24, {"left": 2, "top": 2, "right": 2, "bottom": 0}),
    "sprites/widget/tab_selected.png": (130, 24, {"left": 2, "top": 2, "right": 2, "bottom": 0}),
    "sprites/widget/tab_selected_highlighted.png": (130, 24, {"left": 2, "top": 2, "right": 2, "bottom": 0}),
    "sprites/widget/scroller.png": (6, 32, 1),
    "sprites/widget/scroller_background.png": (6, 32, 1),
    "sprites/popup/background.png": (236, 34, 6),
}


def mcmeta_text(rel: str) -> str:
    w, h, border = MC_META[rel]
    b = json.dumps(border) if isinstance(border, dict) else str(border)
    return ('{\n  "gui": {\n    "scaling": {\n      "type": "nine_slice",\n'
            f'      "width": {w},\n      "height": {h},\n      "border": {b}\n'
            '    }\n  }\n}\n')


def write_mcmeta(rel: str) -> bool:
    """有九宫格声明的贴图必须配套写 .mcmeta，否则缩放行为与设计不符。"""
    if rel not in MC_META:
        return False
    dst = os.path.join(TP, rel + ".mcmeta")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "w", encoding="utf-8") as f:
        f.write(mcmeta_text(rel))
    return True


# --------------------------------------------------------------------------- #
# 控件
# --------------------------------------------------------------------------- #
def button(body, line, top, accent, bottom=SHADOW):
    """200x20，border=3。左 3 列为保留区 → 可放 1px 品牌青线。"""
    im = canvas(200, 20)
    fill(im, 0, 0, 199, 19, body)
    frame(im, 0, 0, 199, 19, line)
    hline(im, 1, 198, 1, top)
    hline(im, 1, 198, 18, bottom)
    hline(im, 1, 198, 19, line)
    if accent is not None:
        vline(im, 1, 1, 18, accent)
    return im


def slider_body(highlighted: bool):
    """200x20，border=1。只画中间 2px 轨道，其余透明。"""
    im = canvas(200, 20)
    if highlighted:
        fill(im, 0, 9, 199, 10, (46, 110, 104, 255))
        hline(im, 0, 199, 8, (53, 230, 210, 0x99))
        hline(im, 0, 199, 11, (53, 230, 210, 0x66))
    else:
        fill(im, 0, 9, 199, 10, (35, 56, 76, 255))
        hline(im, 0, 199, 8, (24, 38, 53, 255))
        hline(im, 0, 199, 11, (24, 38, 53, 255))
    return im


def slider_handle(highlighted: bool):
    """8x20，border=l2 t2 r2 b3。竖条滑块。"""
    im = canvas(8, 20)
    col = TEAL if highlighted else (201, 220, 236, 255)
    fill(im, 2, 2, 5, 17, (10, 16, 23, 0xFF))
    fill(im, 2, 2, 4, 16, col)
    frame(im, 1, 1, 6, 18, LINE)
    hline(im, 2, 4, 2, tuple(min(255, int(c * 1.25)) for c in col[:3]) + (255,))
    return im


def text_field(highlighted: bool):
    """200x20，border=1。内凹输入框。"""
    im = canvas(200, 20)
    fill(im, 0, 0, 199, 19, INSET)
    frame(im, 0, 0, 199, 19, TEAL if highlighted else LINE)
    hline(im, 1, 198, 1, (6, 10, 15, 255))
    return im


def tab(selected: bool, highlighted: bool):
    """130x24，border=l2 t2 r2 b0（下边不保留 → 可画整条底边强调）。"""
    body = PANEL_HI2 if selected else (22, 34, 46, 0xE0)
    line = TEAL if selected else (LINE_HI if highlighted else LINE)
    im = canvas(130, 24)
    fill(im, 0, 0, 129, 23, body)
    frame(im, 0, 0, 129, 23, line)
    # 左上/右上各削 1px，做出 2042 那种切角感
    px(im, 0, 0, BLANK)
    px(im, 129, 0, BLANK)
    hline(im, 1, 128, 1, (58, 88, 114, 255) if not selected else (78, 128, 150, 255))
    if selected:
        hline(im, 0, 129, 22, TEAL)
        hline(im, 0, 129, 23, TEAL)
    return im


def checkbox(state: str):
    """20x20：空框 / 悬停 / 选中(含勾) / 选中悬停。"""
    hl = state.endswith("h")
    sel = state.startswith("s")
    line = CYAN if hl else ((139, 168, 190, 255) if not sel else TEAL)
    im = canvas(20, 20)
    fill(im, 3, 3, 16, 16, (10, 16, 23, 0xFF))
    frame(im, 3, 3, 16, 16, line)
    if sel:
        fill(im, 4, 4, 15, 15, (14, 38, 40, 0xFF))
        # 勾：两条硬边线段
        for i in range(4):
            px(im, 6 + i, 10 + i, TEAL)
        for i in range(6):
            px(im, 9 + i, 13 - i, TEAL)
        px(im, 6, 9, TEAL)
    return im


def cross_button(hl: bool):
    im = canvas(14, 14)
    c = (255, 120, 108, 255) if hl else RED
    fill(im, 4, 4, 9, 9, (10, 16, 23, 0xFF))
    frame(im, 4, 4, 9, 9, c)
    for i in range(4):
        px(im, 5 + i, 5 + i, c)
        px(im, 8 - i, 5 + i, c)
    return im


def page_arrow(forward: bool, hl: bool):
    im = canvas(23, 13)
    c = TEAL if hl else (201, 220, 236, 255)
    cy = 6
    for i in range(4):
        if forward:
            vline(im, 9 + i, cy - i - 1 if i else cy, cy + i + 1 if i else cy, c)
        else:
            vline(im, 13 - i, cy - i - 1 if i else cy, cy + i + 1 if i else cy, c)
    return im


def lock_button(locked: bool, hl: bool, disabled: bool = False):
    im = canvas(20, 20)
    if disabled:
        c = (70, 84, 96, 255)
    elif locked:
        c = AMBER if hl else (196, 156, 52, 255)
    else:
        c = GREEN if hl else (54, 190, 116, 255)
    # 锁体
    fill(im, 6, 9, 13, 15, (10, 16, 23, 0xFF))
    frame(im, 6, 9, 13, 15, c)
    # 锁梁
    vline(im, 7, 6, 8, c)
    vline(im, 12, 6, 8, c)
    hline(im, 7, 12, 5, c)
    if locked:
        fill(im, 9, 11, 10, 12, c)
    else:
        px(im, 7, 6, BLANK)
        px(im, 7, 7, BLANK)
    return im


def scroller(bg: bool):
    im = canvas(6, 32)
    if bg:
        fill(im, 0, 0, 5, 31, (16, 24, 34, 0xD0))
    else:
        fill(im, 1, 0, 4, 31, (53, 230, 210, 0xAA))
        vline(im, 1, 1, 30, (53, 230, 210, 0x60))
    return im


def slot_frame():
    im = canvas(80, 80)
    fill(im, 0, 0, 79, 79, (7, 11, 18, 0x66))
    frame(im, 0, 0, 79, 79, (34, 54, 74, 0xAA))
    return im


def popup_background():
    im = canvas(236, 34)
    fill(im, 0, 0, 235, 33, (13, 21, 31, 0xF2))
    frame(im, 0, 0, 235, 33, LINE)
    hline(im, 1, 234, 1, LINE_HI)
    hline(im, 1, 234, 32, SHADOW)
    return im


def tiled_background(kind: str):
    """16x16 平铺底 —— 做得极克制：只用手感接近的两种深色做细网点，
    避免平铺出明显的重复图案。"""
    im = canvas(16, 16)
    base = BG_DEEP if kind != "tab_header" else BG_UP
    fill(im, 0, 0, 15, 15, base)
    dot = tuple(min(255, c + 5) for c in base[:3]) + (255,)
    for y in range(0, 16, 4):
        for x in range(0, 16, 4):
            px(im, x, y, dot)
    return im


def separator(kind: str):
    """32x2 分隔线：上亮下淡的青线，横向会被拉伸成整条。"""
    im = canvas(32, 2)
    top = TEAL if kind != "footer" else (53, 230, 210, 0xB0)
    for x in range(32):
        # 两端渐隐，避免看到生硬的截断
        edge = 1.0
        if x < 6:
            edge = 0.35 + 0.65 * (x / 6.0)
        elif x > 25:
            edge = 0.35 + 0.65 * ((31 - x) / 6.0)
        px(im, x, 0, (top[0], top[1], top[2], int(top[3] * edge * 0.85)))
        px(im, x, 1, (top[0], top[1], top[2], int(top[3] * edge * 0.35)))
    return im


def panorama_overlay():
    im = canvas(1, 1)
    px(im, 0, 0, (5, 9, 15, 0xD8))
    return im


# --------------------------------------------------------------------------- #
# 组装
# --------------------------------------------------------------------------- #
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true")
    args = ap.parse_args()

    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(TP, exist_ok=True)
    with open(os.path.join(OUT, "pack.mcmeta"), "w", encoding="utf-8") as f:
        f.write(PACK_MC)

    made: list[tuple[str, Image.Image]] = []

    def emit(rel: str, im: Image.Image):
        save(im, rel)
        if not write_mcmeta(rel):
            # 没有九宫格声明的贴图（勾选框/图标/平铺底…）本就不需要 .mcmeta
            pass
        made.append((rel, im))

    # --- 按钮族 ---
    emit("sprites/widget/button.png",
         button(PANEL, LINE, LINE_HI, TEAL_DIM))
    emit("sprites/widget/button_highlighted.png",
         button(PANEL_HI2, TEAL, CYAN, TEAL))
    emit("sprites/widget/button_disabled.png",
         button((15, 22, 31, 0xC8), (30, 44, 58, 255), (34, 48, 62, 255), None))

    # --- 滑块 ---
    emit("sprites/widget/slider.png", slider_body(False))
    emit("sprites/widget/slider_highlighted.png", slider_body(True))
    emit("sprites/widget/slider_handle.png", slider_handle(False))
    emit("sprites/widget/slider_handle_highlighted.png", slider_handle(True))

    # --- 输入框 ---
    emit("sprites/widget/text_field.png", text_field(False))
    emit("sprites/widget/text_field_highlighted.png", text_field(True))

    # --- 标签页 ---
    emit("sprites/widget/tab.png", tab(False, False))
    emit("sprites/widget/tab_highlighted.png", tab(False, True))
    emit("sprites/widget/tab_selected.png", tab(True, False))
    emit("sprites/widget/tab_selected_highlighted.png", tab(True, True))

    # --- 勾选框 ---
    emit("sprites/widget/checkbox.png", checkbox("n"))
    emit("sprites/widget/checkbox_highlighted.png", checkbox("nh"))
    emit("sprites/widget/checkbox_selected.png", checkbox("s"))
    emit("sprites/widget/checkbox_selected_highlighted.png", checkbox("sh"))

    # --- 图标类按钮 ---
    emit("sprites/widget/cross_button.png", cross_button(False))
    emit("sprites/widget/cross_button_highlighted.png", cross_button(True))
    emit("sprites/widget/page_backward.png", page_arrow(False, False))
    emit("sprites/widget/page_backward_highlighted.png", page_arrow(False, True))
    emit("sprites/widget/page_forward.png", page_arrow(True, False))
    emit("sprites/widget/page_forward_highlighted.png", page_arrow(True, True))
    emit("sprites/widget/locked_button.png", lock_button(True, False))
    emit("sprites/widget/locked_button_highlighted.png", lock_button(True, True))
    emit("sprites/widget/locked_button_disabled.png", lock_button(True, False, True))
    emit("sprites/widget/unlocked_button.png", lock_button(False, False))
    emit("sprites/widget/unlocked_button_highlighted.png", lock_button(False, True))
    emit("sprites/widget/unlocked_button_disabled.png", lock_button(False, False, True))

    # --- 滚动条 / 槽位 / 弹窗 ---
    emit("sprites/widget/scroller.png", scroller(False))
    emit("sprites/widget/scroller_background.png", scroller(True))
    emit("sprites/widget/slot_frame.png", slot_frame())
    emit("sprites/popup/background.png", popup_background())

    # --- 平铺底 / 分隔线（无 mcmeta） ---
    for name in ("menu_background", "menu_list_background",
                 "inworld_menu_background", "inworld_menu_list_background"):
        emit(f"{name}.png", tiled_background(name))
    emit("tab_header_background.png", tiled_background("tab_header"))
    emit("header_separator.png", separator("header"))
    emit("inworld_header_separator.png", separator("header"))
    emit("inworld_footer_separator.png", separator("footer"))
    emit("title/background/panorama_overlay.png", panorama_overlay())

    print(f"[✓] 生成 {len(made)} 个贴图 → {os.path.relpath(OUT, ROOT)}")

    if args.preview:
        scale = 4
        pad = 10
        widths = max(im.width for _r, im in made)
        total_h = sum(im.height * scale + pad for _r, im in made) + pad
        sheet = Image.new("RGBA", (widths * scale + pad * 2, total_h),
                          (11, 15, 22, 255))
        y = pad
        for rel, im in made:
            big = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
            sheet.alpha_composite(big, (pad, y))
            y += im.height * scale + pad
        out = os.path.join(ROOT, "docs", "bf-ui-preview.png")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        sheet.save(out)
        print(f"[✓] 预览图 → {os.path.relpath(out, ROOT)}  ({sheet.width}x{sheet.height})")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
