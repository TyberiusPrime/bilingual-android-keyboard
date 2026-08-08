#!/usr/bin/env python3
"""Build the German and English bigram stores the keyboard predicts from.

Stage 6a of D46: the measurable baseline the language model has to beat, and
the thing that fills the suggestion strip between words — blank today at every
word boundary and every word's first keystroke, because both shipped wordlists
are unigram and there is nothing to predict from.

**Counts, not text.** The corpus is streamed and never redistributed; what
ships is a table of how often one word follows another. That is the same
footing the shipped frequency counts stand on and it is the whole legal basis
for the artefact, so the rules that keep it a statistic rather than an index
are not tuning knobs:

  * the vocabulary is closed — only words already in the wordlists are counted,
    so no name, address or rare phrasing from the corpus can enter;
  * pairs seen fewer than `--threshold` times are dropped, so nothing that
    occurred once anywhere is recoverable;
  * order is discarded beyond the pair, so no span of three words survives.

See D46 and `PROVENANCE.md`. OPUS grants no licence and does not claim to; it
asks for a link to opensubtitles.org and a citation, both of which the output
header carries.

**Keys are wordlist indices, not strings.** A bigram entry says "word 12,043 is
followed by word 908", which is compact and is exactly what `Lexicon` already
binary-searches by. The consequence is that a store is only valid against the
wordlist it was built from, so the header records that wordlist's size and
checksum and the app refuses a mismatch rather than predicting nonsense.

**Sentence starts count.** The context after a full stop is a real one — it is
where the strip is emptiest — so a virtual token one past the end of the
vocabulary stands for "start of sentence" and takes its own row.

**An unknown word breaks the chain.** Where a token is not in the wordlist,
neither the pair before it nor the pair after it is counted. Bridging over it
would invent adjacencies the corpus does not contain, which is the one thing a
count table must not do.

Needs python3 and numpy. Run from anywhere:

    scripts/build-bigrams.py --corpus DIR [--threshold N] [--report]

`--report` sweeps the threshold and prints what each one costs in coverage and
in bytes, without writing anything. Output is deterministic.
"""

from __future__ import annotations

import argparse
import array
import gzip
import hashlib
import re
import struct
import sys
import unicodedata
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parent.parent
WORDLISTS = REPO / "app/src/main/assets/wordlists"

# Same shape as build-wordlists.py's ALLOWED, applied to the folded token. The
# apostrophe is in because the shipped English list carries `don't` as one
# entry; the hyphen because German compounds occasionally arrive with one.
ALLOWED = re.compile(r"^[a-z][a-z'-]*$")

# Everything that is not part of a word, mapped away before splitting. Cheaper
# than a regex findall over two billion tokens, and the result is the same
# because ALLOWED has the final say on every token anyway.
KEEP = set("abcdefghijklmnopqrstuvwxyz'-")
PUNCTUATION = {
    ord(c): " "
    for c in "\"“”„«»‚‘’`.,!?;:()[]{}<>/\\|@#$%^&*_+=~…°£€$0123456789"
}
# The typographic apostrophe is a word character in disguise; normalise rather
# than split on it, or every `don’t` in the corpus becomes `don` plus `t`.
PUNCTUATION[ord("’")] = "'"

# How many pair occurrences to hold before folding them down and spilling.
# Eighty million uint64 is 640MB, which leaves room on a 15GB machine for the
# vocabulary, the memo table and numpy's sort scratch.
CHUNK = 80_000_000

# Spill files, so the final group-by can be done one bucket at a time rather
# than holding every distinct pair in memory at once.
BUCKETS = 32

MAGIC = b"BKBG"
VERSION = 1


def fold(word: str) -> str:
    """Reduce a word to the letters someone types when they skip the accents.

    **Must agree with `Folding.fold` in the app and with `build-wordlists.py`**,
    which is what makes a token from the corpus find the right wordlist entry.
    Copied from the wordlist builder rather than imported, because these two
    scripts are meant to be readable one at a time.
    """
    lowered = word.lower().replace("ß", "ss")
    decomposed = unicodedata.normalize("NFD", lowered)
    return "".join(c for c in decomposed if not unicodedata.combining(c))


def load_vocabulary(path: Path) -> tuple[dict[str, int], int, str]:
    """The shipped wordlist, as a folded-form to line-index map.

    The index is the position among the entries, which is exactly what
    `Lexicon.wordAt` takes — the app never re-sorts, so line order is the
    contract between the two files.

    Where two entries share a folded form the first wins, which is what
    `Lexicon.indexOf` does with its lower bound.
    """
    index: dict[str, int] = {}
    position = 0
    digest = hashlib.sha256()
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            digest.update(line.encode("utf-8"))
            if line.startswith("#") or not line.strip():
                continue
            word = line.split("\t", 1)[0]
            index.setdefault(fold(word), position)
            position += 1
    return index, position, digest.hexdigest()


class Spill:
    """Partially counted pairs on disk, bucketed so the merge fits in memory.

    Each chunk of raw occurrences is sorted and folded down to (pair, count)
    before it is written, which is what keeps the spill a few gigabytes rather
    than one uint64 per token in the corpus.
    """

    def __init__(self, work: Path, buckets: int) -> None:
        self.work = work
        work.mkdir(parents=True, exist_ok=True)
        self.files = [(work / f"bucket-{i:02d}.bin").open("wb") for i in range(buckets)]
        self.buckets = buckets

    def add(self, pairs: np.ndarray) -> None:
        keys, counts = np.unique(pairs, return_counts=True)
        # Bucket on the low bits of the *first* word, so every occurrence of one
        # context lands in one file and can be summed without a global merge.
        which = (keys >> 32).astype(np.uint64) % np.uint64(self.buckets)
        for bucket in range(self.buckets):
            mask = which == np.uint64(bucket)
            if not mask.any():
                continue
            block = np.empty(mask.sum(), dtype=[("key", "<u8"), ("count", "<u4")])
            block["key"] = keys[mask]
            block["count"] = counts[mask]
            self.files[bucket].write(block.tobytes())

    def close(self) -> list[Path]:
        for handle in self.files:
            handle.close()
        return [self.work / f"bucket-{i:02d}.bin" for i in range(self.buckets)]


def count_pairs(corpus: Path, vocabulary: dict[str, int], words: int, work: Path) -> list[Path]:
    """Stream the corpus and count every in-vocabulary adjacent pair.

    The hot path is one dictionary lookup per token. Folding is expensive —
    a Unicode decomposition per call — so the result is memoised on the raw
    token, and since a subtitle corpus repeats itself relentlessly the fold is
    computed a couple of million times rather than two billion.

    `None` is memoised too. Most misses are names and numbers, and they miss
    once each.
    """
    start = words  # the virtual start-of-sentence context
    memo: dict[str, int | None] = {}
    buffer = array.array("Q")
    spill = Spill(work, BUCKETS)
    lines = 0
    tokens = 0
    pairs = 0

    with gzip.open(corpus, "rt", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            lines += 1
            previous = start
            for token in line.translate(PUNCTUATION).lower().split():
                found = memo.get(token, False)
                if found is False:
                    folded = fold(token)
                    found = vocabulary.get(folded) if ALLOWED.match(folded) else None
                    memo[token] = found
                tokens += 1
                if found is None:
                    # An unknown word breaks the chain rather than being bridged
                    # over; see the module docstring.
                    previous = None
                    continue
                if previous is not None:
                    buffer.append((previous << 32) | found)
                    pairs += 1
                previous = found

            if len(buffer) >= CHUNK:
                spill.add(np.frombuffer(buffer, dtype="<u8"))
                del buffer[:]

            if lines % 5_000_000 == 0:
                print(
                    f"  {lines:,} lines, {tokens:,} tokens, {pairs:,} pairs",
                    file=sys.stderr,
                    flush=True,
                )

    if buffer:
        spill.add(np.frombuffer(buffer, dtype="<u8"))
    print(
        f"  {lines:,} lines, {tokens:,} tokens, {pairs:,} in-vocabulary pairs",
        file=sys.stderr,
        flush=True,
    )
    return spill.close()


#: The lowest count the merge keeps at all. D46 forbids shipping singletons —
#: a pair seen once somewhere is the one thing that could carry a rare phrasing
#: out of the corpus — so they are counted for the report and then dropped,
#: which is also what keeps the merge inside memory on the English run.
MIN_KEEP = 2

#: The thresholds the report sweeps. Nothing below [MIN_KEEP] can be chosen.
SWEEP = (2, 3, 5, 8, 10, 15, 20, 30, 50, 100)


def gather(buckets: list[Path]) -> tuple[np.ndarray, np.ndarray, int, int]:
    """Sum the spilled partial counts into one sorted (pair, count) table.

    Bucket by bucket, because every occurrence of a given pair was routed to the
    same file and so can be summed without ever holding the whole table. That
    matters: English produces a few hundred million distinct pairs, and the
    singletons among them are both the bulk of the memory and the part D46 says
    must not ship. They are counted and dropped here rather than at the end.

    Returns the surviving table, plus how many singletons were seen and how many
    occurrences they accounted for, so the report can still describe them.
    """
    keys: list[np.ndarray] = []
    counts: list[np.ndarray] = []
    singletons = 0
    singleton_mass = 0
    for path in buckets:
        raw = np.fromfile(path, dtype=[("key", "<u8"), ("count", "<u4")])
        if raw.size == 0:
            continue
        order = np.argsort(raw["key"], kind="stable")
        key = raw["key"][order]
        count = raw["count"][order].astype(np.int64)
        del raw, order
        unique, first = np.unique(key, return_index=True)
        summed = np.add.reduceat(count, first)
        del key, count
        keep = summed >= MIN_KEEP
        singletons += int((~keep).sum())
        singleton_mass += int(summed[~keep].sum())
        keys.append(unique[keep])
        counts.append(summed[keep])
        del unique, summed, keep
    if not keys:
        return np.empty(0, dtype="<u8"), np.empty(0, dtype=np.int64), 0, 0
    key = np.concatenate(keys)
    count = np.concatenate(counts)
    del keys, counts
    order = np.argsort(key, kind="stable")
    return key[order], count[order], singletons, singleton_mass


def report(
    count: np.ndarray, words: int, singletons: int, singleton_mass: int
) -> None:
    """What each threshold costs, so the choice is measured rather than picked.

    **Coverage** is the share of the corpus's in-vocabulary pair occurrences that
    survive — how often the store will have anything to say. **Entries** is what
    it costs to say it, at four bytes each plus the offset table.

    The singleton row is printed for reference and cannot be chosen: it is what
    D46's threshold rule exists to exclude.
    """
    total = int(count.sum()) + singleton_mass
    print(f"\n  {'min':>5} {'entries':>14} {'coverage':>9} {'MB':>8}")
    print(
        f"  {1:>5} {len(count) + singletons:>14,} {100.0:>8.1f}% "
        f"{'—':>8}   (excluded by D46)"
    )
    for minimum in SWEEP:
        keep = count >= minimum
        entries = int(keep.sum())
        covered = int(count[keep].sum())
        print(
            f"  {minimum:>5} {entries:>14,} {covered / total:>8.1%} "
            f"{(entries * 4 + (words + 2) * 4) / 1e6:>8.1f}"
        )


def write(
    out: Path,
    key: np.ndarray,
    count: np.ndarray,
    words: int,
    checksum: str,
    threshold: int,
    language: str,
) -> None:
    """The store, as a row per context word plus one for the sentence start.

    Compressed-sparse-row: an offset table says where each context's followers
    begin, and the followers themselves are one 32-bit word each — 17 bits of
    wordlist index and 15 bits of quantised probability. Fixed width, because a
    common context has thousands of followers and the app binary-searches within
    the row rather than scanning it.

    The probability is `count(a, b) / count(a)` — conditional on the context,
    not a share of the corpus — held as a 15-bit quantised natural log with a
    floor at [LOG_FLOOR]. The app interpolates it with the unigram weight
    (D46), so a value here never has to stand on its own.
    """
    if words >= 1 << 17:
        raise SystemExit(f"{words} words will not fit in a 17-bit index")

    keep = count >= threshold
    key = key[keep]
    count = count[keep].astype(np.float64)

    first = (key >> np.uint64(32)).astype(np.int64)
    second = (key & np.uint64(0xFFFFFFFF)).astype(np.int64)

    rows = words + 1  # every word, plus the sentence start
    starts = np.searchsorted(first, np.arange(rows + 1, dtype=np.int64))

    # Conditional probability within the row. The denominator is the surviving
    # mass of that context rather than its true total, which is the honest
    # normalisation for a table that has had its tail cut off: what the store
    # says is "given that I have an opinion about what follows, here it is".
    #
    # By bincount rather than by reduceat over the row starts: a context with no
    # surviving followers repeats the previous row's start, and reduceat quietly
    # returns that row's first element for it instead of nothing.
    totals = np.bincount(first, weights=count, minlength=rows)
    probability = count / np.where(totals <= 0, 1.0, totals)[first]

    quantised = np.clip(
        np.round(np.log(probability) / LOG_FLOOR * QUANT_MAX), 0, QUANT_MAX
    ).astype(np.uint32)
    entries = (second.astype(np.uint32) << np.uint32(15)) | quantised

    header = struct.pack(
        "<4sHHIIQ32s",
        MAGIC,
        VERSION,
        threshold,
        words,
        len(entries),
        int(count.sum()),
        bytes.fromhex(checksum)[:32],
    )
    with out.open("wb") as handle:
        handle.write(header)
        handle.write(starts.astype("<u4").tobytes())
        handle.write(entries.astype("<u4").tobytes())

    print(
        f"{out.relative_to(REPO)}: {len(entries):,} entries for {language}, "
        f"{out.stat().st_size / 1e6:.1f}MB",
        file=sys.stderr,
    )


# A follower a thousand times less likely than certain is as unlikely as this
# table bothers to distinguish; below that the interpolation with the unigram
# weight dominates anyway.
LOG_FLOOR = -12.0
QUANT_MAX = (1 << 15) - 1


def build(language: str, corpus: Path, work: Path, threshold: int, only_report: bool) -> None:
    wordlist = WORDLISTS / f"{language}.txt"
    vocabulary, words, checksum = load_vocabulary(wordlist)
    print(f"{language}: {words:,} words from {wordlist.name}", file=sys.stderr)

    # Counting is the expensive half — an hour of streaming for English — and
    # the threshold is meant to be chosen by looking at the sweep it produces.
    # So the summed table is kept, and a second run at a different threshold
    # costs nothing. Keyed by the wordlist's checksum, because a table counted
    # against different indices is not a table, it is nonsense.
    cache = work / language / f"counts-{checksum[:16]}.npz"
    if cache.exists():
        print(f"  reusing {cache}", file=sys.stderr)
        held = np.load(cache)
        key, count = held["key"], held["count"]
        singletons, singleton_mass = int(held["singletons"]), int(held["mass"])
        buckets = []
    else:
        buckets = count_pairs(corpus, vocabulary, words, work / language)
        key, count, singletons, singleton_mass = gather(buckets)
        cache.parent.mkdir(parents=True, exist_ok=True)
        np.savez(
            cache, key=key, count=count,
            singletons=singletons, mass=singleton_mass,
        )
    print(f"  {len(key) + singletons:,} distinct pairs", file=sys.stderr)

    if only_report:
        report(count, words, singletons, singleton_mass)
    else:
        report(count, words, singletons, singleton_mass)
        write(
            WORDLISTS / f"{language}.bigrams",
            key,
            count,
            words,
            checksum,
            threshold,
            language,
        )

    for path in buckets:
        path.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--corpus",
        type=Path,
        required=True,
        help="directory holding de.txt.gz and en.txt.gz from OPUS OpenSubtitles v2018",
    )
    parser.add_argument(
        "--work",
        type=Path,
        default=Path("/tmp/bikeyboard-bigrams"),
        help="scratch space for the spilled partial counts",
    )
    parser.add_argument(
        "--threshold",
        default="de=5,en=20",
        help="drop pairs seen fewer times; one number, or per language as de=5,en=20",
    )
    parser.add_argument("--report", action="store_true", help="sweep thresholds, write nothing")
    parser.add_argument("--languages", default="de,en")
    args = parser.parse_args()

    for language in args.languages.split(","):
        threshold = thresholds(args.threshold, language)
        if threshold < MIN_KEEP:
            raise SystemExit(f"threshold {threshold} is below the floor of {MIN_KEEP}")
        build(
            language,
            args.corpus / f"{language}.txt.gz",
            args.work,
            threshold,
            args.report,
        )


def thresholds(spec: str, language: str) -> int:
    """The threshold for one language, from `5` or from `de=5,en=20`.

    **Per language, above a floor that is not.** The floor exists because the
    provenance argument is about absolute occurrences — a phrasing said fewer
    than a handful of times anywhere must not survive — and that does not scale
    with how much corpus there happens to be.

    Above the floor it is a size-against-coverage knob, and the two corpora are
    not the same size: English has eleven times German's tokens, so the same
    number means something eleven times weaker there. Measured, at the shipped
    settings: German keeps 95.4% of its pair mass at 5, English 97.1% at 20 —
    closer to parity than any single number gets, and smaller than most.
    """
    if "=" not in spec:
        return int(spec)
    for part in spec.split(","):
        name, _, value = part.partition("=")
        if name.strip() == language:
            return int(value)
    raise SystemExit(f"no threshold given for {language} in {spec!r}")


if __name__ == "__main__":
    main()
