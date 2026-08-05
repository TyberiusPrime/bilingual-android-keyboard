# Design document

**Status: decisions D1–D22 settled; architecture drafted from them. Roadmap
steps 2 and 3 built; editor I/O (step 4) next.** See `docs/android-ime-api.md`
for what the platform allows and what it withholds.

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

- [ ] What is the starting confidence threshold for auto-replace, in numbers?
      Cannot be answered before there is something to measure.
- [ ] Which concrete model and corpus. D12 sets the shape, not the artefact.
- [ ] Does the umlaut correction from D5 apply inside English words too
      (`uber` → `über`)? Probably not, but it is a real ambiguity.
- [ ] Emoji: search, recents, skin tones — entirely unaddressed so far.
- [ ] **Are `7` on `j` and `9` on `l` acceptable?** See D17; this is the one
      arbitrary placement in the layout and the likeliest thing to want changed
      after a week of real use.
- [ ] Is three the right number of slots in the strip (D21)? Guessed from other
      keyboards. Now that the strip has content, it is answerable.
- [ ] German homographs are offered lowercase — `zeit`, `weg`, `recht` — unless
      shift is pressed (D22). Worth a part-of-speech source, or worth living
      with?
- [ ] Completion only, no edit distance (D22). Which typos does that leave
      uncorrected in practice, and is prefix-only enough until the model lands?

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

**Built, as a tint behind each suggestion** (D22): gold for German, blue for
English, a tenth of the way from the keyboard surface to the hue, and nothing
at all behind a word from the personal store. Deliberately per candidate rather
than per keyboard — under D2 the language belongs to the word, so a strip
showing a German and an English candidate side by side, differently tinted, is
the honest picture. There is nowhere else it could go without inventing a
current language for it to describe.

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

Model shape is decided separately in D12.

### D11 — One-thumb use is common

Not exclusive, but frequent enough to be a constraint. Notably *not* chosen:
a dedicated number row, and a taller-than-stock keyboard. So reach has to be
solved without extra height — which points at touch-target biasing and
reachability rather than at geometry.

### D12 — One multilingual model over a shared subword vocabulary

Asked as "one model or two", answered "whatever is most practical to get
working". Taking that as licence to decide on engineering grounds: **one
multilingual model.**

The reasoning is D2. Two monolingual models plus an arbiter is genuinely easier
to source and debug, and it fails at exactly the case this project exists for:
in *"das ist ein total edge case"*, the English model scoring `edge` has never
seen the German context that makes it predictable, and the German model scoring
`total` cannot use the English continuation. An arbiter choosing between two
context-blind scores cannot recover information that neither model ever had.
A single model over a shared subword vocabulary treats code-switching as
ordinary context, which is what it is for this user.

Costs, accepted: harder to source (few off-the-shelf small DE+EN LMs; likely
means training one on a mixed corpus), and no per-language scores to inspect
when it misbehaves — which raises the value of the D4 indicator again.

Practicality hedge, and the reason D7's interface boundary matters: the scorer
is defined by an interface, not by the model. If a trained multilingual model
turns out not to be reachable in reasonable time, two monolingual scorers can
sit behind the same interface as a fallback without changing anything above it.

### D13 — GPLv3

Relicensed from MIT. Opens up AOSP-lineage keyboard source (LatinIME,
OpenBoard, HeliBoard) as reference and as code, and the good GPL wordlists —
which for German is close to a requirement. Also the norm for this category:
essentially every FOSS keyboard is GPL.

Practical consequence: dictionary and model artefacts need their provenance and
licence recorded per file as they are added, not reconstructed later.

### D14 — Backspace reverts an auto-correction; the strip offers "add word"

Immediately after a silent auto-replace, backspace restores exactly what was
typed rather than deleting a character, and the suggestion strip turns into a
one-tap "keep this word" affordance.

This is the mechanism D8 makes load-bearing: with no implicit learning, this is
the *only* path by which the keyboard ever learns anything. Two requirements
follow:

- The revert window must be unambiguous — it applies only to the keystroke
  immediately after the replacement, and any other input cancels it. A
  backspace that sometimes deletes a character and sometimes restores a word is
  worse than either.
- The "keep this word" affordance is the entire feedback channel, so it appears
  at the moment of annoyance and takes one tap. No settings screen.

### D15 — Reach handled by touch modelling, no one-handed mode

No compact mode, no visible change. The hit-tester learns that one-thumb taps
drift predictably and compensates.

Concretely this means the touch layer must not be a rectangle test. Each key
carries a spatial likelihood, taps produce a distribution over keys rather than
a single key, and that distribution is one input to the candidate scorer
alongside the language model — which is the same architecture D7 requires for
gesture typing later. Both decisions point at the same boundary.

### D16 — The layer toggle never moves

The layer-toggle key occupies the identical row, position and width on every
layer. Enforced by `LayoutsTest`, and enforced structurally by the action being
`ToggleLayer` rather than `SwitchLayer(target)` — with a toggle there is nothing
for a second layer-switch key elsewhere to *mean*, so the shape of the type
makes the constraint hard to violate by accident.

Direct consequence: **there is no third "more symbols" layer.** The rarer glyphs
(`_ [ ] { } < > \ | € § …`) hang off long-press on the symbol layer instead. A
third layer would need either a second toggle position or a three-way cycle,
and both break the rule.

### D17 — Digits on long-press; umlauts win the keys they share

Digits sit on the top row in positional order — `q`=1, `w`=2, … `p`=0, matching
a number row — reachable by long-press.

The collision: positionally `u`=7 and `o`=9, and those are exactly the ü/ö keys.
Per the constraint, umlauts win. The two displaced digits move to the nearest
keys below them on a staggered QWERTY: **7 on `j`, 9 on `l`**. `a` and `s` carry
`ä` and `ß` and no digits.

This keeps all ten digits reachable without the symbol layer, which is the point
of the constraint, at the cost of two of them not being where the positional
rule would put them. Flagged as an open question because it is a guess about a
habit, and habits are measured rather than reasoned about.

The first alternate of every key is drawn small in its corner, so the digits and
umlauts are discoverable without holding each key to find out.

### D18 — Not `directBootAware`

The keyboard is not available before first unlock. Confirmed as fine, which
removes a real constraint: the personal store, dictionaries and model can live
in ordinary credential-encrypted storage rather than device-encrypted storage,
and nothing has to be split across the two.
### D19 — Recent-keypress trail on the keys

The last ten insertions are kept as a stack; the five most recent are drawn as
a colour gradient on the keys themselves — full purple for the most recent,
fading to the resting key colour by the fifth.

Rules:

- **Backspace pops the stack** rather than pushing to it, so deleting walks the
  highlight backwards through what you typed. The stack is deeper than the
  gradient (ten versus five) so backspacing past the visible colours keeps
  revealing older presses instead of running out.
- **Cursor movement clears it.** A trail is only meaningful for a contiguous
  run of typing.
- **A repeated key shows only its most recent depth.** Otherwise a doubled
  letter would compete with itself.
- **A long-press alternate colours only the top half of the key**, matching
  where its hint is drawn — so "I typed the ü, not the u" is readable without
  a second glance.
- Only insertions are on the stack. Modifiers are not things you typed, and
  Enter usually submits rather than adding to the text in front of you.

Detecting "cursor moved" requires distinguishing our own edits from the app's,
which is the editor-I/O bookkeeping that roadmap step 3 exists for. What is
implemented here is the smallest useful piece of it — an expected cursor
position, maintained across insertions and deletions — and it is deliberately
conservative: anything it cannot account for clears the trail. It is a
down payment on step 3, not a substitute for it.

### D20 — Space and backspace carry gestures

Both keys do more than one thing, which is affordable because both are large
and neither has a long-press alternate to collide with.

**Space**
- Tap inserts a space.
- Double tap ends the sentence: the space just typed becomes `". "` and
  capitalisation re-arms. This is the second half of D6 — with no `.` on the
  letter layer, this is how a period gets typed in ordinary prose. It applies
  only when a word actually precedes the space; after punctuation, a newline or
  nothing at all, a second space stays a space.
- Dragging sideways steers the cursor, one character per ~12dp.

  Entry into cursor mode is by **distance, not by a hold timer**. Requiring a
  delay first makes the gesture feel stuck, and horizontal travel on the space
  bar is unambiguous on its own. Once steering, the touch no longer types a
  space on release.

**Backspace**
- Fires on press rather than release, and auto-repeats after 400ms at ~18/s.
  Repeating keys have to act on press or they feel broken.
- **Swiping left ~30dp** deletes one word — once per swipe, not once per step.
  Lift and swipe again for the next word.

  This started as a double tap and was changed after a day of use: two quick
  taps on backspace is exactly what you do when you want two letters gone, so
  the gesture fired constantly by accident. A direction has no such collision —
  nothing else on backspace is horizontal — and it matches the direction of
  deletion. **Space keeps its double tap**, because there is no competing
  reason to hit space twice quickly.

  Repeating per unit of travel was tried and removed the same day: a swipe that
  keeps deleting takes out whole clauses before the finger stops, and a
  destructive gesture wants a fixed, predictable cost.

Auto-repeat ticks are delivered on a separate callback from real presses, so
the machine gun is never mistaken for a deliberate gesture.

**Cursor drag must not walk off the end.** A `DPAD_LEFT`/`DPAD_RIGHT` that the
text field cannot consume — because the cursor is already at the start or the
end — is not swallowed. It falls through to Android's focus navigation, focus
leaves the field, the input connection ends, and the keyboard vanishes. So each
step checks there is a character to move past before asking to move.

This was originally misdiagnosed as the platform's back gesture claiming the
screen edge, on the strength of the symptom being rightward-only. The report
that it happened *whenever the cursor reached the end of the text* is what
identified it. The keyboard still claims its area via
`setSystemGestureExclusionRects`, which is correct hygiene for a surface whose
own gestures run to the screen edge, but it was not the cause.

### D21 — The strip: three fixed slots, and it knows the word without asking

The mechanism half of D9, built while there is still nothing to put in it.

**It costs no height.** The 40dp band above the keys was already reserved — as
plain gutter, so that a long-press popup on the top row had somewhere to be
drawn. The strip takes that band over. The popup now overhangs upwards into it
(the container turns off child clipping and draws the keys last), which is
better than what it did before: with the popup clamped to the top of the key
area it landed under the finger holding the key.

**Three slots, fixed whether occupied or not.** A suggestion never slides
sideways when another appears or disappears. Same argument as D16 makes for the
layer toggle: a target that moves between the look and the tap gets mis-hit, and
a mis-hit here inserts a word. The cost is that a lone suggestion sits in the
left third rather than centred, which looks slightly odd and is the right trade.
Best candidate first, left to right; nothing scrolls, because a suggestion you
have to go looking for is not a suggestion.

**The keyboard does not ask the app what the current word is.** Reading
`getTextBeforeCursor` on every keystroke is an IPC round trip per key, which the
API notes name as the thing that makes a keyboard feel broken. So the keyboard
tracks what it typed — `WordInProgress`, the same posture as the expected-cursor
bookkeeping from D19 — and it refuses to guess: after a cursor move, a replaced
selection, or a backspace that ate past the start of what it was tracking, the
word is *unknown* and the strip stays empty until the next word boundary. That
refusal is what makes tapping a suggestion safe: the characters it deletes are
ones this keyboard put there.

Still a down payment on step 3, not a substitute for it. Step 3 owns the
composing region, which is the mechanism that makes a correction replaceable
after the fact rather than counted backwards through.

**A picked suggestion inserts a trailing space, and that space counts as the
first half of D6's full stop.** The space is needed so that next-word
predictions can be tapped one after another without running together — but it
means the text already ends in a space, so double-tapping space to end the
sentence would see *space, space* rather than *word, space*, and D6's rule
declines to make a full stop out of that. So one tap on space after accepting a
suggestion ends the sentence. From the typist's side it is the same gesture: the
first space was placed for them.

The rule that makes this safe is that **anything other than a space clears it**.
Accept a word, type a letter, and space is a space again. Making that explicit
also fixed an older bug: the previous key-and-timestamp version never reset
between taps, so `space`, letter, `space` typed quickly counted as a double tap
and dropped a full stop into the middle of a sentence.

**Nothing is suggested into a password, a `NO_SUGGESTIONS` field, an email
address, a URI or a search filter** — the first two an obligation from the API
notes, the rest because they are not prose. The strip stays *visible* in those
fields regardless, because D9's promise is that the keyboard height never
changes.

**What is deliberately absent:** any content. There are no dictionaries until
step 4, and inventing a small wordlist to make the strip look alive would put
words of unrecorded provenance in the repo (D13) and teach nobody anything about
whether the design works. The strip renders as bare surface until it has
something true to say.

### D22 — Two wordlists, one scale, one casing per word

Roadmap step 3, and the first thing in the project that makes the strip say
anything. Deliberately the dumb version: completions of what has been typed,
ranked by how common the word is. No edit distance, no context, no model.

**Both lists are queried on every keystroke and compete on one scale.** This is
D1 and D2 made real rather than promised: a word's weight is its share of its
own corpus, so a common German word and a common English word are directly
comparable, and nothing anywhere holds a current language. Typing `inte` offers
*interessiert*, *interesting*, *interested*; `str` offers *street*, *Straße*,
*straight*. That is the entire thesis of the project, working, at a point where
there is no model in the build at all.

**Matching is folded** — lowercased, `ß` as `ss`, accents dropped — so `ube`
finds `über`. That is the cheap half of D5's mitigation: the umlaut costs a
long-press, and skipping it now costs a tap on the strip instead.

**One casing per word, and the typist supplies the first letter.** Two entries
differing only in case would eat two of three slots to say the same thing, so
the wordlists carry one — the least-capitalised form the spelling list offers,
which keeps `nicht` over `Nicht` and leaves `Haus` alone because no lowercase
`haus` exists. A capital the typist types is then applied to the candidate, so
`Zei` completes to `Zeit`.

The cost, and it is visible: German homographs whose spelling list kept the
lowercase reading — `zeit`, `leben`, `weg`, `recht` — are offered lowercase
unless shift is pressed. Telling a noun from an adjective needs a
part-of-speech signal that no GPL-compatible source here carries, and guessing
it would capitalise adjectives instead. Recorded in the wordlists' own
`PROVENANCE.md` alongside the other gaps.

**The personal store is a lexicon with a fixed weight** — about that of the
two-hundredth most common word, so an added word beats ordinary vocabulary but
not `the` or `ich`. It is reached by the add-word offer, which occupies the
strip's rightmost slot whenever the word being typed is one nothing recognises.
That is D8's requirement met literally: one tap, at the moment of annoyance, no
settings screen. It is also the only thing in the keyboard that writes to the
store, which makes `IME_FLAG_NO_PERSONALIZED_LEARNING` a single check rather
than a policy spread across the codebase.

**A candidate holding most of the matching mass is drawn in purple** — the same
purple the keypress trail uses for "this came from the keyboard". The threshold
is half the mass, it is a guess, and it is *appearance only*: D3's auto-replace
threshold does not exist yet and will be a calibrated number rather than a
unigram share. What it does today is make the eventual threshold legible before
anything acts on one, which is the cheapest possible way to find out whether it
sits in the right place.

**The add-word offer appears only when nothing else does.** Half-typed words are
unrecognised nearly all of the time, so an offer keyed on "unknown word" alone
sat in the strip almost permanently and meant nothing when it did. Silence from
both dictionaries is the moment of annoyance D8 attaches it to.

**No profanity filter, deliberately.** A keyboard that declines to suggest words
its owner types is a variant of complaint 4, and the corpus is what people
actually say. The wordlists carry whatever the spelling lists and the subtitle
corpus agree on.

**What this is not:** it is not correction. There is no edit distance, so a
typo that is not a prefix of the intended word gets nothing. The confidence on
each candidate is its share of the matching mass — a unigram
*P(word | prefix)* — which is honest but is not the calibrated number D3 wants,
and nothing is ever replaced silently. D3 and step 7 are untouched.

Measured on a laptop, not the Fairphone: 70,000 entries load in ~75ms, and a
query costs ~0.2ms. The load happens once per service on a background thread,
so the first moment of a session has an empty strip rather than a stalled one.
The device numbers are the ones that matter and are not in yet.

---

## Architecture

Falls out of the decisions above, particularly D7, D12 and D15.

```
      touch points
           │
           ▼
   ┌───────────────┐   spatial likelihood per key, not a hit test (D15)
   │  TouchModel   │   learns one-thumb drift; later, gesture paths (D7)
   └───────┬───────┘
           │  P(key | touch) distribution per tap
           ▼
   ┌───────────────┐   word candidates consistent with the tap sequence,
   │  Candidates   │   from both languages, plus the personal store (D8)
   └───────┬───────┘
           │  candidate set
           ▼
   ┌───────────────┐   one multilingual subword LM (D12) scores candidates
   │    Scorer     │   in sentence context; emits CALIBRATED confidence (D3)
   └───────┬───────┘
           │  ranked candidates + confidence
           ▼
   ┌───────────────┐   above threshold → silent replace (D3)
   │    Policy     │   below → offer in strip (D9); never learns (D8)
   └───────┬───────┘
           │
           ▼
   ┌───────────────┐   composing-region bookkeeping, undo window (D14),
   │  Editor I/O   │   reconciliation with onUpdateSelection
   └───────────────┘
           │
           ▼
     InputConnection
```

Notes on the layers that are not obvious:

**TouchModel** is where complaint 3 is actually solved. Making the space bar
wide (already done) helps; making the hit test probabilistic and letting the
language model break ties is what removes the class of error. A tap landing
between two keys should not be resolved by geometry alone when the sentence
context makes one of them far more likely.

Three invariants, learned from the first round of device testing, that hold
regardless of how clever the model later becomes:

- **Touches are per-pointer.** Fast typing overlaps them — the next finger
  lands before the previous lifts. A single "currently pressed key" field drops
  one of every overlapping pair, which reads as random missed keystrokes.
- **A press commits the key it started on.** Not the key under the release
  point. A tap that drifts off the keyboard entirely must still type what it
  began on; resolving at release time turns drift into silence.
- **There are no gaps.** Hit areas tile the whole surface — they meet in the
  middle of the visual gaps and run to the view edges — rather than matching
  the drawn key rectangles with slop bolted on.

**Window insets.** `targetSdk 35` makes edge-to-edge mandatory, so the system
stops insetting the IME window. Unhandled, the system's own hide-keyboard
chevron, IME-switcher globe and gesture pill are composited over the bottom row
and take its taps. The input view is wrapped in a container carrying the
navigation-bar inset as bottom padding.

**Scorer** must emit calibrated confidence, not just a ranking (D3). This is a
distinct engineering task from getting good rankings, it is usually skipped,
and skipping it is why other keyboards auto-correct confidently and wrongly.

**Editor I/O** is the bookkeeping layer described in the API notes: it owns the
composing region, tracks what was committed versus what the app reports, and
handles `onUpdateSelection` contradicting it. Most "text got scrambled in app
X" bugs live here. It is also where the D14 undo window lives, since that
window is defined in terms of committed-text state.

**The strip is Policy's only visible output** until auto-replace is allowed to
turn on (step 7). It is built (D21), and the `SuggestionSource` behind it now
answers with real words from both languages (D22). What it feeds back is a tap,
which the service turns into "replace the word in progress with this" — the
narrowest editing operation that still exercises the whole path — or into "add
this word to the personal store", which is the only way anything is ever
learned (D8).

**Language inference is not a layer.** There is no component that decides "we
are in German now". Per D2 and D12, language identity is a property of a
candidate, resolved per word by the scorer. The D4 indicator reads out the
scorer's belief; it does not drive anything.

## Roadmap

1. **Scaffold** — service, layout, CI, sideloadable APK. *Done.*
2. **Typing that is pleasant without any intelligence** — layout constraints
   (D16, D17), umlaut and digit long-press with tuned timing (D5), double-space
   period (D6), space and backspace gestures (D20), keypress trail (D19),
   suggestion strip present but empty (D9, D21). Daily-drivable, dumb.
   *Done.*
3. **Dictionaries and personal store** — GPL DE/EN wordlists with provenance
   (D13), the add-word path (D8/D14), plain lookup-based suggestions (D22).
   *Done.*
4. **Editor I/O done properly** — composing regions, selection reconciliation,
   undo window (D14). This is the layer that makes everything above it
   trustworthy, and the one most likely to be underestimated.
5. **TouchModel** — probabilistic hit testing, one-thumb drift compensation
   (D15). Measurable against step 2 on typo rate.
6. **The multilingual model** (D10/D12) — source or train, quantise, integrate
   behind the scorer interface, measure latency on the actual Fairphone.
7. **Calibration and threshold tuning** (D3) — the point at which auto-replace
   is allowed to turn on at all.

**Steps 3 and 4 are swapped from the original order**, which had editor I/O
first. The reason is that editor I/O has nothing to be tested against while the
strip is empty: composing regions, replacement and the undo window are all
defined in terms of corrections that do not exist yet, so building them first
means building to a specification nobody has typed against. Dictionaries produce
the corrections, and the corrections are what shows whether the editing model
holds up in real apps. The risk of doing it this way is that step 3 ships
suggestions on top of the deliberately conservative word tracking described in
D21 — good enough to offer and replace a word, not good enough to be the final
answer — and step 4 has to go back over that ground properly rather than
starting clean.

Steps 2–5 are worth having on their own; a keyboard with a stable layout, a
generous space bar and no autocorrect is already better than what is being used
today. Step 6 is where the project either delivers or does not, and it should
not be started before editor I/O is solid.

## Risks

- **Editor I/O is underestimated.** Now step 4, and it looks like plumbing; it
  is where keyboards actually break. Budget accordingly.
- **No suitable small DE+EN model exists off the shelf**, making step 6 a
  training project rather than an integration one. Mitigated by the D12 hedge.
- **Latency on real hardware.** A model that is fine on a laptop may not hold a
  per-keystroke budget on a Fairphone. Measure early, on the device, not in an
  emulator.
- **No implicit learning (D8) caps the ceiling.** If the add-word path has any
  friction at all, the keyboard will stay wrong about this user's vocabulary
  indefinitely. This is the accepted cost of the chosen privacy posture. The
  path exists now (D22) — one tap in the rightmost slot — and whether it is
  actually reached in the moment of annoyance is a question for real use.
- **Daily-driver risk.** A crash makes the phone untypeable. Keep a second
  keyboard installed; consider a crash guard that disables the fancy path
  rather than the service.
