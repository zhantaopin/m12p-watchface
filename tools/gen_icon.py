#!/usr/bin/env python3
"""生成表盘应用图标：自适应图标前景 PNG + 传统 mipmap。

设计：深色底 + 白色表盘圆环 + 时针分针 + 3 点位置绿色血糖点。
运行：python3 tools/gen_icon.py
"""
import os
from PIL import Image, ImageDraw

BG = (28, 28, 30)
FG = (242, 242, 242)
ACCENT = (76, 175, 80)

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
DP = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
SS = 4  # 超采样倍数


def draw_face(size, scale=1.0):
    """在透明画布上画表盘，坐标以 108dp 为基准。"""
    n = size * SS
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = n / 108.0 * scale
    cx = cy = n / 2.0

    def pt(angle_deg, radius):
        import math
        a = math.radians(angle_deg - 90)
        return (cx + radius * u * math.cos(a), cy + radius * u * math.sin(a))

    # 表盘圆环
    r, w = 27, 6.5
    d.ellipse([cx - r * u, cy - r * u, cx + r * u, cy + r * u],
              outline=FG, width=int(round(w * u)))

    # 时针（指向 4 点）与分针（指向 12 点）
    def hand(angle, length):
        x, y = pt(angle, length)
        d.line([(cx, cy), (x, y)], fill=FG, width=int(round(5.5 * u)))
        d.ellipse([x - 2.75 * u, y - 2.75 * u, x + 2.75 * u, y + 2.75 * u], fill=FG)

    hand(120, 15)
    hand(0, 20)

    # 中心轴
    d.ellipse([cx - 3.2 * u, cy - 3.2 * u, cx + 3.2 * u, cy + 3.2 * u], fill=FG)

    # 3 点位置的绿色血糖点（压在圆环上）
    x, y = pt(90, 27)
    rad = 5.6 * u
    d.ellipse([x - rad, y - rad, x + rad, y + rad], fill=ACCENT)
    return img.resize((size, size), Image.LANCZOS)


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
    face = draw_face(size, scale=0.84)
    bg.alpha_composite(face.convert("RGBA"))
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
