#!/usr/bin/env python3
"""Draws the Marbingo "MB" launcher icon (adaptive layers + legacy) into app/src/main/res.

Run from anywhere:  python3 android/tools/generate-icons.py
Needs Pillow. Uses the bundled Bricolage Grotesque (bold) if present, else DejaVu Sans Bold.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

GREEN = (0x1E, 0x5B, 0x43, 255)
TINT = (0xE3, 0xEE, 0xE7, 255)
RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
BRICOLAGE = RES / "font/bricolage_grotesque.ttf"
DEJAVU = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
SS = 4  # supersampling

# density -> scale factor vs mdpi
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def load_font(px):
    if BRICOLAGE.exists():
        try:
            f = ImageFont.truetype(str(BRICOLAGE), px)
            try:
                axes = f.get_variation_axes()
                vals = []
                for a in axes:
                    name = a["name"].decode() if isinstance(a["name"], bytes) else a["name"]
                    if "eight" in name:
                        vals.append(min(a["maximum"], 800))
                    else:
                        vals.append(a["default"])
                f.set_variation_by_axes(vals)
            except Exception:
                pass
            return f
        except Exception:
            pass
    return ImageFont.truetype(DEJAVU, px)


def draw_mark(size, square_frac, tile_fill, letters, radius_frac=0.2):
    """Return an RGBA image. square_frac = tile side as a fraction of the canvas."""
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    side = s * square_frac
    x0 = (s - side) / 2
    box = (x0, x0, x0 + side, x0 + side)
    d.rounded_rectangle(box, radius=side * radius_frac, fill=tile_fill)
    font = load_font(int(side * 0.52))
    bbox = d.textbbox((0, 0), "MB", font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    pos = (s / 2 - tw / 2 - bbox[0], s / 2 - th / 2 - bbox[1])
    d.text(pos, "MB", font=font, fill=letters)
    return img.resize((size, size), Image.LANCZOS)


def mono_layer(size):
    """Alpha-only silhouette: tile filled, letters knocked out."""
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    side = s * 0.58
    x0 = (s - side) / 2
    d.rounded_rectangle((x0, x0, x0 + side, x0 + side), radius=side * 0.2, fill=(0, 0, 0, 255))
    font = load_font(int(side * 0.52))
    bbox = d.textbbox((0, 0), "MB", font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).text((s / 2 - tw / 2 - bbox[0], s / 2 - th / 2 - bbox[1]), "MB", font=font, fill=255)
    px = img.load()
    m = mask.load()
    for y in range(s):
        for x in range(s):
            if m[x, y]:
                r, g, b, a = px[x, y]
                px[x, y] = (r, g, b, max(0, a - m[x, y]))
    return img.resize((size, size), Image.LANCZOS)


def main():
    for name, k in DENSITIES.items():
        out = RES / f"mipmap-{name}"
        out.mkdir(parents=True, exist_ok=True)
        adaptive = int(round(108 * k))
        legacy = int(round(48 * k))
        # Adaptive foreground: tile inside the 66dp safe zone (about 61% of 108dp)
        draw_mark(adaptive, 0.58, GREEN, TINT).save(out / "ic_launcher_foreground.png")
        mono_layer(adaptive).save(out / "ic_launcher_monochrome.png")
        # Legacy icon: the tile fills the canvas
        draw_mark(legacy, 0.92, GREEN, TINT, radius_frac=0.22).save(out / "ic_launcher.png")
        draw_mark(legacy, 0.92, GREEN, TINT, radius_frac=0.5).save(out / "ic_launcher_round.png")
    print("icons written to", RES)


if __name__ == "__main__":
    main()
