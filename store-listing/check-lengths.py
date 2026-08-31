"""
Checks the Play listing text in listing-text.md against Play's character limits.

    python store-listing/check-lengths.py

Exits non-zero if anything is over, so it can be wired into a pre-publish check.
Play counts characters including newlines, which is what len() gives us here.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "listing-text.md")

# In document order: the first four fenced blocks are the four listing fields.
FIELDS = [
    ("App name", 30),
    ("Short description", 80),
    ("Full description", 4000),
    ("Release notes", 500),
]

def main() -> int:
    text = open(SRC, encoding="utf-8").read()
    blocks = re.findall(r"```\n(.*?)\n```", text, re.S)
    if len(blocks) < len(FIELDS):
        print(f"Expected at least {len(FIELDS)} fenced blocks, found {len(blocks)}.")
        return 2

    worst = 0
    for (label, limit), body in zip(FIELDS, blocks):
        used = len(body)
        over = used > limit
        worst = max(worst, int(over))
        flag = "OVER" if over else "ok  "
        print(f"{flag}  {label:<20} {used:>5} / {limit:<5} ({limit - used:+d})")

    # A hard-wrapped paragraph re-wraps again on a phone and looks ragged, so
    # flag long blocks whose lines are suspiciously uniform in length.
    full = blocks[2]
    prose = [l for l in full.split("\n") if l and not l.startswith("•") and l.strip()]
    wrapped = [l for l in prose if 60 <= len(l) <= 90]
    if len(wrapped) > len(prose) * 0.6 and len(prose) > 4:
        print("\nwarning: the full description looks hard-wrapped. Play preserves "
              "line breaks, so paragraphs should be single long lines.")

    print("\n" + ("SOMETHING IS OVER THE LIMIT" if worst else "All fields within limits."))
    return 1 if worst else 0

if __name__ == "__main__":
    sys.exit(main())
