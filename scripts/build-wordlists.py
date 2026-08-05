#!/usr/bin/env python3
"""Build the German and English wordlists the keyboard ships.

Two kinds of source, neither of which is enough on its own:

  * **igerman98** (Debian `wngerman`) and **SCOWL** (Debian `wamerican`) supply
    the vocabulary — every inflected form, spelled out — and its
    capitalisation. German nouns are capitalised, so a keyboard that offers
    `haus` is wrong.
  * **hermitdave/FrequencyWords** (OpenSubtitles 2018) supplies how common each
    word is, which is what ranks the suggestions. Its tokens are lowercased,
    which is why the spelling lists have to supply the case.

A word is kept when it appears in both: the spelling list decides what is a
word and how it is spelled, the frequency list decides the order. That
intersection also throws out the misspellings, names and foreign words a
subtitle corpus is full of.

**One casing per word**, because two entries differing only in case would eat
two of the strip's three slots to say the same thing. Where a spelling list
offers several — `in`, `In`, `IN` — the fewest capitals wins, which keeps
`nicht` over `Nicht` and leaves `Haus` alone because no lowercase `haus`
exists. The keyboard puts the first letter back into the case the typist used,
so a shifted `Zei` still produces `Zeit`.

The known cost: German homographs where the flat list kept the lowercase
reading — `zeit`, `leben`, `weg`, `recht` — are offered lowercase unless the
typist presses shift. Telling those apart needs a part-of-speech signal that
the GPL-compatible sources do not carry, and guessing it wrong would
capitalise adjectives.

Contractions do not survive either: OpenSubtitles tokenisation splits `don't`
into `don` and `t`, so it never reaches the frequency list as one word. D6
expects correction to place apostrophes eventually, which is a later problem
than this one.

Licences are in app/src/main/assets/wordlists/PROVENANCE.md; all of them are
compatible with this project's GPLv3.

Needs python3, curl, ar and tar. Run from anywhere:

    scripts/build-wordlists.py [--cache DIR]

Output is deterministic — no timestamps — so re-running it against unchanged
sources produces no diff.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import unicodedata
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT_DIR = REPO / "app/src/main/assets/wordlists"

FREQUENCY = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/{lang}/{lang}_50k.txt"

LANGUAGES = {
    "de": {
        "name": "German",
        "deb": "https://deb.debian.org/debian/pool/main/i/igerman98/wngerman_20161207-16_all.deb",
        "dict_path": "usr/share/dict/ngerman",
        "spelling": "igerman98, via Debian wngerman 20161207-16 (GPL-2+)",
        # Nothing to override: German has no one-letter words to get wrong.
        "casing": {},
    },
    "en": {
        "name": "English",
        "deb": "https://deb.debian.org/debian/pool/main/s/scowl/wamerican_2020.12.07-4_all.deb",
        "dict_path": "usr/share/dict/american-english",
        "spelling": "SCOWL, via Debian wamerican 2020.12.07-4 (SCOWL licence, permissive)",
        # The one word where fewest-capitals is wrong: English `i` is always `I`,
        # and SCOWL lists both because `i` is also a name for the letter.
        "casing": {"i": "I"},
    },
}

# After folding, a word may contain only these. Anything else — digits, dots,
# letters from a third language — is corpus noise rather than something this
# keyboard can type or suggest.
ALLOWED = re.compile(r"^[a-z][a-z'-]*$")


def fold(word: str) -> str:
    """Reduce a word to the letters someone types when they skip the accents.

    `über` folds together with `uber`, `Straße` with `strasse`. **This must stay
    identical to `Folding.fold` in the app** — the wordlists are written in
    folded order and the app binary-searches them without re-sorting.
    `WordlistAssetTest` checks that the two agree, on the real files.
    """
    lowered = word.lower().replace("ß", "ss")
    decomposed = unicodedata.normalize("NFD", lowered)
    return "".join(c for c in decomposed if not unicodedata.combining(c))


def fetch(url: str, into: Path) -> Path:
    target = into / url.rsplit("/", 1)[-1]
    if not target.exists():
        print(f"fetching {url}", file=sys.stderr)
        subprocess.run(["curl", "-sSL", "-o", str(target), url], check=True)
    return target


def spelling_words(deb: Path, dict_path: str, work: Path) -> list[str]:
    """The word list out of a Debian dictionary package."""
    unpacked = work / deb.stem
    extracted = unpacked / dict_path
    if not extracted.exists():
        unpacked.mkdir(parents=True, exist_ok=True)
        subprocess.run(["ar", "x", str(deb.resolve())], cwd=unpacked, check=True)
        data = next(unpacked.glob("data.tar*"))
        subprocess.run(["tar", "xf", data.name], cwd=unpacked, check=True)
    return extracted.read_text(encoding="utf-8").split()


def frequencies(path: Path) -> list[tuple[str, int]]:
    counts = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].isdigit():
            counts.append((parts[0], int(parts[1])))
    return counts


def canonical(forms: list[str], override: str | None) -> str:
    """The single casing to ship for a word."""
    if override:
        return override
    return min(forms, key=lambda form: (sum(c.isupper() for c in form), form))


def build(lang: str, cache: Path) -> None:
    spec = LANGUAGES[lang]
    deb = fetch(spec["deb"], cache)
    freq_file = fetch(FREQUENCY.format(lang=lang), cache)

    # Vocabulary, indexed by the lowercase form the frequency list is written in.
    by_lower: dict[str, list[str]] = {}
    for word in spelling_words(deb, spec["dict_path"], cache):
        by_lower.setdefault(word.lower(), []).append(word)

    entries: list[tuple[str, str, int]] = []  # folded, word, count
    for token, count in frequencies(freq_file):
        folded = fold(token)
        if not ALLOWED.match(folded):
            continue
        forms = by_lower.get(token)
        if not forms:
            continue
        entries.append((folded, canonical(forms, spec["casing"].get(token)), count))

    # Folded order is what the app relies on. Ties — `über` and `uber` would be
    # one, if both were words — go to the more common word first.
    entries.sort(key=lambda entry: (entry[0], -entry[2], entry[1]))

    total = sum(count for _, _, count in entries)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / f"{lang}.txt"
    with out.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(f"# Bilingual Keyboard wordlist — {spec['name']}\n")
        handle.write(f"# Vocabulary and casing: {spec['spelling']}\n")
        handle.write(
            "# Frequencies: hermitdave/FrequencyWords 2018 "
            f"{lang}_50k, from OpenSubtitles 2018 (CC BY-SA 4.0)\n"
        )
        handle.write("# Built by scripts/build-wordlists.py; see PROVENANCE.md\n")
        handle.write(f"# {len(entries)} entries, {total} corpus occurrences\n")
        handle.write("# Sorted by folded form. One entry per line: word<TAB>count\n")
        for _, word, count in entries:
            handle.write(f"{word}\t{count}\n")

    print(f"{out.relative_to(REPO)}: {len(entries)} entries", file=sys.stderr)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--cache",
        type=Path,
        default=Path("/tmp/bikeyboard-wordlists"),
        help="where downloaded sources are kept between runs",
    )
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    for lang in LANGUAGES:
        build(lang, args.cache)


if __name__ == "__main__":
    main()
