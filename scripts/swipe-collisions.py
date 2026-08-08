#!/usr/bin/env python3
"""How much of a language is invisible to a swipe.

A swipe is a shape. Two words whose shapes are *the same shape* cannot be told
apart by any decoder, however good — only by how common they are. This counts
them, on the shipped wordlists, for QWERTY and for Dvorak.

A word's shape is the polyline through the key centres of its letters, reduced
to canonical form: consecutive repeats collapse (the finger does not move for
the second `l` of `hello`), and a vertex lying *on* the line between its
neighbours disappears (going w-i-p along the top row is a straight line, so the
`i` leaves no trace). Two words collide when their reduced polylines are equal.

Two kinds of collision, counted apart because they have different causes:

  spelling   the same folded spelling — `song`/`Song`, `wurde`/`würde`.
             Case and accents, which a finger cannot express either.
  shape      different spellings, one shape — `das`/`dass`, `swiping`/`sweeping`.
             This is the geometric one, and the one a layout can change.

The number that matters is the last one printed: the share of words a perfect
decoder must still get wrong, because it can only ever answer a collision with
whichever member is commoner.
"""

import sys
from collections import defaultdict
from pathlib import Path

FOLD = {
    'ä': 'a', 'à': 'a', 'á': 'a', 'â': 'a', 'ã': 'a', 'å': 'a',
    'ë': 'e', 'è': 'e', 'é': 'e', 'ê': 'e',
    'ï': 'i', 'ì': 'i', 'í': 'i', 'î': 'i',
    'ö': 'o', 'ò': 'o', 'ó': 'o', 'ô': 'o', 'õ': 'o', 'ø': 'o',
    'ü': 'u', 'ù': 'u', 'ú': 'u', 'û': 'u',
    'ý': 'y', 'ÿ': 'y', 'ç': 'c', 'č': 'c', 'ć': 'c', 'ñ': 'n',
    'ł': 'l', 'š': 's', 'ś': 's', 'ž': 'z', 'ź': 'z', 'ż': 'z', 'ß': 's',
}

QWERTY = ("qwertyuiop", "asdfghjkl", "zxcvbnm")
DVORAK = ("pyfgcrl", "aoeuidhtns", "qjkxbmwvz")

# A phone key: one unit wide, one and a half tall, as on the real keyboard.
KEY_H = 1.5
EPSILON = 1e-6


def centres(rows):
    """Uniform key width, each row centred — so only the letters differ."""
    widest = max(len(r) for r in rows)
    out = {}
    for y, row in enumerate(rows):
        left = (widest - len(row)) / 2.0
        for x, ch in enumerate(row):
            out[ch] = (left + x + 0.5, y * KEY_H)
    return out


def fold(word):
    return ''.join(FOLD.get(c, c) for c in word.lower())


def on_segment(a, b, c):
    """Whether b sits on the line from a to c, between them."""
    abx, aby = b[0] - a[0], b[1] - a[1]
    acx, acy = c[0] - a[0], c[1] - a[1]
    if abs(abx * acy - aby * acx) > EPSILON:
        return False
    dot = abx * acx + aby * acy
    return -EPSILON <= dot <= acx * acx + acy * acy + EPSILON


def shape(word, keys):
    pts = []
    for ch in fold(word):
        p = keys.get(ch)
        if p is None:
            continue
        if pts and pts[-1] == p:
            continue
        pts.append(p)
    if len(pts) < 2:
        return None
    changing = True
    while changing and len(pts) > 2:
        changing = False
        out = [pts[0]]
        for i in range(1, len(pts) - 1):
            if on_segment(out[-1], pts[i], pts[i + 1]):
                changing = True
                continue
            out.append(pts[i])
        out.append(pts[-1])
        pts = out
    return tuple(pts)


def load(path):
    words = []
    for line in path.open(encoding='utf-8'):
        if not line.strip() or line.startswith('#'):
            continue
        parts = line.rstrip('\n').split('\t')
        if len(parts) == 2:
            words.append((parts[0], int(parts[1])))
    return words


def analyse(name, rows, entries):
    keys = centres(rows)
    total = sum(c for _, c in entries)
    unswipeable = sum(c for w, c in entries if shape(w, keys) is None)

    by_shape = defaultdict(list)
    for word, count in entries:
        s = shape(word, keys)
        if s is not None:
            by_shape[s].append((word, count))

    spelling_mass = shape_mass = 0
    spelling_err = shape_err = 0
    groups = 0
    worst = []

    for members in by_shape.values():
        if len(members) < 2:
            continue
        # Split off pure case/accent variants: same folded spelling.
        folded = defaultdict(int)
        for word, count in members:
            folded[fold(word)] += count
        mass = sum(c for _, c in members)
        best = max(c for _, c in members)

        if len(folded) == 1:
            spelling_mass += mass
            spelling_err += mass - best
        else:
            groups += 1
            shape_mass += mass
            shape_err += mass - best
            worst.append((mass - best, sorted(members, key=lambda m: -m[1])))

    pct = lambda n: 100.0 * n / total
    print(f"\n=== {name} ===")
    print(f"  words {len(entries)}, corpus mass {total:,}")
    print(f"  unswipeable (one key)            {pct(unswipeable):6.2f}% of typing")
    print(f"  collides on case/accent only     {pct(spelling_mass):6.2f}%   "
          f"cost {pct(spelling_err):5.2f}%")
    print(f"  collides on SHAPE ({groups} groups)  {pct(shape_mass):6.2f}%   "
          f"cost {pct(shape_err):5.2f}%")
    print(f"  --> irreducible swipe error      {pct(shape_err + spelling_err):6.2f}%")

    worst.sort(key=lambda w: -w[0])
    print("  worst shape collisions:")
    for cost, members in worst[:8]:
        shown = ", ".join(f"{w}({c:,})" for w, c in members[:4])
        print(f"     {pct(cost):5.3f}%  {shown}")
    return pct(shape_err + spelling_err)


def main():
    here = Path(__file__).resolve().parent.parent / "app/src/main/assets/wordlists"
    lists = {"de": load(here / "de.txt"), "en": load(here / "en.txt")}
    for lang, entries in lists.items():
        for name, rows in (("QWERTY", QWERTY), ("Dvorak", DVORAK)):
            analyse(f"{lang}  {name}", rows, entries)


if __name__ == "__main__":
    main()
