"""
Regenerates the Play listing icon and feature graphic from the artwork the app
actually ships (app/src/main/res/drawable/ic_launcher_foreground.xml), so the
store assets can never drift away from the launcher icon.

    python store-listing/generate.py

Screenshots are NOT generated here - they are real device captures, see README.md.
Requires Pillow and a Roboto TTF (adjust FONT below if your path differs).
"""
import math, re, sys
from PIL import Image, ImageDraw, ImageChops

def arc_points(x0, y0, rx, ry, phi_deg, large, sweep, x1, y1, steps=64):
    """SVG endpoint -> centre parameterisation (W3C implementation notes F.6.5)."""
    if rx == 0 or ry == 0 or (x0 == x1 and y0 == y1):
        return [(x1, y1)]
    phi = math.radians(phi_deg)
    cosp, sinp = math.cos(phi), math.sin(phi)
    dx2, dy2 = (x0 - x1) / 2.0, (y0 - y1) / 2.0
    x1p = cosp * dx2 + sinp * dy2
    y1p = -sinp * dx2 + cosp * dy2
    rx, ry = abs(rx), abs(ry)
    lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if lam > 1:
        s = math.sqrt(lam)
        rx, ry = rx * s, ry * s
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        co = -co
    cxp = co * rx * y1p / ry
    cyp = -co * ry * x1p / rx
    cx = cosp * cxp - sinp * cyp + (x0 + x1) / 2.0
    cy = sinp * cxp + cosp * cyp + (y0 + y1) / 2.0

    def ang(ux, uy, vx, vy):
        d = math.hypot(ux, uy) * math.hypot(vx, vy)
        if d == 0:
            return 0.0
        c = max(-1.0, min(1.0, (ux * vx + uy * vy) / d))
        a = math.acos(c)
        return -a if (ux * vy - uy * vx) < 0 else a

    theta1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dtheta = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dtheta > 0:
        dtheta -= 2 * math.pi
    elif sweep and dtheta < 0:
        dtheta += 2 * math.pi

    pts = []
    for i in range(1, steps + 1):
        t = theta1 + dtheta * i / steps
        px = cosp * rx * math.cos(t) - sinp * ry * math.sin(t) + cx
        py = sinp * rx * math.cos(t) + cosp * ry * math.sin(t) + cy
        pts.append((px, py))
    return pts

TOKEN = re.compile(r"[MLAZmlaz]|-?\d*\.?\d+(?:e-?\d+)?")

def parse_subpaths(data):
    toks = TOKEN.findall(data)
    i = 0
    subpaths, cur = [], []
    x = y = sx = sy = 0.0
    while i < len(toks):
        t = toks[i]
        if t in "Mm":
            if len(cur) > 2:
                subpaths.append(cur)
            x, y = float(toks[i + 1]), float(toks[i + 2])
            sx, sy = x, y
            cur = [(x, y)]
            i += 3
        elif t in "Ll":
            x, y = float(toks[i + 1]), float(toks[i + 2])
            cur.append((x, y))
            i += 3
        elif t in "Aa":
            rx, ry, rot = float(toks[i+1]), float(toks[i+2]), float(toks[i+3])
            la, sw = int(float(toks[i+4])), int(float(toks[i+5]))
            nx, ny = float(toks[i+6]), float(toks[i+7])
            cur.extend(arc_points(x, y, rx, ry, rot, la, sw, nx, ny))
            x, y = nx, ny
            i += 8
        elif t in "Zz":
            cur.append((sx, sy))
            x, y = sx, sy
            i += 1
        else:
            i += 1
    if len(cur) > 2:
        subpaths.append(cur)
    return subpaths



import os
from PIL import Image, ImageDraw, ImageFont, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = HERE
os.makedirs(OUT, exist_ok=True)
VD = r"D:\Documents\My_Projects\my_offline_password_safe\app\src\main\res\drawable\ic_launcher_foreground.xml"
FONT = "C:/Program Files/Android/Android Studio/plugins/design-tools/resources/layoutlib/data/fonts/RobotoFlex-Regular.ttf"

def font(size, weight="Regular"):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_name(weight)
    except Exception:
        pass
    return f

ARTWORK = (0xFF, 0x7A, 0x1A)   # brand orange, matching ic_launcher_foreground.xml

# ---------------------------------------------------------------- background
# Mirrors ic_launcher_background.xml: linear gradient across the 108 viewport.
STOPS = [(0.00, (0x23, 0x23, 0x26)), (0.55, (0x14, 0x14, 0x16)), (1.00, (0x08, 0x08, 0x0A))]

def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))

def grad_at(t):
    t = max(0.0, min(1.0, t))
    for i in range(len(STOPS) - 1):
        t0, c0 = STOPS[i]
        t1, c1 = STOPS[i + 1]
        if t0 <= t <= t1:
            return lerp(c0, c1, (t - t0) / (t1 - t0))
    return STOPS[-1][1]

def diagonal_gradient(w, h):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            px[x, y] = grad_at((x / w + y / h) / 2)
    return img

# ---------------------------------------------------------------- artwork mask
def artwork_mask(size, viewport=108.0):
    """1-bit mask of the foreground path, even-odd = XOR of subpath masks."""
    data = re.search(r'android:pathData="([^"]+)"', open(VD, encoding="utf-8").read(), re.S).group(1)
    k = size / viewport
    acc = Image.new("1", (size, size), 0)
    for sp in parse_subpaths(data):
        m = Image.new("1", (size, size), 0)
        ImageDraw.Draw(m).polygon([(x * k, y * k) for x, y in sp], fill=1)
        acc = ImageChops.logical_xor(acc, m)
    return acc

def icon(out_px=512, crop_units=78.0):
    """Render the 108 viewport, then crop the centre `crop_units` and scale.

    A launcher only ever shows the central ~72 of the 108dp viewport, so cropping
    makes the store icon match what people see on their home screen instead of
    floating in a field of padding.
    """
    big = int(round(out_px * 108.0 / crop_units))
    bg = diagonal_gradient(big, big)
    fg = Image.new("RGB", (big, big), ARTWORK)
    bg.paste(fg, (0, 0), artwork_mask(big).convert("L"))
    off = int(round((big - out_px) / 2))
    return bg.crop((off, off, off + out_px, off + out_px))

# ---------------------------------------------------------------- feature graphic
def feature_graphic(w=1024, h=500):
    img = diagonal_gradient(w, h)
    d = ImageDraw.Draw(img)

    # faint concentric rings echoing the vault dial, bled off the right edge
    ring = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    rd = ImageDraw.Draw(ring)
    cx, cy = int(w * 0.845), int(h * 0.5)
    for r in range(300, 90, -46):
        rd.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(255, 122, 26, 26), width=3)
    img = Image.alpha_composite(img.convert("RGBA"), ring).convert("RGB")
    d = ImageDraw.Draw(img)

    # the app mark
    mark_px = 236
    mark_bg = Image.new("RGB", (mark_px, mark_px), ARTWORK)
    m = artwork_mask(int(mark_px * 108 / 78))
    off = (m.size[0] - mark_px) // 2
    m = m.crop((off, off, off + mark_px, off + mark_px))
    img.paste(mark_bg, (78, (h - mark_px) // 2), m.convert("L"))

    x = 78 + mark_px + 62
    d.text((x, 150), "Offline Password", font=font(62, "Bold"), fill=(255, 255, 255))
    d.text((x, 218), "Wallet", font=font(62, "Bold"), fill=(255, 255, 255))
    d.text((x, 300), "Encrypted on your device. No account,",
           font=font(30, "Regular"), fill=(186, 186, 192))
    d.text((x, 340), "no cloud, no internet permission.",
           font=font(30, "Regular"), fill=(186, 186, 192))
    return img

if __name__ == "__main__":
    icon(512, 84.0).convert("RGBA").save(os.path.join(OUT, "icon-512.png"))
    feature_graphic().convert("RGB").save(os.path.join(OUT, "feature-graphic-1024x500.png"))
    print("wrote:", sorted(os.listdir(OUT)))
