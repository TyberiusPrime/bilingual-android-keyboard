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

English contractions are **reconstructed**, because the corpus throws them away:
OpenSubtitles tokenises on the apostrophe, so `don't` arrives as `don` plus a
separate `'t` and never reaches the list as one word. Both halves survive, so
the mass can be put back — see `contractions()`. German gets none, its spelling
dictionary having no apostrophe words at all.

Possessives are deliberately not shipped. The dictionary holds 29,467 of them
against a few dozen contractions and cannot tell `it's` from `aardvark's`; the
difference that matters is that `dont` is a typo while `cats` is a word.

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

# The same corpus, untruncated. Only ever read for a handful of tokens — the
# apostrophe suffixes, which the 50k list drops — but there is no smaller source
# for them, and reconstructing the contractions is worth one cached download.
FREQUENCY_FULL = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/{lang}/{lang}_full.txt"

# Suffixes whose apostrophe form is a *contraction* rather than a possessive.
#
# The distinction matters because the spelling dictionary cannot make it: it
# holds `it's` and `aardvark's` as the same kind of thing, 29,467 of the latter
# and a few dozen of the former. Shipping every possessive would nearly double
# the English list for forms the typist can already produce by typing the
# apostrophe, while a contraction is the case where the apostrophe-less spelling
# is unambiguously a typo — `dont` is not a word.
CONTRACTION_SUFFIXES = ("'t", "'ve", "'d", "'ll", "'re", "'m")

# `'s` is both, and only the stem tells them apart. These are the closed word
# classes — pronouns and interrogatives — where `'s` contracts *is* or *has*
# rather than marking possession. A list of English pronouns is a linguistic
# fact rather than a judgement call; the frequencies still come from the corpus.
CONTRACTION_S_STEMS = frozenset(
    """he she it that there here this what who where when why how let one
    everybody everyone everything nobody nothing somebody someone something
    anybody anyone anything""".split()
)

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

    `über` folds together with `uber`, `Straße` with `strasse`. **This must agree
    with `Folding.fold` in the app** — the wordlists are written in folded order
    and the app binary-searches them without re-sorting.

    The two are no longer the same implementation. This one decomposes and
    strips combining marks, which is the general answer; the app uses a table of
    the fifteen non-ASCII characters these two wordlists actually contain, which
    is thirteen times faster and enough. They agree on every word in both files,
    and `WordlistAssetTest` checks that on the real data rather than on trust.
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


def wanted_contraction(word: str) -> str | None:
    """The apostrophe suffix of [word], if it is a contraction worth shipping."""
    stem, sep, rest = word.partition("'")
    if not sep:
        return None
    suffix = "'" + rest
    if suffix in CONTRACTION_SUFFIXES:
        return suffix
    if suffix == "'s" and stem.lower() in CONTRACTION_S_STEMS:
        return suffix
    return None


def contractions(
    words: list[str], counts: dict[str, int], full: dict[str, int], casing: dict[str, str]
) -> tuple[list[tuple[str, int]], dict[str, int]]:
    """Reconstruct contraction frequencies the corpus threw away.

    OpenSubtitles tokenises on the apostrophe, so `don't` never appears: the
    corpus has `don` and a separate `'t`. That is why the shipped list has no
    contractions at all, and why `don` sits there with 4.16 million occurrences
    — six tenths of a percent of the corpus, for a verb nobody uses. Almost all
    of that count is `don't` filed under the wrong key.

    The mass is recoverable because both halves survive. For each apostrophe
    suffix, its total (`'t`, 9.6 million) is divided among the stems that can
    take it, in proportion to how often each stem appears:

        count(X'Y) = total(Y) x count(X) / sum of count over stems of Y

    Exact where the stem is not a word on its own — `didn`, `isn`, `wouldn`
    occur only as contraction stems, so they take their whole count with them.
    An estimate where the stem is also a word: `can` is both a modal verb and
    the front of `can't`, and nothing here can separate them. The numbers come
    out plausible — `I'm` 0.60% of the corpus, `don't` 0.45%, `can't` 0.41% —
    which is the most that can be said for them.

    The allocation is then **subtracted from the stem**, because it was never
    the stem's to begin with. That is what drops `don` to something a verb might
    plausibly score, and it is also where the estimate's error propagates: an
    over-allocated `can't` leaves `can` correspondingly light.

    Returns the new entries, and the counts to subtract from stems.
    """
    # One casing per contraction, by the same rule as everything else: the
    # dictionary carries both `He's` and `he's`, and shipping both would be two
    # entries competing for one slot in the strip.
    forms: dict[str, list[str]] = {}
    for word in words:
        if wanted_contraction(word) is not None:
            forms.setdefault(word.lower(), []).append(word)

    by_suffix: dict[str, list[tuple[str, str]]] = {}
    for lowered, spellings in forms.items():
        stem, _, rest = lowered.partition("'")
        override = casing.get(stem)
        chosen = canonical(spellings, f"{override}'{rest}" if override else None)
        by_suffix.setdefault("'" + rest, []).append((chosen, stem))

    # Possessives are dropped from the output but not from the arithmetic: `'s`
    # attaches to every noun in the language, and dividing its total among the
    # two dozen pronouns alone would hand each of them a share of the corpus
    # that belongs to `aardvark's`.
    pools: dict[str, int] = {}
    for word in words:
        stem, sep, rest = word.partition("'")
        if sep:
            pools["'" + rest] = pools.get("'" + rest, 0) + counts.get(stem.lower(), 0)

    entries: list[tuple[str, int]] = []
    owed: dict[str, int] = {}
    for suffix, items in by_suffix.items():
        total = full.get(suffix, 0)
        pool = pools.get(suffix, 0)
        if not total or not pool:
            continue
        for word, stem in items:
            share = counts.get(stem, 0)
            if not share:
                continue
            count = round(total * share / pool)
            if count <= 0:
                continue
            entries.append((word, count))
            owed[stem] = owed.get(stem, 0) + count
    return entries, owed


def build(lang: str, cache: Path) -> None:
    spec = LANGUAGES[lang]
    deb = fetch(spec["deb"], cache)
    freq_file = fetch(FREQUENCY.format(lang=lang), cache)

    # Vocabulary, indexed by the lowercase form the frequency list is written in.
    by_lower: dict[str, list[str]] = {}
    for word in spelling_words(deb, spec["dict_path"], cache):
        by_lower.setdefault(word.lower(), []).append(word)

    counts = dict(frequencies(freq_file))

    # Contractions, reconstructed from the halves the tokeniser left behind, and
    # the mass each one takes back off its stem. Only worth the extra download
    # for a language whose dictionary has apostrophe words at all — German's has
    # none, so `geht's` stays a matter for D27's suggestion-side rule.
    words = spelling_words(deb, spec["dict_path"], cache)
    added: list[tuple[str, int]] = []
    owed: dict[str, int] = {}
    if any("'" in word for word in words):
        full = dict(frequencies(fetch(FREQUENCY_FULL.format(lang=lang), cache)))
        added, owed = contractions(words, counts, full, spec["casing"])

    entries: list[tuple[str, str, int]] = []  # folded, word, count
    for token, count in counts.items():
        folded = fold(token)
        if not ALLOWED.match(folded):
            continue
        forms = by_lower.get(token)
        if not forms:
            continue
        # What the contractions took is not this word's to keep. Never to zero:
        # the token did occur, and a word that drops out of the list entirely
        # stops being suggestible at all.
        count = max(1, count - owed.get(token, 0))
        entries.append((folded, canonical(forms, spec["casing"].get(token)), count))

    for word, count in added:
        entries.append((fold(word), word, count))

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
        if added:
            handle.write(
                f"# {len(added)} contractions reconstructed from {lang}_full "
                "apostrophe tokens; see build-wordlists.py\n"
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
