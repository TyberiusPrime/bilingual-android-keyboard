# Design document

**Status: empty on purpose.** This gets filled in from the interview; see
`docs/android-ime-api.md` for what the platform allows.

## Problem statement

Existing Android keyboards, on a Fairphone running /e/OS:

1. Carry a single active dictionary, so typing in two languages means constant
   fighting or constant switching.
2. Bind layout to language, so switching language moves the keys.
3. Ship narrow space bars with punctuation immediately adjacent, so a thumb
   aimed at space produces `.`.
4. Predict and correct badly.

## Open questions

Tracked here as they come up in the interview, and resolved into decisions
below.

- [ ] One multilingual model, or two monolingual models plus an arbiter?
- [ ] Where do the dictionaries and the model come from, and under what licence?
      The project is MIT; most good wordlists and every AOSP-derived keyboard
      are GPL.
- [ ] What is the confidence threshold for auto-replace, and how is undo
      surfaced?
- [ ] Does correction ever cross language boundaries within one sentence?
- [ ] One-handed mode, or reachability handled purely by touch modelling?

## Decisions

### D1 — Languages: German and English, roughly balanced

Neither is the fallback. Both dictionaries and both language models stay live
simultaneously; there is no "current language" that gates lookup.

### D2 — Switching happens mid-sentence, constantly

This is the hardest case and it rules out the cheap designs. Specifically it
rules out:

- per-field language inference as the primary mechanism (too coarse),
- treating `EditorInfo.packageName` as more than a weak prior,
- any model where one language's dictionary is loaded and the other is not.

What it requires is **per-word language inference**, with the sentence context
so far as evidence, and a scoring model where a candidate from either language
can win at any position. The unit of language identity is the word, not the
field and not the message.

### D3 — Auto-replace only at high confidence

Silent replacement is permitted, but only above a tuned confidence threshold;
below it, corrections are offered and never applied. Two consequences:

- The scorer must produce a calibrated confidence, not just a ranking. A
  ranking tells you which candidate is best; only calibration tells you whether
  the best candidate is good enough to act on unasked.
- Threshold must be tunable and undo must be visible and cheap, because the
  failure mode being designed away — a keyboard that confidently corrupts a
  word — is one of the original complaints.

Note the interaction with D2: a word that is valid in the *other* language must
not be treated as a misspelling. Cross-language false positives are the most
likely way this feature becomes the thing it was meant to fix.

### D4 — Subtle language indicator, non-interactive

The keyboard shows what it currently believes without offering a control to
change it — deliberately not a mode. Its purpose is diagnostic: when a
correction goes wrong, the indicator should make it obvious *why*. A separate,
louder debug overlay is expected during tuning.

### D5 — QWERTY letter positions, umlauts on long-press

One layout, English letter positions, `äöüß` reached by long-pressing `a`,
`o`, `u`, `s`. Follows from D1/D2: with genuinely mid-sentence switching there
is no moment at which swapping to QWERTZ would be correct, so the y/z positions
must be fixed, and QWERTY is the one that does not surprise.

The cost is a long-press per umlaut in German, which is real and lands on the
language that needs it most. Two mitigations, both dependent on the prediction
engine rather than the layout:

- Typing `a` where `ä` was meant should be *correctable* — an unaccented
  spelling of a word that exists only with the umlaut is a high-confidence
  correction, and the cheapest possible win under D3.
- Long-press timing must be tuned rather than inherited; the default Android
  long-press delay is noticeably too slow for a key you hit routinely.

### D6 — Punctuation is inserted, not typed

The letter layer carries no `.` or `,` at all. Instead:

- double-space inserts `. ` (period, space) and re-arms capitalisation,
- the symbol layer keeps the full punctuation set for everything else,
- correction is expected to place apostrophes in contractions unprompted.

This is the direct fix for the original complaint: there is no punctuation key
adjacent to the space bar because there is no punctuation key on the letter
layer at all. `LayoutsTest` already enforces the weaker version of this (no
text key adjacent to space); it should be tightened to the full rule once the
letter layer is final.

### D7 — No swipe typing now; do not design it out

Tap typing only for the foreseeable future. The constraint this places on the
architecture: the scoring layer must take a *sequence of touch points* and
return ranked candidates, rather than having the view commit a letter per tap
and the model see only characters. If the boundary is drawn there, a gesture
decoder can later be added as a second producer of candidates against the same
dictionaries and the same language-inference model.

This is cheap to honour now and expensive to retrofit, which is the only reason
it is being decided before it is needed.

### D8 — Conservative learning — and its conflict with D3

Chosen: the keyboard learns only from words explicitly accepted (a tapped
suggestion, or a word added by hand). No silent absorption of everything typed.

**This conflicts with D3.** High-confidence auto-replace plus a store that
never learns your vocabulary means every name, every piece of jargon, every
project codeword gets silently corrected to a dictionary word — forever,
because nothing in the loop ever teaches it otherwise. That is precisely the
behaviour listed as complaint 4.

**Resolved, after the conflict was put explicitly:** no implicit learning of
any kind. The personal store changes only through an "add word" action. Undo
is undo; it teaches the keyboard nothing.

The accepted consequence is that a word the keyboard corrects wrongly will keep
being corrected wrongly until it is added by hand. Two things follow, and they
are now load-bearing rather than nice-to-have:

- **Adding a word must be trivially reachable from the moment of annoyance** —
  ideally one tap at the point where the bad correction happened, not a trip
  into a settings screen. Under this policy it is the *only* feedback channel,
  so its friction sets the ceiling on how good the keyboard ever gets for this
  user.
- **The auto-replace confidence threshold should start conservative**, because
  the self-correcting mechanism that would normally excuse an aggressive
  threshold does not exist here.

### D9 — Always-visible suggestion strip

A permanently present strip, so keyboard height never changes while typing. It
carries below-threshold corrections (which under D3 are offered rather than
applied) and next-word predictions.

### D10 — Small on-device neural model from the start

Not an n-gram-first approach. Accepted costs: tens of MB of APK, per-keystroke
inference latency to budget for, and correction failures that are much harder
to explain than a dictionary lookup's.

The debug indicator from D4 becomes more important under this decision, not
less — when a neural model corrects something wrongly, the visible belief state
is the only cheap diagnostic available.

Open: whether this is one multilingual model or two monolingual models with an
arbiter. See open questions.

### D11 — One-thumb use is common

Not exclusive, but frequent enough to be a constraint. Notably *not* chosen:
a dedicated number row, and a taller-than-stock keyboard. So reach has to be
solved without extra height — which points at touch-target biasing and
reachability rather than at geometry.
