# Builds the start-up branding that the convert tool puts on a frame (tools/convert/README.md, "Boot logo and
# animation"): the bootloader/kernel logo and the Android boot animation. Only our own brand assets go in.
#
#   uv run --no-project --with resvg-py==0.5.0 --with pillow==12.0.0 \
#     tools/brand/boot/build.py --brand kinframe --out web/static/frame-boot
#
# Writes <out>/<brand>/ with one boot logo per frame panel, one boot animation per screen size, a preview per screen
# size and manifest.json, which lists every file with its format and sha256 (one entry per line, so the convert
# script can read it without a JSON parser). The convert tools pick the logo whose format (size, depth, compression)
# and panel rotation match the frame's own logo exactly, and the animation made for the frame's screen size
# (`wm size`); without a match they refuse. The output is byte-for-byte reproducible (fixed zip timestamps, no PNG
# metadata, deterministic quantization), so a rebuild with the same versions only shows up in git when the design
# changes.
#
# Formats (docs/hardware/denver-pff-1024.md and denver-pff-1515.md, "Boot chain"):
# - logo-<w>x<h>-r<rotation>.bmp: what the Rockchip bootloader and kernel show from the resource partition. Windows
#   BMP in the panel's native scan-out orientation: Android rotates its picture by ro.sf.hwrotation, the bootloader
#   does not, so the landscape screen is stored turned back by that rotation (PFF-1024: 800x1280, rotation 270;
#   PFF-1515: 1920x1080, rotation 180, upside down). 8 bits per pixel with a 256-colour palette, BI_RLE8 compression
#   using only encoded runs, an end-of-line marker after every row and an end-of-bitmap marker, bottom-up rows.
# - bootanimation-<w>x<h>.zip: Android 6 boot animation for a <w>x<h> screen. Stored (uncompressed) zip with desc.txt
#   and one looping part, drawn centred on the part's background colour until the frame has booted. The frame is
#   smaller than the screen; it is made per screen size so the wordmark keeps the size and place it has in the logo.
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

# The frame panels we build for: the screen as Android draws it (landscape, what `wm size` reports) and how the panel
# is mounted (ro.sf.hwrotation). A new panel adds an entry; the 1280x800 one is the reference layout.
TARGETS = [
    {"screen": (1280, 800), "hwrotation": 270},  # Denver PFF-1024 (10.1")
    {"screen": (1920, 1080), "hwrotation": 180},  # Denver PFF-1515 (15.6")
]

# The reference layout (1280x800); other screens scale it by their height.
REF_H = 800
LOGO_W = 562  # the wordmark's width on screen (2x its 281-unit viewBox)
# The animation frame: centred on the screen like the boot logo, so the wordmark doesn't move between the two.
ANIM_W, ANIM_H = 720, 336
ANIM_FPS = 20
ANIM_FRAMES = 24  # 1.2 s loop
DOTS, DOT_R, DOT_GAP, DOT_DY = 3, 6, 30, 56

# Turns the landscape screen into the panel's scan-out for a given ro.sf.hwrotation (PIL's ROTATE_* turn
# counter-clockwise): Android rotates by hwrotation, so the stored picture is turned back by it.
PANEL_TURN = {0: None, 90: Image.Transpose.ROTATE_270, 180: Image.Transpose.ROTATE_180, 270: Image.Transpose.ROTATE_90}


class Layout:
    """Sizes for one screen: the reference layout scaled by the screen height."""

    def __init__(self, screen):
        self.w, self.h = screen
        s = self.h / REF_H
        self.scale = s
        self.logo_w = round(LOGO_W * s)
        self.anim_w = 2 * round(ANIM_W * s / 2)
        self.anim_h = 2 * round(ANIM_H * s / 2)
        self.dot_r, self.dot_gap, self.dot_dy = DOT_R * s, DOT_GAP * s, round(DOT_DY * s)


def rgb(hex_colour):
    h = hex_colour.lstrip("#")
    return tuple(int(h[i : i + 2], 16) for i in (0, 2, 4))


def wordmark(path, width):
    png = resvg_py.svg_to_bytes(svg_path=str(path), width=width)
    return Image.open(io.BytesIO(bytes(png))).convert("RGBA")


def screen(brand, mark, lay):
    """The landscape start-up screen: the wordmark centred on the brand background."""
    img = Image.new("RGB", (lay.w, lay.h), rgb(brand["background"]))
    img.paste(mark, ((lay.w - mark.width) // 2, (lay.h - mark.height) // 2), mark)
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


def boot_logo(land, hwrotation):
    """The panel's picture of the landscape screen `land`, as an RLE8 BMP."""
    turn = PANEL_TURN[hwrotation]
    panel = land.transpose(turn) if turn is not None else land
    pal = panel.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    return bmp_rle8(pal), panel.size


# ---------------------------------------------------------------- boot animation


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def anim_frame(brand, mark, lay, i):
    """One frame: the wordmark where the boot logo has it, and three dots pulsing in turn below it."""
    s = 4  # supersampling for smooth dots
    bg, accent, dim = rgb(brand["background"]), rgb(brand["accent"]), rgb(brand["dim"])
    img = Image.new("RGB", (lay.anim_w * s, lay.anim_h * s), bg)
    d = ImageDraw.Draw(img)
    cy = (lay.anim_h // 2 + mark.height // 2 + lay.dot_dy) * s
    cx0 = lay.anim_w // 2 - lay.dot_gap * (DOTS - 1) / 2
    for k in range(DOTS):
        phase = (i / ANIM_FRAMES - k / (DOTS * 2)) % 1.0
        glow = max(0.0, math.cos(2 * math.pi * phase)) ** 2
        r = (lay.dot_r * (0.85 + 0.15 * glow)) * s
        cx = (cx0 + k * lay.dot_gap) * s
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=mix(dim, accent, glow))
    img = img.resize((lay.anim_w, lay.anim_h), Image.Resampling.LANCZOS)
    # The wordmark at exactly its boot-logo position (the frame is centred on the screen).
    ox = (lay.w - lay.anim_w) // 2
    oy = (lay.h - lay.anim_h) // 2
    img.paste(mark, ((lay.w - mark.width) // 2 - ox, (lay.h - mark.height) // 2 - oy), mark)
    return img


def png_bytes(img):
    buf = io.BytesIO()
    img.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).save(buf, "PNG", optimize=True)
    return buf.getvalue()


def boot_animation(brand, mark, lay):
    desc = f"{lay.anim_w} {lay.anim_h} {ANIM_FPS}\np 0 0 part0 {brand['background'].upper()}\n"
    files = [("desc.txt", desc.encode()), ("part0/", b"")]
    files += [(f"part0/{i:03d}.png", png_bytes(anim_frame(brand, mark, lay, i))) for i in range(ANIM_FRAMES)]
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
        for name, data in files:
            zi = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_STORED
            zi.create_system = 3
            zi.external_attr = ((0o40755 if name.endswith("/") else 0o100644) << 16) | (0x10 if name.endswith("/") else 0)
            z.writestr(zi, data)
    return buf.getvalue()


def entry(fields):
    """One manifest entry on one line."""
    return json.dumps(fields, separators=(", ", ": "))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--brand", default="kinframe", choices=sorted(BRANDS))
    ap.add_argument("--out", default=str(ROOT / "web/static/frame-boot"))
    args = ap.parse_args()
    brand = BRANDS[args.brand]

    out = Path(args.out) / args.brand
    out.mkdir(parents=True, exist_ok=True)
    for old in list(out.glob("logo*.bmp")) + list(out.glob("bootanimation*.zip")) + list(out.glob("preview*.png")):
        old.unlink()
    logos, anims, done = [], [], set()
    for t in TARGETS:
        lay = Layout(t["screen"])
        mark = wordmark(brand["logo_svg"], lay.logo_w)
        land = screen(brand, mark, lay)
        logo, (pw, ph) = boot_logo(land, t["hwrotation"])
        name = f"logo-{pw}x{ph}-r{t['hwrotation']}.bmp"
        (out / name).write_bytes(logo)
        logos.append({"file": name, "width": pw, "height": ph, "bpp": 8, "compression": 1, "hwrotation": t["hwrotation"], "size": len(logo), "sha256": hashlib.sha256(logo).hexdigest()})
        size = f"{lay.w}x{lay.h}"
        if size in done:
            continue
        done.add(size)
        anim = boot_animation(brand, mark, lay)
        name = f"bootanimation-{size}.zip"
        (out / name).write_bytes(anim)
        anims.append({"file": name, "screen": size, "frame": f"{lay.anim_w}x{lay.anim_h}", "fps": ANIM_FPS, "size": len(anim), "sha256": hashlib.sha256(anim).hexdigest()})
        prev = io.BytesIO()
        land.resize((lay.w // 2, lay.h // 2), Image.Resampling.LANCZOS).save(prev, "PNG", optimize=True)
        (out / f"preview-{size}.png").write_bytes(prev.getvalue())
    manifest = (
        "{\n"
        + f'  "brand": {json.dumps(args.brand)},\n'
        + '  "logos": [\n'
        + ",\n".join(f"    {entry(e)}" for e in logos)
        + "\n  ],\n"
        + '  "bootanimations": [\n'
        + ",\n".join(f"    {entry(e)}" for e in anims)
        + "\n  ]\n}\n"
    )
    json.loads(manifest)  # well-formed
    (out / "manifest.json").write_text(manifest)
    print(manifest, end="")


if __name__ == "__main__":
    main()
