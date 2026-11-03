# Builds the start-up branding that the convert tool puts on a frame (tools/convert/README.md, "Boot logo and
# animation"): the bootloader/kernel logo and the Android boot animation. Only our own brand assets go in.
#
#   uv run --no-project --with resvg-py==0.5.0 --with pillow==12.0.0 \
#     tools/brand/boot/build.py --brand kinframe --out web/static/frame-boot
#
# Writes <out>/<brand>/logo.bmp, bootanimation.zip, preview.png and manifest.json. The output is byte-for-byte
# reproducible (fixed zip timestamps, no PNG metadata, deterministic quantization), so a rebuild with the same
# versions only shows up in git when the design changes.
#
# Formats (docs/hardware/denver-pff-1024.md, "Boot chain"):
# - logo.bmp: what the Rockchip bootloader and kernel show from the resource partition. Windows BMP, 800×1280
#   (the panel's native portrait orientation; the frame shows it rotated to 1280×800 landscape), 8 bits per pixel
#   with a 256-colour palette, BI_RLE8 compression using only encoded runs, an end-of-line marker after every row and
#   an end-of-bitmap marker, bottom-up rows. The convert tool checks the frame's own logo has the same size, depth
#   and compression before it replaces it.
# - bootanimation.zip: Android 6 boot animation. Stored (uncompressed) zip with desc.txt and one looping part, drawn
#   centred on the part's background colour until the frame has booted.
#
# SPDX-License-Identifier: AGPL-3.0-or-later
import argparse
import hashlib
import io
import json
import math
import re
import struct
import zipfile
from pathlib import Path

import resvg_py
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[3]

# A brand: its wordmark SVG (light-on-dark variant) and its colours. A retail brand adds an entry here.
BRANDS = {
    "kinframe": {
        "logo_svg": ROOT / "web/static/logo-dark.svg",
        "background": "#191512",  # web/src/app.css dark --bg
        "accent": "#f09a6d",  # dark --primary
        "dim": "#3a322b",  # dark --border
    },
}

# Screen as Android draws it (landscape) and the panel's native scan-out (portrait).
SCREEN_W, SCREEN_H = 1280, 800
LOGO_W = 562  # the wordmark's width on screen (2× its 281-unit viewBox)
# The animation frame: centred on the screen like the boot logo, so the wordmark doesn't move between the two.
ANIM_W, ANIM_H = 720, 336
ANIM_FPS = 20
ANIM_FRAMES = 24  # 1.2 s loop
DOTS, DOT_R, DOT_GAP = 3, 6, 30


def rgb(hex_colour):
    h = hex_colour.lstrip("#")
    return tuple(int(h[i : i + 2], 16) for i in (0, 2, 4))


def wordmark(path, width):
    png = resvg_py.svg_to_bytes(svg_path=str(path), width=width)
    return Image.open(io.BytesIO(bytes(png))).convert("RGBA")


def screen(brand, mark):
    """The landscape start-up screen: the wordmark centred on the brand background."""
    img = Image.new("RGB", (SCREEN_W, SCREEN_H), rgb(brand["background"]))
    img.paste(mark, ((SCREEN_W - mark.width) // 2, (SCREEN_H - mark.height) // 2), mark)
    return img


# ---------------------------------------------------------------- BMP (8-bit, RLE8)


def rle8_rows(pixels, width, height):
    """BI_RLE8 with encoded runs only (count 1..255, value), bottom-up, EOL per row, then EOB."""
    out = bytearray()
    for y in range(height - 1, -1, -1):
        row = pixels[y * width : (y + 1) * width]
        x = 0
        while x < width:
            v = row[x]
            n = 1
            while x + n < width and n < 255 and row[x + n] == v:
                n += 1
            out += bytes((n, v))
            x += n
        out += b"\x00\x00"  # end of line, after every row (the last one too)
    out += b"\x00\x01"  # end of bitmap
    return bytes(out)


def bmp_rle8(img):
    """An 8-bit palettized BI_RLE8 BMP of a 'P' image with up to 256 colours."""
    w, h = img.size
    pal = img.getpalette()[: 256 * 3]
    pal += [0] * (256 * 3 - len(pal))
    palette = b"".join(bytes((pal[i + 2], pal[i + 1], pal[i], 0)) for i in range(0, 256 * 3, 3))
    data = rle8_rows(img.tobytes(), w, h)
    off = 14 + 40 + len(palette)
    file_header = struct.pack("<2sIHHI", b"BM", off + len(data), 0, 0, off)
    # 11808 px/m = 300 dpi; 256 colours used, all important.
    info = struct.pack("<IiiHHIIiiII", 40, w, h, 1, 8, 1, len(data), 11808, 11808, 256, 256)
    return file_header + info + palette + data


def boot_logo(brand, mark):
    land = screen(brand, mark)
    portrait = land.transpose(Image.Transpose.ROTATE_90)  # counter-clockwise: the panel is mounted rotated
    assert portrait.size == (SCREEN_H, SCREEN_W)
    pal = portrait.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    return bmp_rle8(pal), land


# ---------------------------------------------------------------- boot animation


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def anim_frame(brand, mark, i):
    """One frame: the wordmark where the boot logo has it, and three dots pulsing in turn below it."""
    s = 4  # supersampling for smooth dots
    bg, accent, dim = rgb(brand["background"]), rgb(brand["accent"]), rgb(brand["dim"])
    img = Image.new("RGB", (ANIM_W * s, ANIM_H * s), bg)
    d = ImageDraw.Draw(img)
    cy = (ANIM_H // 2 + mark.height // 2 + 56) * s
    cx0 = ANIM_W // 2 - DOT_GAP * (DOTS - 1) / 2
    for k in range(DOTS):
        phase = (i / ANIM_FRAMES - k / (DOTS * 2)) % 1.0
        glow = max(0.0, math.cos(2 * math.pi * phase)) ** 2
        r = (DOT_R * (0.85 + 0.15 * glow)) * s
        cx = (cx0 + k * DOT_GAP) * s
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=mix(dim, accent, glow))
    img = img.resize((ANIM_W, ANIM_H), Image.Resampling.LANCZOS)
    # The wordmark at exactly its boot-logo position (the frame is centred on the screen).
    ox = (SCREEN_W - ANIM_W) // 2
    oy = (SCREEN_H - ANIM_H) // 2
    img.paste(mark, ((SCREEN_W - mark.width) // 2 - ox, (SCREEN_H - mark.height) // 2 - oy), mark)
    return img


def png_bytes(img):
    buf = io.BytesIO()
    img.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).save(buf, "PNG", optimize=True)
    return buf.getvalue()


def boot_animation(brand, mark):
    desc = f"{ANIM_W} {ANIM_H} {ANIM_FPS}\np 0 0 part0 {brand['background'].upper()}\n"
    files = [("desc.txt", desc.encode()), ("part0/", b"")]
    files += [(f"part0/{i:03d}.png", png_bytes(anim_frame(brand, mark, i))) for i in range(ANIM_FRAMES)]
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
        for name, data in files:
            zi = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_STORED
            zi.create_system = 3
            zi.external_attr = ((0o40755 if name.endswith("/") else 0o100644) << 16) | (0x10 if name.endswith("/") else 0)
            z.writestr(zi, data)
    return buf.getvalue()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--brand", default="kinframe", choices=sorted(BRANDS))
    ap.add_argument("--out", default=str(ROOT / "web/static/frame-boot"))
    args = ap.parse_args()
    brand = BRANDS[args.brand]
    mark = wordmark(brand["logo_svg"], LOGO_W)

    out = Path(args.out) / args.brand
    out.mkdir(parents=True, exist_ok=True)
    logo, land = boot_logo(brand, mark)
    anim = boot_animation(brand, mark)
    (out / "logo.bmp").write_bytes(logo)
    (out / "bootanimation.zip").write_bytes(anim)
    prev = io.BytesIO()
    land.resize((SCREEN_W // 2, SCREEN_H // 2), Image.Resampling.LANCZOS).save(prev, "PNG", optimize=True)
    (out / "preview.png").write_bytes(prev.getvalue())
    manifest = {
        "brand": args.brand,
        "logo": {"file": "logo.bmp", "size": len(logo), "sha256": hashlib.sha256(logo).hexdigest(), "width": SCREEN_H, "height": SCREEN_W, "bpp": 8, "compression": "rle8"},
        "bootanimation": {"file": "bootanimation.zip", "size": len(anim), "sha256": hashlib.sha256(anim).hexdigest(), "frame": [ANIM_W, ANIM_H], "fps": ANIM_FPS},
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
