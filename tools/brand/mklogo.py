# Generates web/static/logo-light.svg and logo-dark.svg: the Logo.svelte icon + the "kinframe" wordmark in
# Fraunces (wght 620, letter-spacing -0.02em) converted to outlines, so it renders without the font
# (Logto's sign-in page shows them as <img>).
#
#   uv run --with fonttools --with brotli tools/brand/mklogo.py \
#     web/static/fonts/fraunces-latin-wght-normal.woff2 web/static
import sys
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

font_path, out_dir = sys.argv[1], sys.argv[2]
f = TTFont(font_path)
f = instancer.instantiateVariableFont(f, {"wght": 620})
gs = f.getGlyphSet()
cmap = f.getBestCmap()
upm = f["head"].unitsPerEm
hmtx = f["hmtx"]

# Geometry in the icon's 64-unit space: icon 30px, text 20.8px, gap 10px (Logo.svelte).
scale_px = 64 / 30
size = 20.8 * scale_px
gap = 10 * scale_px
s = size / upm
x = 64 + gap
xh = f["OS/2"].sxHeight or 500
baseline = 32 + (xh * s) / 2 + 2  # optically centre the x-height on the icon
paths = []
for ch in "kinframe":
    g = cmap[ord(ch)]
    pen = SVGPathPen(gs, ntos=lambda v: (f"{v:.1f}".rstrip("0").rstrip(".") or "0"))
    tp = TransformPen(pen, (s, 0, 0, -s, x, baseline))
    gs[g].draw(tp)
    paths.append(pen.getCommands())
    x += hmtx[g][0] * s - 0.02 * size
width = round(x + 2)

def svg(primary, text):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} 64" width="{width}" height="64" role="img" aria-label="kinframe">
<rect x="4" y="8" width="56" height="48" rx="12" fill="{primary}"/>
<rect x="11" y="15" width="42" height="34" rx="6" fill="#fbf1e6"/>
<circle cx="41" cy="26" r="5" fill="#e7a23a"/>
<path d="M11 43 23 31l9 9 6-6 15 15H17a6 6 0 0 1-6-6z" fill="#4f7a5a"/>
<path fill="{text}" d="{' '.join(paths)}"/>
</svg>
'''

open(f"{out_dir}/logo-light.svg", "w").write(svg("#a7471e", "#2a221d"))
open(f"{out_dir}/logo-dark.svg", "w").write(svg("#f09a6d", "#f3ebe3"))
print("width", width)
