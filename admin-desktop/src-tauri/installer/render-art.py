"""Render the NSIS installer bitmaps from the moon fish artwork.

Run from admin-desktop/:  python src-tauri/installer/render-art.py
Needs Pillow, Microsoft YaHei (Windows) and the Tauri CLI, which rasterizes the SVG sources.
The bitmaps are drawn at 150% of the MUI sizes (164x314 and 150x57); NSIS fits them to the
DPI-scaled controls, so they stay sharp on 150% displays and are scaled down at 100%.
"""
import math
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = Path(__file__).resolve().parent
ICONS = HERE.parent / "icons"
FONT = "C:/Windows/Fonts/msyhbd.ttc"
FONT_LIGHT = "C:/Windows/Fonts/msyh.ttc"
TOP, BOTTOM = (99, 153, 236), (61, 112, 203)  # the app icon's water


def rasterize(svg: Path, work: Path) -> Image.Image:
    out = work / svg.stem
    subprocess.run(["npx.cmd", "tauri", "icon", str(svg), "-o", str(out)], check=True,
                   cwd=HERE.parent.parent, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return Image.open(out / "icon.png").convert("RGBA")


def gradient(size, top, bottom) -> Image.Image:
    w, h = size
    image = Image.new("RGB", size)
    pixels = image.load()
    for y in range(h):
        for x in range(w):
            t = min(1.0, max(0.0, (y / h) * 0.85 + (x / w) * 0.15))
            pixels[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(top, bottom))
    return image.convert("RGBA")


def centered(draw, y, text, font, fill, width):
    box = draw.textbbox((0, 0), text, font=font)
    draw.text(((width - (box[2] - box[0])) / 2 - box[0], y), text, font=font, fill=fill)


def sidebar(fish: Image.Image) -> Image.Image:
    w, h = 246, 471
    image = gradient((w, h), TOP, BOTTOM)
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((-30, 30, 276, 300), fill=(255, 255, 255, 20))
    image.alpha_composite(glow.filter(ImageFilter.GaussianBlur(40)))
    waves = Image.new("RGBA", (w * 4, h * 4), (0, 0, 0, 0))
    draw = ImageDraw.Draw(waves)
    for row, alpha in ((372, 70), (404, 44), (436, 26)):
        points = []
        for x in range(0, w * 4 + 1, 8):
            points.append((x, (row + 7 * math.sin(x / (w * 4) * math.tau * 2.2 + row)) * 4))
        draw.line(points, fill=(255, 255, 255, alpha), width=10, joint="curve")
    image.alpha_composite(waves.resize((w, h), Image.LANCZOS))
    mark = fish.resize((196, 196), Image.LANCZOS)
    image.alpha_composite(mark, ((w - 196) // 2 + 6, 70))
    draw = ImageDraw.Draw(image)
    centered(draw, 268, "梦鱼更新管理", ImageFont.truetype(FONT, 30), (255, 255, 255, 255), w)
    centered(draw, 314, "DreamingFish 管理端桌面版", ImageFont.truetype(FONT_LIGHT, 15), (255, 255, 255, 205), w)
    return image


def header(mark: Image.Image) -> Image.Image:
    w, h = 225, 86
    image = Image.new("RGBA", (w, h), (255, 255, 255, 255))
    icon = mark.resize((60, 60), Image.LANCZOS)
    image.alpha_composite(icon, (24, (h - 60) // 2))
    draw = ImageDraw.Draw(image)
    draw.text((96, 19), "梦鱼", font=ImageFont.truetype(FONT, 20), fill=(52, 98, 186, 255))
    draw.text((96, 46), "DreamingFish", font=ImageFont.truetype(FONT_LIGHT, 13), fill=(91, 105, 113, 255))
    return image


def main():
    with tempfile.TemporaryDirectory() as temp:
        work = Path(temp)
        fish = rasterize(HERE / "fish.svg", work)
        mark = rasterize(ICONS / "icon.svg", work)
    sidebar(fish).convert("RGB").save(HERE / "sidebar.bmp")
    header(mark).convert("RGB").save(HERE / "header.bmp")


if __name__ == "__main__":
    main()
