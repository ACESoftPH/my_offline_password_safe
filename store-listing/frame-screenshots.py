"""
Frames the raw device captures in `screenshots-raw/` into the 1080x1920 images
Play accepts, writing them to `screenshots-phone/`.

    python store-listing/frame-screenshots.py

Why this exists: a modern phone capture is 1080x2400, i.e. 20:9, which is taller
than the 9:16 maximum Play documents for phone screenshots. Rather than cropping
-- which would cut off the bottom navigation bar or the app bar -- each capture
is scaled to fit the 1920px height and centred on a dark vertical gradient. The
whole screen stays visible and the aspect ratio is unambiguously within spec.

This step used to be done by hand, which meant the framed set could silently
drift from the raw set. Running this makes them reproducible from the captures.

Capturing the raw images is a manual step (a real device or emulator), because
`FLAG_SECURE` is on by default and makes every capture come out black -- turn
"Block screenshots" off in the app's settings first. See README.md.
"""
import glob
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "screenshots-raw")
OUT = os.path.join(HERE, "screenshots-phone")

TARGET = (1080, 1920)
TOP = (35, 35, 38)      # gradient at y=0
BOTTOM = (11, 11, 13)   # gradient at the last row

# Play accepts at most 8 phone screenshots, and there are 9 captures. These are
# kept in screenshots-raw/ as the record of the app, but held back from the
# upload set. Numbering stays aligned with the raw files rather than being
# closed up, so a raw capture and its framed version always share a name; Play
# orders by upload, not by filename, so the gap is harmless.
#
# 06 is the one held back: the backup screen is two masked passphrase fields,
# the least informative of the set to look at, and the capability is already
# described in the full listing text.
EXCLUDE = {"06-encrypted-backup.png"}


def gradient(size, top, bottom):
    w, h = size
    img = Image.new("RGB", (1, h))
    px = img.load()
    for y in range(h):
        t = y / (h - 1)
        px[0, y] = tuple(round(a + (b - a) * t) for a, b in zip(top, bottom))
    return img.resize((w, h), Image.NEAREST)


def frame(path):
    src = Image.open(path).convert("RGB")
    tw, th = TARGET
    scale = th / src.height
    new = (round(src.width * scale), th)
    if new[0] > tw:                      # unusually wide capture: fit width instead
        scale = tw / src.width
        new = (tw, round(src.height * scale))
    scaled = src.resize(new, Image.LANCZOS)
    canvas = gradient(TARGET, TOP, BOTTOM)
    canvas.paste(scaled, ((tw - new[0]) // 2, (th - new[1]) // 2))
    return canvas


def main():
    raws = sorted(glob.glob(os.path.join(RAW, "*.png")))
    if not raws:
        sys.exit(f"no captures in {RAW}")
    os.makedirs(OUT, exist_ok=True)
    written = 0
    for p in raws:
        name = os.path.basename(p)
        out = os.path.join(OUT, name)
        if name in EXCLUDE:
            if os.path.exists(out):          # a previous run may have written it
                os.remove(out)
            print(f"{name:34} -- held back (Play allows 8)")
            continue
        img = frame(p)
        # 24-bit, no alpha -- Play rejects alpha in screenshots
        img.save(out, "PNG", optimize=True)
        written += 1
        print(f"{name:34} -> {img.size} {img.mode}")

    rel = os.path.relpath(OUT, os.path.dirname(HERE))
    print(f"\n{written} screenshots written to {rel}")
    if written > 8:
        sys.exit(f"ERROR: {written} screenshots, but Play accepts at most 8. Add one to EXCLUDE.")


if __name__ == "__main__":
    main()
