#!/usr/bin/env python3
"""生成控糖表盘应用图标：自适应图标前景 PNG + 传统 mipmap。

设计：深色底 + 白色圆环刻度 + 绿色血糖折线（末端圆点代表当前值）。
运行：python3 tools/gen_icon.py
"""
import os
import math
from PIL import Image, ImageDraw

BG = (28, 28, 30)
FG = (242, 242, 242)
ACCENT = (76, 175, 80)

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
DP = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
SS = 4  # 超采样倍数


def draw_face(size, scale=1.0):
    """在透明画布上画表盘，坐标以 108dp 为基准。"""
    return _draw_canvas(size * SS, scale).resize((size, size), Image.LANCZOS)


def _draw_canvas(n, scale=1.0):
    """直接按 n 像素画布绘制（调用方负责超采样）。"""
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = n / 108.0 * scale
    cx = cy = n / 2.0

    def pt(angle_deg, radius):
        a = math.radians(angle_deg - 90)
        return (cx + radius * u * math.cos(a), cy + radius * u * math.sin(a))

    # 圆环
    r = 30
    d.ellipse([cx - r * u, cy - r * u, cx + r * u, cy + r * u],
              outline=FG, width=int(round(2.4 * u)))

    # 12/3/6/9 四个刻度
    for angle in (0, 90, 180, 270):
        x1, y1 = pt(angle, r)
        x2, y2 = pt(angle, r - 5)
        d.line([(x1, y1), (x2, y2)], fill=FG, width=int(round(2.4 * u)))

    # 绿色血糖折线（末端圆点 = 当前值）
    pts = [(34, 62), (43, 54), (53, 59), (62, 48), (71, 54)]
    line_pts = [(cx + (x - 54) * u, cy + (y - 54) * u) for x, y in pts]
    width = max(1, int(round(5.2 * u)))
    d.line(line_pts, fill=ACCENT, width=width)
    for x, y in line_pts[:-1]:
        rad = width / 2.0
        d.ellipse([x - rad, y - rad, x + rad, y + rad], fill=ACCENT)
    ex, ey = line_pts[-1]
    d.ellipse([ex - 5 * u, ey - 5 * u, ex + 5 * u, ey + 5 * u], fill=ACCENT)
    return img


def rounded_mask(n, radius):
    """兼容老版本 Pillow 的圆角矩形遮罩。"""
    m = Image.new("L", (n, n), 0)
    d = ImageDraw.Draw(m)
    d.rectangle([radius, 0, n - radius, n], fill=255)
    d.rectangle([0, radius, n, n - radius], fill=255)
    r2 = radius * 2
    d.ellipse([0, 0, r2, r2], fill=255)
    d.ellipse([n - r2, 0, n, r2], fill=255)
    d.ellipse([0, n - r2, r2, n], fill=255)
    d.ellipse([n - r2, n - r2, n, n], fill=255)
    return m


def full_bleed(size, round_mask):
    """传统图标：圆角方块/圆形深色底 + 缩放后的表盘。"""
    n = size * SS
    bg = Image.new("RGBA", (n, n), BG + (0,))
    if round_mask:
        mask = Image.new("L", (n, n), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, n - 1, n - 1], fill=255)
    else:
        mask = rounded_mask(n, int(n * 0.18))
    bg.putalpha(mask)
    # 在超采样画布上直接绘制，避免小图贴到左上角
    face = _draw_canvas(n, scale=0.84)
    bg.alpha_composite(face)
    return bg.resize((size, size), Image.LANCZOS)


def main():
    for dpi, mult in DP.items():
        folder = os.path.join(RES, "mipmap-" + dpi)
        os.makedirs(folder, exist_ok=True)
        size = int(round(108 * mult))
        draw_face(size).save(os.path.join(folder, "ic_launcher_foreground.png"))
        full_bleed(size, True).save(os.path.join(folder, "ic_launcher_round.png"))
        full_bleed(size, False).save(os.path.join(folder, "ic_launcher.png"))
        print("mipmap-%s: %dpx" % (dpi, size))


if __name__ == "__main__":
    main()
