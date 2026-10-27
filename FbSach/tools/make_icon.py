"""Vẽ icon launcher cho FB Sạch: nền xanh đậm bo góc, chữ "f" trắng, khiên xanh lá có dấu tích."""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def draw(size):
    s = 4 * size  # vẽ to rồi thu nhỏ cho mịn
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * 0.22), fill=(31, 58, 95, 255))

    font = ImageFont.truetype(FONT, int(s * 0.78))
    d.text((s * 0.40, s * 0.56), "f", font=font, fill="white", anchor="mm")

    # Khiên góc dưới phải
    cx, cy, w = s * 0.72, s * 0.70, s * 0.36
    shield = [
        (cx - w / 2, cy - w * 0.48),
        (cx + w / 2, cy - w * 0.48),
        (cx + w / 2, cy + w * 0.02),
        (cx, cy + w * 0.55),
        (cx - w / 2, cy + w * 0.02),
    ]
    d.polygon(shield, fill=(46, 160, 67, 255), outline="white")
    d.line(shield + [shield[0]], fill="white", width=max(2, int(s * 0.025)))
    d.line(
        [(cx - w * 0.22, cy - w * 0.02), (cx - w * 0.04, cy + w * 0.17), (cx + w * 0.25, cy - w * 0.22)],
        fill="white",
        width=max(3, int(s * 0.045)),
        joint="curve",
    )
    return img.resize((size, size), Image.LANCZOS)


def main(res_dir):
    for density, px in SIZES.items():
        out = os.path.join(res_dir, f"mipmap-{density}")
        os.makedirs(out, exist_ok=True)
        draw(px).save(os.path.join(out, "ic_launcher.png"), optimize=True)


if __name__ == "__main__":
    main(sys.argv[1])
