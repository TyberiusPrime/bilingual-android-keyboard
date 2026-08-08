# Design document

**Status: decisions D1–D46 settled; architecture drafted from them. Roadmap
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
- [x] **Which concrete model and corpus.** Settled in D46: a bigram store as the
      measurable baseline, then a ~25M-parameter joint-vocabulary decoder, both
      interpolated with the unigram weight so neither can score worse than the
      lookup does today. What is *not* settled is the corpus licence — see D46's
      blocker, which is now the first task of step 6.
- [ ] Does the umlaut correction from D5 apply inside English words too
      (`uber` → `über`)? Probably not, but it is a real ambiguity.
- [ ] Emoji: search, recents, skin tones — entirely unaddressed so far.
- [x] **Are `7` on `j` and `9` on `l` acceptable?** Yes — reported fine after
      real use on the phone (D17).
- [ ] Is three the right number of slots in the strip (D21)? Guessed from other
      keyboards. Now that the strip has content, it is answerable.
- [ ] German homographs are offered lowercase — `zeit`, `weg`, `recht` — unless
      shift is pressed (D22). Worth a part-of-speech source, or worth living
      with?
- [x] **Corrections cannot fix a mistyped first letter.** Fixed in D35 for the
      cases with evidence — an adjacent-key slip or a transposed first pair —
      by widening the buckets rather than adding an index. A first letter typed
      dead centre on the wrong key stays out of reach, and the falloff slider is
      the lever for it.
- [ ] Is the unknown-word prior right (D28)? Everything the auto-correction
      threshold does, it does relative to that one number, and it was guessed.
- [x] **Next-word prediction has no data source.** Answered by D46: a bigram
      store counted off the same corpus, shipped and wired into the strip. What
      is still open is whether context should also rescore *corrections*, which
      would move every number D43 and D45 measured and so needs its own pass.
- [ ] **Language inference, as opposed to provenance.** D2 asks for per-word
      inference from sentence context; what exists is a label saying which file
      the word came from (D45 makes that label honest, it does not make it an
      inference). Nothing reads `EditorInfo.hintLocales` either.
- [ ] Auto-correction still cannot spell `Straße` from `strasse` (D28), `ß`
      folding one character at a time.
- [ ] The `'s` contractions are systematically under-weighted (D34): their share
      is allocated across all 29,467 possessive stems, so `that's` lands near the
      threshold where `don't` clears it easily. Worth a better estimator?
- [ ] Is the falloff's default of 7 right (D34)? It is now a slider, which is an
      admission that nobody here can answer this from a laptop.
- [x] **Per-keystroke latency.** Confirmed usable on the phone, and since D37
      it is one search rather than two: 0.2–1.5ms warm on a laptop against the
      shipped lists, worst case a long German word. Still not profiled on the
      device itself, but no longer the open question it was.
- [x] **Which haptic route works on this phone?** Only Insistent (D29) — so the
      motor is fine and this phone has touch feedback muted system-wide. Two
      rounds of tuning constants were spent on a switch in another app.
- [ ] Is the order within an accent popup right (D32)? `é è ê ë` is alphabetical
      by accent name and nothing better, and the ones past the third are a slide
      most of the way across a key row.
- [x] **`suggest()` and `correct()` had drifted apart** (D33 patched over it).
      Unified in D37: one search, two views, and the strip can no longer fail to
      show a word the space bar is about to insert.

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

**None of the inference exists yet, and the decision reads as though it does.**
What is built is per-candidate *provenance*: `Suggestion.language` records which
file the word was read from, and no context of any kind is consulted anywhere in
the path. The second half of the requirement — that a candidate from either
language can win at any position — is built and is the part that matters so far
(D22, D45). The first half waits on step 6. Nor is anything read from
`EditorInfo`: this decision demotes `packageName` and the locale hints to a weak
prior, which implies used-as-a-prior, and today they are used as nothing at all.

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

**Built in D28**, where the calibrated number turns out to rest on the touches
rather than on the dictionary, and where the rule above became a hard gate: a
word spelled exactly right is never replaced.

### D4 — Subtle language indicator, non-interactive

The keyboard shows what it currently believes without offering a control to
change it — deliberately not a mode. Its purpose is diagnostic: when a
correction goes wrong, the indicator should make it obvious *why*. A separate,
louder debug overlay is expected during tuning.

**Built, as a tint behind each suggestion** (D22): gold for German, blue for
English, a fifth of the way from the keyboard surface to the hue, and a neutral
wash behind a word from the personal store, which belongs to no language. Half
that strength was tried first and could not be seen on the phone at all, which
for an indicator is the same as not existing; an untinted slot next to two
tinted ones was read as a rendering bug rather than as a third case.

Deliberately per candidate rather than per keyboard — under D2 the language
belongs to the word, so a strip showing a German and an English candidate side
by side, differently tinted, is the honest picture. There is nowhere else it
could go without inventing a current language for it to describe.

**The neutral wash means "no language claim", not "personal"** (D45). It was
introduced for the personal store, which belongs to no language; a word both
wordlists carry belongs to no language either, and for a fifth of typing mass
the tint used to name whichever corpus happened to weigh the word more. Since
the indicator is diagnostic, saying *both* is worth more than picking one.

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

- double-space inserts `. ` (period, space) and re-arms capitalisation, and
  **repeats**: the second double tap swallows both the space it just made and
  the one it typed, so `word. ` becomes `word.. ` and three of them spell an
  ellipsis without a trip to the symbol layer,
- the symbol layer keeps the full punctuation set for everything else,
- correction is expected to place apostrophes in contractions unprompted.

This is the direct fix for the original complaint: there is no punctuation key
adjacent to the space bar because there is no punctuation key on the letter
layer at all. `LayoutsTest` already enforces the weaker version of this (no
text key adjacent to space); it should be tightened to the full rule once the
letter layer is final.

### D7 — No swipe typing now; do not design it out

**Superseded by D39, which added it. The reasoning below is what made that
cheap, and is kept for that reason.**

Tap typing only for the foreseeable future. The constraint this places on the
architecture: the scoring layer must take a *sequence of touch points* and
return ranked candidates, rather than having the view commit a letter per tap
and the model see only characters. If the boundary is drawn there, a gesture
decoder can later be added as a second producer of candidates against the same
dictionaries and the same language-inference model.

This is cheap to honour now and expensive to retrofit, which is the only reason
it is being decided before it is needed.

**What it turned out to be worth, when D39 came to collect.** The prediction
was half right, and it is worth being precise about which half, because the
same reasoning will be applied again to the language model.

Right about the *seam*. A gesture did slot in as a second producer against the
same lexicons, the same frequency weighting, the same `Candidates` type and so
the same confidence scale the strip and the auto-replace threshold already read.
Nothing downstream of the candidate set changed at all.

Wrong about the *shape of the boundary*. D7 assumed the interface would be a
sequence of touch points and that a swipe would arrive through it. It cannot: a
tapped word is characters each with a touch behind it, and a stroke is a shape
with no characters in it whatsoever. There is no honest per-character split of
a path that crosses six keys it does not mean. Swiping got its own entry point
next to the existing one rather than reusing it.

The lesson for D10/D12: what a foresighted boundary actually buys is that
**everything downstream is shared**. Guessing the exact signature years early
buys nothing, and `List<TypedTouch>` — designed to be the future-proof one —
was the part that had to be worked around.

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
- **And it must be reviewable.** A one-tap add with no way back fills up with
  half-typed mistakes — `+ purp` is one tap away from being learned forever. The
  launcher screen lists everything the store holds and can take any of it out
  again. That is the only screen the keyboard has, and it is the right one:
  seen on the way to the keyboard rather than gone looking for.
- **The auto-replace confidence threshold should start conservative**, because
  the self-correcting mechanism that would normally excuse an aggressive
  threshold does not exist here.

### D9 — Always-visible suggestion strip

A permanently present strip, so keyboard height never changes while typing. It
carries below-threshold corrections (which under D3 are offered rather than
applied) and next-word predictions.

**For a long time only the first half was built.** `candidatesFor` returns
nothing for an empty prefix, and `MIN_PREFIX` wants two letters before it will
complete, so the three slots sat empty at every word boundary *and* at every
word's first keystroke — roughly a third of the cycle. That was not a matter of
wiring up something that existed: both shipped wordlists are unigram
(FrequencyWords 50k), and there was no bigram data in the repo to predict from
at all. Step 3 delivered half of this decision while the roadmap read as though
it had delivered all of it.

**Both halves are built now** (D46): the strip carries next-word predictions
from a bigram store counted off the same corpus the frequencies came from.
What remains empty is what should be — a cursor jump, a backspace into the
previous word, anything the keyboard cannot vouch for.

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

**Built in D28**, along with the auto-replacement it exists to undo.

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

**Half of this arrived early**, in D28: taps now produce a distribution over
keys, and the scorer reads it. What is still missing is the part D15 is actually
about — *learning* that a one-thumb tap drifts predictably, so that the
distribution is centred where the thumb aims rather than where the key is. The
geometry is in place for it; the drift is roadmap step 5.

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
umlauts are discoverable without holding each key to find out. D32 later hung
the other European accents off the same keys, *behind* these — so "every digit
is one hold away" stays literally true rather than becoming "one hold and a
slide away". There is a test for it. D36 is the other half of that promise: the
first alternate is also where the finger already is, which stopped being true
the moment any key had more than one.

### D18 — Not `directBootAware`

The keyboard is not available before first unlock. Confirmed as fine, which
removes a real constraint: the personal store, dictionaries and model can live
in ordinary credential-encrypted storage rather than device-encrypted storage,
and nothing has to be split across the two.
### D19 — Recent-keypress trail on the keys

The last ten insertions are kept as a stack; the five most recent are drawn as
a colour gradient on the keys themselves — full purple for the most recent,
fading to the resting key colour by the fifth.

**Switchable from a key, and off *by default* in password fields** — see D38,
which explains both why it took a while to notice that a picture of the last five
keys is a picture of part of the password, and why the field defaulting to off is
as far as that goes. The trail is an accessibility feature, and the person typing
gets to decide.

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
and neither has a long-press alternate to collide with. D38 later put a third
gesture on `h`, which is neither — and pays for it with a longer threshold and a
direction test.

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

Shift joined them later; see D24.

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

The store is also the only thing here the user can be *wrong* about, so the
launcher screen lists it and can forget any word in it. Keyboard and screen hold
separate copies of the same file; each stats it on the way in and re-reads only
when the other has written.

**The personal store is a lexicon with a fixed weight** — about that of the
two-hundredth most common word, so an added word beats ordinary vocabulary but
not `the` or `ich`. It is reached by the add-word offer, which occupies the
strip's rightmost slot whenever the word being typed is one nothing recognises.
That is D8's requirement met literally: one tap, at the moment of annoyance, no
settings screen. It is also the only thing in the keyboard that writes to the
store, which makes `IME_FLAG_NO_PERSONALIZED_LEARNING` a single check rather
than a policy spread across the codebase.

**A candidate holding most of the matching mass is drawn in purple** — the same
purple the keypress trail uses for "this came from the keyboard".

The rule for *which* candidate has changed twice since; **D33 is the current
one**, and it is no longer a threshold on this ranking at all. The purple word is
the one the auto-corrector would substitute if space were pressed — the same
stored decision, not a second opinion that happens to agree most of the time.

**The add-word offer appears only when nothing else does.** Half-typed words are
unrecognised nearly all of the time, so an offer keyed on "unknown word" alone
sat in the strip almost permanently and meant nothing when it did. Silence from
both dictionaries is the moment of annoyance D8 attaches it to.

**No profanity filter, deliberately.** A keyboard that declines to suggest words
its owner types is a variant of complaint 4, and the corpus is what people
actually say. The wordlists carry whatever the spelling lists and the subtitle
corpus agree on.

**What this is not:** it is not *auto*-correction. Typos are found (D23 added
edit distance), but the confidence on each candidate is still its share of the
matching mass — a unigram *P(word | prefix)* — which is honest but is not the
calibrated number D3 wants, and nothing is ever replaced silently. D3 and step
7 are untouched.

Measured on a laptop, not the Fairphone: 70,000 entries load in ~75ms, and a
query costs ~0.2ms. The load happens once per service on a background thread,
so the first moment of a session has an empty strip rather than a stalled one.
The device numbers are the ones that matter and are not in yet.

### D23 — Corrections, and picking a word back up after a cursor jump

Two halves of the same complaint: the strip went quiet exactly when it was
wanted. A typo is not the prefix of anything, so completion had nothing to say
about it; and tapping back into an earlier word left the keyboard with no idea
what word that was, so it said nothing at all.

**Corrections are edit distance, bounded and scanned.** One edit for a short
word, two from six letters up, and a transposition counts as one — `teh` for
`the` is the commonest way a thumb misses, and calling it two edits puts it out
of reach. There is no index: a deletion index over 70,000 words costs more
memory than the wordlists themselves. Instead the scan is cut down twice, first
to words sharing the typed first letter, then to those close enough in length to
be reachable, which leaves a few hundred words to actually measure. That runs
only when completion has fewer candidates than the strip has slots, so ordinary
typing never pays for it.

The limit this accepts: **the first letter has to be right.** A word whose
first letter was mistyped is not searched at all. That is the price of not
building the index, and the first letter is the one a thumb gets wrong least
often.

Corrections rank below completions by construction — a word that was begun
correctly beats one that has to be repaired — and further-away corrections rank
below nearer ones.

**A cursor jump is answered by asking the field.** When the cursor arrives
somewhere the keyboard did not put it, it now reads the word around it back
over the `InputConnection` instead of giving up (which is what D21's
conservatism did). Both halves are kept, because correcting a word means
replacing all of it and not just the part in front of the cursor. This is the
first place the keyboard reads text back rather than remembering it, and the
budget is what makes it acceptable: **once per cursor jump, never per
keystroke.** A read that comes back null is still a refusal to guess.

The same recovery covers a backspace that eats past the start of what was being
tracked — delete a word and start retyping it, and the strip stays awake — and
separately, a backspace with nothing in front of the cursor no longer counts as
losing track at all. It used to, which left the strip dead after clearing a
field, which is precisely the moment the next word starts.

**A correction only adds a space if the text does not already have one.** At
the end of the text it does, and in front of another word it does, so
predictions still chain; in front of an existing space or a comma it does not.
Without that check, correcting a word inside a finished sentence left two spaces
behind it every time.

Measured on a laptop, not the Fairphone: a completion costs ~0.2ms, a correction
~3ms. The device numbers are the ones that matter and are not in yet.

### D24 — Shift carries gestures too

Following D20, which gave space and backspace theirs. Shift is large, has no
long-press alternate to collide with, and had exactly one behaviour.

- **Double tap locks it.** Caps lock, drawn as a latched shift so the difference
  between the next letter and every letter is one glance. A single tap unlocks.
  There is no collision to worry about here: two quick taps on shift previously
  meant "on, off", which is a null gesture.
- **Swipe up re-cases the word the cursor is in**, cycling lower →
  capitalised → shouted. Upward because shift has always pointed that way and
  nothing else on the key is vertical, and once per swipe like the backspace
  gesture, because it edits text and a destructive-feeling gesture wants a
  fixed cost.

The swipe is the direct answer to D22's known cost: the wordlists carry one
casing per word, so a German noun typed without shift is offered lowercase.
Fixing it afterwards was four keystrokes and is now one gesture — and it works
on a word jumped back to, using D23's recovery, as much as on the one being
typed.

### D25 — How large the suggestions are is a setting

Not a decision anyone made: a number that was guessed at three times from a
laptop and was fine print, then correct, then shouting, on a phone nobody here
is holding. It depends on eyesight, on screen size, and on the system font
scale, which the strip now follows as well.

So the size lives in a slider on the settings screen with a live preview, and
the band above the keys follows it — larger suggestions make room for
themselves rather than clipping — down to a floor, because the band is also the
headroom a long-press popup needs (D21). The keyboard notices the change when a
field is next focused, which is the next time it is visible anyway.

The general point, worth keeping: **a value that can only be judged by looking
at it belongs to whoever is looking.** The threshold in D3 is the opposite
case — that one has to be measured, and a slider for it would be an abdication.

### D26 — A long press on a suggestion joins it to the next word

German runs words together. `Haus` and `Tür` are `Haustür`, and a keyboard that
puts a space after every accepted suggestion is useless for exactly the half of
the vocabulary that is longest and most worth completing.

So a **tap** finishes the word — space after it, predictions chain, as before —
and a **long press** hands the word over bare. What is typed next continues it,
and the strip keeps completing the whole thing rather than starting over: after
joining `Haus`, typing `tür` looks up `Haustür`.

The hold is 400ms, longer than a key's (D5 shortened that one, because a key
long-press is on the path of ordinary typing). Nothing on the strip is, and the
cost of triggering this by accident is a word inserted without a space rather
than a missing umlaut.

Not offered for the add-word slot: remembering a word is the same act however
long the finger stays down, and there is no second meaning available for it.

### D27 — `letvs` means `let's`

The apostrophe is a long-press on `v` (D17), so the way to miss it is to tap the
key rather than hold it — and what follows an apostrophe is, overwhelmingly, an
`s`. A word ending in `vs` is otherwise almost nonexistent, which is what makes
this safe to apply without evidence: when the letters before it are a word, the
strip offers that word with `'s` on the end. `letvs` → `let's`, `gehtvs` →
`geht's`, `wievs` → `wie's`.

The candidate has to be **built rather than looked up**, and that is the
interesting part: the wordlists contain no contractions at all. The frequency
corpus behind them was tokenised by a tool that split `don't` into `don` and
`t`, so no contraction ever survived the intersection with the spelling lists
(recorded in the wordlists' `PROVENANCE.md` as a known gap). Looking `let's` up
would find nothing; looking `let` up and adding the apostrophe finds it every
time, and inherits a sensible frequency while it is there.

It covers only `'s`. The other contractions — `don't`, `can't` — need the
apostrophe in the middle, where there is no such unambiguous signal, and would
need the wordlists to know the results.

### D28 — Auto-correction on space, when the touches agree

D3 said silent replacement was permitted above a tuned confidence threshold and
that calibration, not ranking, was the hard part. This is that, turned on.

**It fires on space**, because that is the moment a word is finished and the
last moment changing it is cheap. Not per keystroke: a word being typed is not
yet wrong.

**Confidence comes from where the thumb landed.** Plain edit distance answers
"are these two words similar", which is the wrong question — `hanen` is one edit
from `haben` whether or not the finger was anywhere near `b`. So every typed
character now carries the keys it nearly hit ([TypedTouch]), substitution costs
come from that, and the distance is a real number rather than a count
([SpatialEditDistance]). The same typo scores 0.999 when the thumb was on the
`b`/`n` border and 0.76 when it was dead centre on `n` — the first is replaced,
the second is only offered. That distinction is the whole feature.

Each candidate is scored as *how likely is it that this was meant*: how common
the word is, discounted exponentially by how implausible the slips would have to
be. The confidence is that score's share of everything on the table **including
the standing chance that an unknown word was typed deliberately** — a name, a
codeword, jargon. That prior is what the threshold is really measured against,
and it is the most load-bearing guess in the file.

Two hard rules sit in front of the arithmetic, and neither is negotiable:

- **A word spelled exactly right is never touched**, however rare, however
  common its neighbour. Under D2 a word valid in the other language is valid,
  and cross-language false positives are the way this feature becomes the thing
  it was meant to fix.
- **No touches, no correction.** A word the cursor jumped back to (D23) was not
  typed here, and half a set of touches would make the keyboard confident about
  exactly the words it knows least about.

**It says so twice.** A wash of purple rises from the space bar to the top of
the keys, and the vibration is two pulses rather than one (D29). Both exist
because the typist is looking at the text rather than at the keyboard: one is
caught out of the corner of an eye, the other needs no eye at all. The flash
starts at the space bar because that is the key that caused it.

Both were too faint on the phone to register, twice. The flash is now five
sliders over a live preview (D30) rather than a constant to be guessed at, and
the vibration is a choice of route with a diagnostic beside it (D29). Something
meant to be caught peripherally has to be well past subtle, because peripheral
vision is what it is being asked to survive — and neither of these turned out to
be tunable from a laptop at all.

**Backspace puts it back** — D14, finally built. The keystroke immediately
after a replacement restores exactly what was typed, space and all, and the
strip turns into an offer to remember the word, which under D8 is the only way
the keyboard ever learns anything. Anything other than that one backspace closes
the window.

Measured on a laptop: 1.5ms per space. It was 18ms before the edit-distance
table stopped normalising Unicode in its inner loop, which is worth recording
because the profile is entirely unlike the rest of the keyboard — thousands of
tiny comparisons rather than one lookup.

That led to a second question worth writing down: **does a German and English
keyboard need Unicode normalisation at all?** The two wordlists contain fifteen
non-ASCII characters between them — `ä ü ö ß Ä Ü Ö é ñ â ê à á ó è` — every one
precomposed, and the keyboard can type four of them. So the whole of folding is
now a table rather than a normaliser: thirteen times faster, and it agrees with
the old implementation on every one of the 70,000 shipped words. The cost is
that an accent from outside those two languages is no longer folded away, which
makes it match nothing — the right answer, since it is not a word here either.

**Why the same typo is corrected only sometimes.** Because it is not the same
typo. Confidence is a smooth function of where the thumb landed, so two presses
that produce identical text can sit either side of the threshold. Measured
against the shipped wordlists, `sttong` → `strong` at the default 90%:

| Distance from `r`, in key widths | Confidence |
| --- | --- |
| 0.1 | 98% |
| 0.3 | 94% |
| 0.4 | 88% |
| 0.6 | 64% |
| 0.9 | 18% |

So it corrects from roughly the left third of the `t` key and not from the rest
of it. That is the feature working — without the touches there is no way to tell
a slip from a decision, and a keyboard that replaced `sttong` on the strength of
the letters alone would replace deliberate words too.

The corpus matters as much as the thumb, which is less obvious: at an identical
0.3 key widths, `teh` → `the` scores 99%, `hellp` → `hello` 97%, and
`wrold` → `world` only 80% — below the threshold, uncorrected. Frequency is part
of the evidence, so the same slip on a rarer word survives. Arguably right, and
worth knowing before reading the threshold as a promise.

There are two levers, the threshold and the falloff — see D34, where the
falloff became a slider precisely because the threshold alone moves the boundary
without changing how abruptly it arrives. `DictionarySuggestionsTest` pins the
shape rather than the numbers: monotonic, spanning the threshold, and moving in
the direction each slider claims.

Known limits: `hte` used to be unable to reach `the`, the search being bucketed
by first letter (D23) — fixed in D35 for the cases the touches can evidence. And `ß` folds to `s` one character at a time here, so `strasse`
does not reach `Straße` cheaply enough to be corrected.

### D29 — Haptics: three states, per event

Off, light, strong. Not a switch, because "on" means something different on
every phone, and because the difference between the two strengths is what makes
a correction distinguishable from a keypress by feel alone.

**And two of them, one per event**, once the route was finally working and the
levels could be judged. A keypress tick and a correction knock are not the same
message: the first is texture, hundreds a minute, and plenty of people want none
of it; the second is *news* — a word was changed unasked and the undo window is
open. Wanting silence while typing and a firm knock on a replacement is an
entirely coherent position, and one shared control could not express it.

The defaults keep this decision's original claim: light for keys, strong for
corrections, so the two do not feel the same unless someone deliberately makes
them. Splitting the setting reads the old single value as a fallback, so nobody
who had already chosen Strong found themselves reset.

A keypress is one tick, as short as the hardware will honour, since it happens
hundreds of times a minute and anything longer is a buzz. An applied correction
is **two**: the keyboard did something unasked, and that is worth noticing while
the thumb is still moving — it is the only signal that D14's undo window is
open and about to close.

**Amended twice, and still not felt.** The first round's two causes, both worth
writing down because both are easy to repeat:

- *"As short as the hardware will honour"* was read as twelve milliseconds. A
  linear resonant actuator needs longer than that to spin up, so the request was
  honoured and felt like nothing. Where the platform offers a **predefined**
  effect — `EFFECT_TICK`, `EFFECT_HEAVY_CLICK`, `EFFECT_DOUBLE_CLICK` — that is
  used instead, because those are tuned per device by whoever knows the motor;
  the hand-rolled pulses are only the fallback, and they are 20ms and 40ms now.
- **A vibration with no stated purpose can be dropped.** Android routes haptics
  by usage, and an untagged request competes with ringer and notification
  settings. Every call now carries `VibrationAttributes.USAGE_TOUCH`, and the
  light setting asks the *view* for `KEYBOARD_TAP` first, which is the path
  every other keyboard on the phone takes.

If the phone reports no vibrator at all, the settings screen says so rather than
leaving three buttons that quietly do nothing.

**That was not it either**, and the second round changed the approach rather
than the constants. The reason this cannot be fixed by reasoning is structural:
**nothing in the Android haptics API reports back**. `vibrate` returns `Unit`.
The one call that returns a boolean, `performHapticFeedback`, is telling you
whether the *view* accepted the request, not whether the motor moved. And there
are at least four independent gates between the request and the hardware:

1. the app's `VIBRATE` permission,
2. the system-wide touch-feedback switch,
3. the per-usage intensity slider, which can scale touch haptics to zero,
4. whether the device actually implements the effect being asked for —
   `createPredefined` for an unsupported effect is allowed to do nothing, which
   means the first round's "use the tuned vendor effect" fix could be *worse*
   than the plain pulse it replaced.

A shut gate is indistinguishable from a dead motor from inside the process. So
the keyboard stops guessing and does two things instead. It **prints what the
phone will admit to** — motor present, amplitude control, touch feedback on or
off, intensity, per-effect support — because the combination usually names the
culprit outright. And it offers **each route as its own button**, since the only
instrument that can actually detect a vibration is a finger:

| Route | What it asks |
| --- | --- |
| Automatic | view feedback, then predefined, then a pulse |
| System keyboard tap | `performHapticFeedback(KEYBOARD_TAP)` |
| Built-in effect | `createPredefined(EFFECT_TICK)` |
| Plain pulse | `createOneShot`, tagged `USAGE_TOUCH` |
| Insistent | `createOneShot`, tagged `USAGE_ALARM` |

Whichever is felt becomes the setting. **Insistent is the diagnostic**: alarm
haptics are not scaled by the touch-feedback slider, so if that one is felt and
the others are not, the motor is fine and a system setting is off — which is a
sentence the keyboard can then say to the user instead of buzzing at them.

The amplitudes went up again as well, to 160 and 255 out of 255. Asking for half
power on a phone that is already scaling the request down is asking for nothing.

**The answer, on the phone: only Insistent.** Which settles it — the motor is
fine, the code was always fine, and this phone has touch feedback muted at the
system level. Every route tagged as touch feedback was being scaled to zero on
its way to the hardware, silently, with no error and nothing to read from inside
the process. Two rounds of tuning constants were spent on a switch in a different
app.

So the settings screen now leads with the *conclusion* rather than the readings:
the motor works, touch feedback is off system-wide, either turn it back on or
keep Insistent — and a warning that alarm-strength vibration can still be
suppressed by Do Not Disturb. Automatic deliberately does **not** escalate to the
alarm route on its own. Someone who muted touch feedback on purpose should not
be buzzed at by a keyboard whose haptics default to on; the escape hatch is
there, it is discoverable, and taking it should be a decision.

**A crash came out of this round, and the mechanism is worth keeping.** The
service held a placeholder `Haptics(this, OFF)` in a *field initialiser*. That
runs during `Service` construction, before `attachBaseContext`, so the context
has no base and `getSystemService` on it is a null dereference. It had survived
only by accident: the old lookup's first branch returned null for `OFF` without
touching the context, and `OFF` was exactly what the placeholder passed.
Rewriting the lookup deleted that branch and turned the same line into a crash
on every launch — of the keyboard, and of the settings screen too, because
opening it starts the IME in the same process.

Two fixes, because one of them is only a fix and the other removes the class:
the vibrator lookup is `by lazy`, so no level and no caller can make construction
touch the system; and the service holds `Haptics?` rather than a placeholder,
because an object that merely *happens* not to ask its context anything is one
refactor away from this exact crash.

**And then the route setting turned out to be written and never read.** The test
buttons named their route explicitly, so they worked and proved the mechanism;
the keyboard and the level buttons both constructed `Haptics` without one and
silently took the `AUTO` default — which on this phone is the one thing that
does not work. So the diagnosis was right, the fix was in the code, and none of
it reached the keys.

The constructor parameter no longer has a default, and `Haptics.fromPrefs` reads
both settings together. A parameter that can be forgotten will be; the type
system is the only thing here that reliably remembers.

### D30 — The timings are settings

Every duration in the keyboard is now a slider: the long-press delay on a key
and on a suggestion, how long backspace waits before repeating and how fast it
then goes, the double-tap window, the correction flash.

They are here for the same reason the suggestion size is (D25). D5 already said
the long-press delay "must be tuned rather than inherited"; three rounds of
tuning-by-guess later, the honest version of that is that a hold which feels
deliberate to one thumb is a stutter to another, and none of it is decidable
from a laptop. The views read them once when they are built, and a settings
revision counter tells the service to build them again.

The flash is in that list, and after two device rounds it is **five** sliders
rather than one, over a preview that replays the animation as they move:

| Slider | What it changes |
| --- | --- |
| Duration | how long the whole rise takes; zero switches it off |
| Strength | peak opacity, 0–255 |
| Hold | how much of the rise runs at full strength before fading |
| Reach | how far up the keys the front climbs |
| Tail | hard edge at 0, fading all the way back to the space bar at 95 |

The defaults moved with them: 650ms rather than 420, full opacity rather than
150 then 230, holding for 60% of the rise rather than fading throughout.

The **preview** is the part worth arguing for. Twice now the answer from the
phone has been "still too subtle", and each round cost a constant edit, a
rebuild, an install, and a deliberately misspelled word to trigger the thing
being judged. A `FlashPreviewView` draws a sketch of the bottom of the keyboard
and runs the identical `CorrectionFlash`, so the number is chosen by looking —
the same argument D25 makes for the suggestion-size preview, which was settled
in one round after three without.

### D33 — The strip's purple is the auto-correction decision, not a lookalike

Purple in the strip means **pressing space right now would substitute this
word**. Not "this candidate scores well".

It has been three things. First a hardcoded half of the matching mass, when
there was no auto-replace for it to speak for. Then the same *threshold* as the
auto-correction (D28), which was closer but still two calculations that could
disagree — the strip's a unigram share among completions, the corrector's a
touch-weighted share with its own gates. Two measurements sharing one number is
not the same as one measurement, and the only way to find out which one was
right was to press space and see.

Now there is one decision. After every keystroke the service asks the corrector
what it would do with the word as it stands, gates and threshold included, and
keeps the answer. The strip paints that word purple; the space bar applies that
same stored answer rather than recomputing. They cannot come apart, and space
does no work it has not already shown you.

Three consequences fall out, and all three are improvements:

- **A confident completion is no longer purple.** `hel` → `hello` may hold
  almost all the mass, but space does not turn `hel` into `hello`; it ends the
  word. The old rule shouted about that constantly.
- **The correction is shown even when the ranker did not offer it.** Ranking and
  correcting are different questions and occasionally disagree about what is
  even a candidate. It goes in front. A warning that is sometimes invisible is
  not a warning.
- **One correction query per keystroke** instead of one per space. Measured at
  1.5ms on a laptop (D28) against a `suggest()` call that is already more
  expensive, but it is the second thing in this keyboard with a latency budget
  and it has not been timed on the phone either.

### D31 — Punctuation takes back the space a suggestion inserted

Accepting a suggestion finishes the word and puts a space after it (D21), which
is a guess about what comes next. Two keys are entitled to disagree with that
guess, and they are the two that follow a finished word:

- **Space**, which turns it into a full stop — that is D6's double-space
  gesture, already built.
- **Punctuation**, which takes it away entirely. Tapping `,` after accepting
  `Haus` should give `Haus,` and not `Haus ,`.

One flag serves both, on `SpaceGesture`: the last interaction was a suggestion
acceptance that inserted a space, and any other input clears it. Two flags with
the same lifecycle would only be two things to keep in step.

Which marks hug is a list, not a rule: `, . ! ? ; : ' ’ ) ] } …`. Closing
brackets and quotes hug and opening ones do not, and the apostrophe hugs because
the case it exists for is `don` + `'` + `t` (D27) rather than a quotation. The
field is asked what is actually in front of the cursor before anything is
deleted, because between our commit and this keystroke the app may have done
anything at all.

The apostrophe has a second consequence. It is a *word* character (D27), so
retracting the space in front of one does not end a word, it joins the mark to
the accepted suggestion — `let` + `'` is now the single word `let'`, and the
tracked word was emptied when the suggestion was accepted. So a retraction is
treated as one of D23's cursor-jump cases and the word is read back from the
field. One IPC round trip, on a keystroke that happens a few times a paragraph.

### D32 — The other European accents, behind the German ones

Long-press alternates are a row, and until now every key offered at most one.
The rest of the row was free, so the accents of the neighbouring languages go
there: `é è ê ë` on `e`, `á à â å ã` after `ä` on `a`, `ç č ć` on `c`, `ž ź ż`
on `z`, `ł` after `9` on `l`, `ñ` after `!` on `n`, and so on.

**The first entry is untouched, and that is the whole design.** It is what is
drawn small in the corner of the key, and the popup opens with it selected, so
holding and releasing without sliding still gives it. The German umlaut keeps
`a o u s` (D17), the digit keeps every key that had one (D17 again — otherwise
"every digit is one hold away" quietly becomes "one hold and a slide away"), and
the punctuation keeps `v b n m` (D6). Everything new is reachable only by
sliding, which is to say only on purpose.

Six is the ceiling, enforced by test: the popup is one row of cells clamped to
the screen width, and beyond six the far end is unreachable on a phone.

The folding table (D22) grew to match, including for characters no German or
English word contains — `ø ł ž`. It costs a line each, it means a name typed
with its accents still finds the personal-store entry typed without them, and it
cannot disturb the wordlist sort order because none of them occur there.

### D34 — Contractions, and the falloff as a second slider

Two halves of the same complaint: the same typo is corrected only sometimes, and
`dont` is never corrected at all.

**`dont` was never going to work, because `don't` was not in the dictionary.**
None of them were. OpenSubtitles tokenises on the apostrophe, so the corpus has
`don` and a separate `'t` and no contraction ever reaches the frequency list as
one word — which is why the shipped English list had exactly zero apostrophes in
35,481 entries, and why `don` sat there with 4.16 million occurrences, six tenths
of a percent of the corpus, for a verb nobody uses. That count was `don't` filed
under the wrong key.

Both halves survive, so the mass can be put back. For each apostrophe suffix, its
total is divided among the stems that can take it, in proportion to how often
each stem occurs:

```
count(X'Y) = total(Y) × count(X) / Σ count over stems of Y
```

Exact where the stem is not a word on its own — `didn`, `isn`, `wouldn` occur
only as contraction stems and take their whole count with them. An estimate
where the stem is also a word: nothing can separate the modal `can` from the
front of `can't`. The results are plausible enough to trust for ranking — `I'm`
0.60% of the corpus, `don't` 0.45%, `can't` 0.41% — and the allocation is
**subtracted from the stem**, which is what drops `don` to 914k. That is also
where the estimate's error goes: an over-allocated `can't` leaves `can` light.

**Possessives are not shipped.** The dictionary holds 29,467 of them against a
few dozen contractions and cannot tell `it's` from `aardvark's`. Shipping them
all would nearly double the English list for forms the typist can already
produce by typing the apostrophe. The `'s` contractions that *are* shipped are
restricted to pronouns and interrogatives — closed word classes where `'s`
contracts *is* or *has* — which is a linguistic fact rather than a judgement,
and the frequencies still come from the corpus. 74 entries in total. German gets
none: its spelling dictionary has no apostrophe words at all, so `geht's` stays
a matter for D27's suggestion-side rule.

**A missing apostrophe is now a cheap edit**, 0.15 against a full-price 1.0 for
any other insertion. The argument is D5's, applied to the other character this
keyboard makes expensive: `'` is a long-press on `v`, so leaving it out is a
decision about effort rather than a mistake about spelling, and D6 said from the
start that correction was expected to place apostrophes unprompted. Dearer than
the accent's 0.1 on purpose — a skipped umlaut still types a letter, so there is
a touch to reason about, while a skipped apostrophe leaves no evidence at all.

Measured against the shipped lists at the default threshold: `dont` → `don't`
99.8%, `didnt` 99.5%, `youre` 99.0%, `doesnt` 99.0%, `couldnt` 98.0%, `ive` →
`I've` 99.4%. The `'s` set lands lower — `thats` 92%, `theres` 91%, `isnt` 90% —
because their frequencies are the systematically underestimated ones. `wont`,
`cant` and `ill` are not touched at all: they are real words, and D28's hard gate
says a word spelled exactly right is never replaced.

**The falloff is the second slider**, because the threshold alone could not
answer "this doesn't feel right yet". The threshold sets how sure the keyboard
must be; the falloff sets how quickly it stops being sure as the thumb moves
away from the key the word needed. Same word, same typo, varying only the
falloff:

| Falloff | 0.2 key widths | 0.4 | 0.6 | 0.8 |
| --- | --- | --- | --- | --- |
| 3 | 97% | 96% | 93% | 92% |
| 7 (default) | 97% | 88% | 64% | 31% |
| 14 | 88% | 31% | 3% | 0% |

Low is forgiving and nearly flat; high corrects only a graze. The scorer takes
it as a `var` rather than a constructor argument — reloading thirty-five thousand
words because a slider moved would be absurd — pushed in when the input view is
rebuilt and again when the dictionaries finish loading, since either can be the
later of the two.

### D35 — A mistyped first letter, without scanning the dictionary

The correction scan is bucketed by first letter, which for a long time meant a
mistyped first letter was simply unreachable: `xontinue` found nothing at all,
because nothing beginning with `x` is within a slip of it.

The obvious fix is to stop bucketing, or to add a second index by *last* letter.
Both were measured against the shipped lists rather than guessed at, and the
surprise is that the last-letter index is not expensive — the length filter and
the budget early-exit prune far harder than bucket sizes suggest:

| Typed | First-letter bucket | Whole dictionary | Last-letter bucket |
| --- | --- | --- | --- |
| `xontinue` | 0.06 ms, nothing | 75 ms | 2.7 ms → `continue` |
| `zomorrow` | 1.09 ms, nothing | 15 ms | 0.03 ms → `tomorrow` |
| `hte` | 0.16 ms → `he` | 1.7 ms | 0.33 ms → `the` |

**It was rejected anyway, and for a better reason than cost.** Look at what the
found candidates score: `zomorrow` reaches `tomorrow` and is rated 0.40, `vonnte`
reaches `könnte` at 0.22. A first letter the thumb was nowhere near is a
full-price substitution, and a full-price substitution loses to the unknown-word
prior. The index would find the word and the scorer would refuse it — paying 2.7
ms on every keystroke (D33) to arrive at the same answer.

So the buckets are widened instead, from evidence already in hand:

- **The neighbours of the first press**, when the touch was within half a key of
  one. That threshold is not arbitrary: measured against a real key geometry, a
  press 30% of the way from `x` towards `c` reaches `continue` at 0.82, at 20%
  it is 0.53, and at dead centre 0.06. Half a key is roughly where a candidate
  stops being able to clear any threshold worth having, so the cases excluded
  are exactly the ones that would have been refused.
- **The second letter typed**, because `hte` is not a mistyped `t` but a
  transposed one, and the intended first letter is sitting right there.

Four buckets at most. Worst case 2–3 ms against 0.3–1.1 ms before, and — the
thing that made a single pass safe rather than needing a two-pass fallback — it
does not dilute the existing answers: the extra candidates are all far enough
away that their share of the mass is invisible. `hanen` → `haben` goes from
0.794 to 0.782.

What remains out of reach is a first letter typed dead centre on the wrong key,
where there is genuinely no evidence: `xontinue` scores 0.06 whether or not the
candidate is found. **The lever for that is the falloff slider** (D34) rather
than a new index — at a falloff of 3 the same correction scores 0.83 — which is
the right place for it, because "correct a word on the strength of the letters
alone" is a preference and not a fact.

### D36 — The first alternate goes under the finger

A long-press popup lays its cells out **from the key outwards in one direction**,
with the first alternate centred on the key being held.

The old rule centred the whole row on the key, and that is fine for one cell and
wrong for every number above it: with four alternates the first one lands a cell
and a half to the left of the thumb, so the smallest drift selects something
else. Holding `u` for `ü` gave `ù`. Holding `n` for `!` gave `ñ`.

This is a regression D32 caused and D17 predicted the shape of. Every key had one
alternate until the accents were added, so "centred on the key" and "under the
finger" were the same position and nothing distinguished them. `a` went on
working afterwards purely by luck: it sits near the left edge, so clamping the
row onto the screen shoved cell zero back under the thumb — which is why `ä` was
reported fine while `ü` and `ö` were not.

The rule now:

- **Cell zero is centred on the key.** It is the one drawn in the corner, the one
  a plain hold commits, and under D17 and D32 the one the key is understood to
  carry. Nothing else may occupy the position the finger is already in.
- **The rest extend one way**, towards whichever side has more room — leftwards
  for keys on the right of the board, rightwards for those on the left. That is
  the same answer as picking a direction per key, without a table to maintain.
- **A row that will not fit is shifted bodily**, never re-ordered, because
  re-ordering is how cell zero moves out from under the finger again.

Index order is consequently not screen order — a leftward row has cell zero as
its rightmost rectangle — which costs nothing because the hit test walks the
rectangles rather than dividing by width. It buys something, too: a finger that
drifts off the far side of cell zero stays on cell zero.

The arithmetic is in `AlternatePopup`, out of the view and tested, because this
was wrong for two releases in a way that reads perfectly plausibly.

### D37 — The strip and the space bar are one search

`candidatesFor(word, touches)` returns both what the strip should show and what
space would substitute, from a single scan.

They had drifted into two searches with different reach. The strip walked one
first-letter bucket with a whole-number edit distance that knew nothing about
the touches; the correction walked several (D35) with the spatial distance and a
cheap apostrophe (D34). The visible result was a strip that could not offer
`don't` for `dont` or `continue` for `xontinue` — words the space bar was about
to insert, missing from the three slots that exist to show them. D33 had already
patched over the worst of it by prepending the correction to the strip, which
worked and was a sign that the split was wrong.

Since D33 made the correction run on every keystroke, it was also twice the
work. One call halves it.

Three constants collapsed on the way. The strip refused to correct words under
four letters and the corrector under three, for no reason anyone recorded —
they were written months apart. And the strip's correction penalty was `50` for
one edit and `2500` for two, which is `50^distance`, which is
`exp(−ln(50) × distance)` — the same exponential in the scorer, at a different
rate. It is now the one rate, the falloff slider.

**Two-character prefixes** are what made the wider reach affordable. A
transposed first pair means the word begins with those two letters swapped, so
`hte` needs the words beginning `th`, not every word beginning `t`. A
substituted first letter means the word begins with the neighbour and then the
second letter typed, so `xontinue` needs `co`. Both assume the *other* of the
first two letters came out right, which is the difference between chasing one
slip and chasing two.

That distinction is worth a great deal in German, where `e` is the second letter
of half the language: as a whole bucket the transposition slice cost 53ms, as a
two-character prefix it is a rounding error. The whole query — strip and
correction together, warm — now measures 0.2–1.5ms against the shipped lists,
where the two separate searches were 4–8ms.

`EditDistance` was deleted with its tests. It had one caller, and the caller now
uses the spatial version.

**What the source may not decide** stays with the service: the confidence
threshold, and D28's rule that a word the keyboard did not watch being typed is
never replaced. `WordInProgress.fullTouches` hands the search untouched
placeholders when the touches are unavailable, which is right for *offering* a
candidate and never right for replacing one — so the service checks the real
touches before acting.

### D38 — The globe key becomes a trail switch, and `h` steers by line

Two changes to what the keys do, and one of them closes a leak.

**The globe is gone.** The system draws its own IME switcher — in the navigation
bar on this phone — so a second one cost a key position to duplicate something
already on screen. `switchToNextInputMethod` goes with it.

**Its position now toggles the keypress trail** (D19), showing `◉` or `○` for
what it will do rather than what it is. It is the one setting that belongs on a
key rather than in the settings screen, because the moment it matters is the
moment somebody is standing behind you, and that is not a moment for three taps
and a scroll.

Which is the leak. The trail draws the last five keys pressed in purple, and
until now it did that **in password fields too** — `isPassword` was computed at
focus and used only to decide whether to capitalise. Five characters of a
password, held on the keyboard until the next keystroke pushes them along, in
the one place where the whole design says nothing may be remembered.

When the trail is off the service **does not record it at all**, rather than
recording it and declining to draw — what is in that list is a description of
what was typed, and the point of switching it off in a hurry is that the
description should not exist.

**The first attempt made the field a veto, and that was wrong.** A password
field forced the trail off whatever the toggle said, which meant pressing the
key there did nothing at all: it flipped a flag that was then ANDed away. A
control that silently does nothing is worse than one that is absent, and this
one was inert in precisely the field where somebody might most want to press it.

The trail is an **accessibility feature** before it is a decoration. It says what
was just typed, which is worth most to someone who cannot easily check by reading
the field — and a password box, where the text comes back as dots, is the hardest
field of all to verify by looking. Refusing to show it there overrules the person
who needs it in order to protect them from a threat they can see and the keyboard
cannot: whether anybody is actually standing behind them.

So the field selects *which* answer is remembered rather than overriding it.
Ordinary fields and password fields keep separate settings; the defaults differ —
on and off — and neither is a veto. The toggle writes to whichever applies to
the field in front of it, so it always does something, and turning it on for
passwords does not quietly change what ordinary fields do.

**`h` steers the cursor by line.** The same gesture as the space bar's, turned
ninety degrees: drag up or down and the caret follows, one line per 22dp. `h`
because it is the middle of the home row, reachable with either thumb without
looking, and because it carries no long-press to race with the drag.

Two guards the space bar does not need. The travel has to be **mostly vertical
and further** — 18dp against the space bar's 10 — because the space bar cannot
be typed by accident and a letter can, and a tap that drifts must stay a tap.

And the field has to say it holds more than one line. That is the same
focus-escape hazard the space-bar drag already guards against, with a worse
failure mode: a DPAD event the field cannot consume is not swallowed, it falls
through to focus navigation, focus leaves, and the keyboard disappears
mid-sentence. Asking "is there a character to the left" answers it for
horizontal movement; nothing so cheap answers "is there a line above", because a
wrapped line contains no character that says so.

So the honest statement of the limit: on the first line of a genuine multi-line
field with something focusable above it, this can still dismiss the keyboard.
Recoverable by tapping the field, and cheaper than the alternative, which is
reading the whole text back and counting newlines on every step of a drag.

### D39 — Swipe typing, and what it costs `h`

**Supersedes D7's "not now".** A word traced in one stroke, decoded against the
same two dictionaries and committed on lift.

**The decoder is shape matching and nothing cleverer.** Both the finger's path
and each candidate word's *ideal* path — the polyline through its key centres —
are resampled to 32 points spaced evenly by distance, and the cost is the mean
gap between corresponding points, in key widths. Resampling by distance rather
than time is what throws away how fast the finger moved and keeps the shape.

Comparing path against path rather than path against *letters* is the one
decision in the decoder that matters. Swiping `hello` crosses `r`, `t`, `y`,
`d`, `f`, `g` and `j` on the way, and any scheme that matches touch points
against candidate letters has to explain away every one of them. Path against
path does not, because the ideal path travels over those keys too.

**The search is bounded by the two things a stroke says clearly.** A finger
comes down and lifts deliberately, so the first and last letters are near
certain. That pair is a slice of about three hundred words across both
dictionaries — measured, out of seventy thousand. The last letter is one
character comparison and throws away most of a first-letter bucket; the length
of the journey is one pass over the word and throws away most of the rest; only
what survives both is worth the full comparison. Endpoints admit their close
neighbours too, capped at three, because unlike everywhere else in the search
that generosity is *quadratic* — three first letters and three last ones is nine
slices.

**Measured on the shipped wordlists**, the 800 commonest words of the two
languages:

| trace | top-1 | top-3 |
|---|---|---|
| perfect | 97% | 100% |
| realistic (rounded corners, thumb wander) | 97% | 100% |
| sloppy | 96% | 100% |
| endpoints half a key off | 94% | 100% |

About 265µs per stroke, warm. This runs once per word rather than once per
keystroke, so it has a whole word's worth of typing to hide in.

**Every miss is one of two permanent ambiguities, and the right word is always
rank two.** A doubled letter is one place on the keyboard — the finger does not
move for the second `l` of `hello` — so `das` and `dass` are traced along
literally the same path. Folding does the same to `wurde` and `würde`, since no
accent can be swiped at all (D5 puts them on a long-press). Nothing separates
these but how common each is, the commoner wins, and it is right rather more
often than not. What makes that survivable is that **the runners-up stay in the
strip**, one tap away, and they displace ordinary completions while they last:
after swiping `das`, completions of `das` are of no use to anybody and `dass` is
the only thing worth offering.

**A stroke commits without a trailing space and stays the word in progress.**
That is what makes everything after it work with no special cases: tapping an
alternate replaces it exactly the way tapping a suggestion always has, typing
`s` after swiping `dog` gives `dogs`, and backspace eats it a letter at a time.
The next stroke puts the space in front of itself. And a half-typed word in
front of a stroke is never swallowed — it gets that separating space — because
it is far likelier to be wanted than to be a mistake, and under D14 a
replacement that cannot be undone is not one to make quietly.

The alternates are tied to the committed word rather than cleared by hand.
Typing on, backspacing, pressing space, moving the cursor, changing field: every
one of them changes the word in progress and retires the alternates by doing so.
One invariant instead of a list of places to remember.

**No swiping where there are no suggestions.** A password box has no dictionary
for a stroke to be decoded against, so the gesture is not recorded there at all
rather than recorded and refused. A gesture that visibly draws itself across the
keys and then does nothing is worse than one that is absent — and the ribbon
would be a picture of a password.

**Entry is a crossing, not a distance.** A drag off a letter becomes a stroke
when it reaches a *different letter key*. Distance alone would not do: a tap
that drifts must stay a tap, and a lazy thumb drifts a surprising way without
meaning to leave the key. Requiring a real crossing also means the gesture
cannot fire on the modifiers, none of which are letters, so the space bar,
shift and backspace keep the drags D20 and D24 gave them. Recording starts at
touch-down regardless, before anything has been decided, because by the time a
stroke has proved itself the first leg has already happened — and the first leg
carries the first letter, which is half of what bounds the search.

**Backspace after a swipe removes the whole word.** A stroke is one act, so
undoing it is one act. Taking a letter at a time off a word nobody typed a
letter of is busywork — the word was wrong as a whole, and what happens next is
always either swiping it again or typing it out. Only while the swiped word is
still exactly what stands in front of the cursor, which is the same invariant
that keeps the alternates in the strip; after that, backspace is a backspace.

**The stroke stays on screen until the next press, with both ends ringed.**
A swipe that produces the wrong word is otherwise impossible to argue with: by
the time the word appears the evidence for how it was chosen has gone. The two
rings are not decoration — the first and last letters *bound the entire search*,
so if a ring is sitting on the wrong key that is the whole explanation. The
start is hollow and the end filled, so the direction is readable from the still
picture.

### D39a — What the first week of swiping actually found

Three things, and they are worth separating because only two of them were bugs.

**Batched touch samples were being thrown away.** Android reports touches faster
than it draws frames, so one `ACTION_MOVE` carries every sample since the last
one, with all but the newest in the historical arrays. Reading only the current
position samples the stroke at frame rate. Measured, on otherwise identical
strokes: 97% top-1 at touch rate, 92% at one report per frame, 85% at one per
frame through a fast flick. The corners are the first thing to go, and the
corners are the letters. This is a genuinely nasty bug to notice from the
outside, because slow careful strokes decode fine either way — it makes the
keyboard look worst at the words you type fastest, which are the ones you know
best.

**Three of the four prefilters were too tight**, and a prefilter is the worst
place to be wrong. An outranked word still sits in the strip one tap away; a
word cut by a gate is gone before anything scores it, so it is not offered, not
a runner-up, and indistinguishable from the keyboard not knowing the word at
all. Measuring the *correct* word's cost against each gate showed hurried
strokes reaching 1.16 at the 99th percentile against a ceiling of 1.0, and hard
corner-cutting putting the length ratio past its bound. All four were loosened,
with `GestureGateTest` now asserting that no gate ever rejects the right word
and that hurried strokes keep a fifth of the cost ceiling in reserve. Cost:
decode went from about 340µs to about 570µs, which is nothing once per word.

**And one thing that was not found.** After both fixes the synthetic accuracy is
96–97% top-1 and 100% top-3, evenly across the two languages, with no gate
rejecting anything — and that does not match the reported experience of frequent
wrong words. Ruled out along the way: the keyboard's proportions (the fixture
now uses the real 52dp rows and 3dp gaps, which changed nothing), and the theory
that German suffers more from the doubled-letter and umlaut collisions (it has
17% doubled letters and 8% accents against English's 14% and 0%, and still
scores 96% against 97%).

What is left is the finger model. The synthetic thumb rounds corners by a
fillet, wanders on two slow sine waves and is reported at a fixed spacing; a
real one does none of those things exactly, and the accuracy table is only ever
as good as that model. This has already been wrong twice — white noise per
sample is a sawtooth rather than a thumb, and pulling vertices toward the chord
of their neighbours deletes corners rather than cutting them — and both times it
made the decoder look far worse than it was, so it is quite capable of being
wrong in the flattering direction too. **The next move is evidence rather than
another hypothesis**, which is what the ringed, persistent stroke is for.

**What it costs `h`.** D38 put line steering on a plain vertical drag off `h`,
and a plain drag off a letter is now a word. The two cannot be separated by
direction: `h` to `b` is down and to the left, which is exactly what steering
looks like. So they are separated by what came *before* — **tap `h`, then press
again and drag**. No stroke ever begins that way, because a stroke begins with a
finger landing on a key it has not just left.

The arming tap types an `h` nobody wanted, so it is taken back when the drag
starts. **Only on the drag**: a plain double tap still types both letters, so
`withhold` and `Rohheit` cost nothing. The alternative considered was hanging
the gesture on a letter that never doubles, but no letter on the home row
qualifies and being in the middle of the home row is the whole reason `h` was
picked. And the retraction asks the field what is actually in front of the
cursor rather than assuming, because between the tap and the drag the app may
have done anything.

### D39b — What the phone said next

Two strokes from real use, and they turned out to be two different problems.

**`learning`, decoded as `laughing`.** Both begin `l`, end `g`, are the right
length, and `laughing` is six times the commoner — so the geometry had to
overturn that and could not. It never charged `laughing` for the plain fact
that the finger went up to `e` and out to `r`, which its route passes nowhere
near.

Comparing the two paths point for point asks the wrong question twice. It
charges full price for the *route between* two letters, when how a finger chose
to travel is not evidence about anything. And it couples by position, so a
loop — which is what a reversal looks like at speed — shifts every later sample
against its counterpart. Measured: as a loop grows, the cost of the **correct**
word climbs from 0.00 to 0.79 while the wrong one, already misaligned and with
nothing left to lose, sits flat around 0.9. It punished the word it was meant
to find.

Two alignment-free terms replaced it, and they are converses. **Visit** asks
whether the finger came near each of the word's letters, in order. **Coverage**
asks whether the word's route explains where the finger actually went. Neither
survives alone — a short word satisfies the first trivially, a rambling one the
second — and together they are hard to cheat. Both are root-mean-square rather
than mean, because a word is wrong if *any* letter went unvisited and averaging
buried exactly that: `leaving` misses `v` by two key widths, and spread across
seven letters it vanished.

Letters are measured to the nearest point *on* the stroke rather than the
nearest sample, which removed a floor of half a key that had nothing to do with
the typist, and `SAMPLES` rose to 48 because corner fidelity now matters where
it did not before. On the reported stroke `laughing` costs 1.12 against
`learning`'s 0.15.

**`swiping`, not offered at all — and that one was not geometry.** The decoder
had it right: 0.05 against `stopping`'s 0.49 and `selling`'s 1.16, the best fit
by a wide margin. `swiping` occurs **236 times in 675 million words** of film
subtitles. Its share of the corpus is *smaller than* [UNKNOWN_WORD_PRIOR], so
the keyboard rated "a word I have never heard of" as three times likelier than
the word itself, and no quality of trace could have rescued it. The frequency
data is film dialogue and the word is from the smartphone era.

So **rarity counts for less when swiping**: the score uses the square root of
the corpus weight rather than the weight. The justification is not the one word
it rescues but what a stroke *is* — a whole word's worth of geometric evidence,
where a typo correction works from one or two characters, so the shape has
earned the right to overrule the frequency table further than it may there. It
pays for itself on the corpus as well, lifting top-1 from 95% to 96% on
realistic traces and 93% to 95% on sloppy ones. The unknown-word prior is
raised to the same power, or it would be swamped and every stroke would come
back certain.

`swiping` still does not win, and after a second look that is not a shortfall
at all — it is **arithmetic**. `swiping` is `s w i p i n g` and `sweeping` is
`s w e p i n g`, and `w`, `e`, `i` and `p` all sit on the top row at the same
height: `w→i→p` and `w→e→p` are *the same straight line*, and the tails
`p→i→n→g` are identical. Same start, same corners, same end, same length. The
two words are one stroke, exactly, and no geometry will ever separate them —
they are `das` and `dass` again, in a less obvious costume.

So frequency decides, ten to one, and the loser is second in the strip. Teaching
the word with the personal key (D40) settles it for good, which is exactly what
D8 built that key for, and there is a test that says so.

**Corners count for more than straights**, and this is what fixed the stroke
above — a stroke I had twice mis-read, first as being about `sweeping` and then
as being about frequency. What actually came back was `seeing`, with `song` and
`strong` beside it, and the objection from the phone was exact: all three ignore
the corner at `w`, and `strong` additionally wants a back-and-forth over `r` and
`t` that the finger never made. In the middle of
a straight run the finger had to be *somewhere*, and where it was says almost
nothing about which word this is — any candidate running roughly that way
explains it. A corner is a deliberate change of direction, and fingers change
direction at letters, so a word that fails to account for a corner is failing to
account for the evidence. Coverage therefore weights each point of the stroke by
how sharply the finger turned there, measured across a window of a few samples
so the reading is a corner rather than digitiser noise.

Reconstructed from the screenshot and measured, the weighting does exactly what
was asked of it. `seeing` climbs from 0.66 to 1.08 as corners come to count for
more, `strong` from 0.82 to 1.00 and `song` from 0.71 to 0.79, while `swiping`
does not move from 0.05 — because its corners *are* the stroke's corners. All
three are now out of the strip entirely, and a test holds them there.

It pays on the corpus too: realistic traces 96% to 97%, sloppy 95% to 96%,
frame-rate sampled 93% to 94%.

What is left over is a three-way tie no geometry can break. `swiping`,
`sweeping` and `swooping` are `s w i p i n g`, `s w e p i n g` and
`s w o p i n g`, and `e`, `i`, `o` and `p` all sit on the top row at the same
height — so all three trace one identical polyline, to within half a percent.
Frequency orders them, the strip carries all three, and the personal key (D40)
settles it permanently. That is `das` and `dass` again in a less obvious
costume, and it is the right place for the argument to end.

**The cost of all this** was decode time: from about 0.3ms to about 3ms per
stroke. Once per word rather than once per keystroke, so there was room — but a
phone runs on a battery, and several hundred candidates each getting two
dynamic programmes is a poor way to discover that most of them start with the
wrong letters.

So the search sifts before it scores. **There are only twenty-six places a
letter can be**, so the distance from every key to the stroke is worked out once
and read off by every candidate. Ignoring the order the letters must come in can
only make the answer smaller, and coverage is never negative, so that gives a
genuine *floor* under a candidate's cost for the price of a table lookup per
letter — and a floor under the cost is a **ceiling on the score**. A candidate
whose best conceivable score is ten thousand times below the best conceivable
score going is not scored properly at all.

That took 3ms to 0.8ms with no measured accuracy change whatever. It is not
quite free of consequence, so the consequence is arranged to fall the right way:
the skipped candidates' most flattering possible scores go into the confidence
*divisor* rather than being dropped, which means the pruning can only ever make
the keyboard sound less sure than it is, never more. An optimisation that
quietly inflated confidence would be changing the answer, and D3 rests on that
number meaning something.

Loosening the threshold tenfold was tried and doubles the time for no accuracy
at all — including on the one coarse-sampling case that sits at 99% rather than
100% in the top three, which is therefore the model's doing and not the
pruning's.

### D39d — A key is a rectangle, and it is taller than it is wide

A second `swiping` stroke came back as `stopping`, and reconstructing it from
the screenshot pixel by pixel found two mistakes in the *unit*, not in the
model.

The finger came up off `s`, turned inside the **bottom** of the `w` key, and
set off right. Every letter of `stopping` sat within 0.35 of a key of the
stroke; every letter of `swiping` did too, except `w` at **0.71**. That single
number lost the word.

Neither half of it was the typist's fault:

- **The miss was almost entirely vertical**, and every cost here is quoted in
  key *widths* while a phone's keys are half again as tall as they are wide. A
  0.44-key-height error was billed as 0.66. Distances are now measured in key
  *units* — the vertical scaled by the aspect — so one unit is one key in
  either direction.
- **The corner was inside the `w` key.** The keyboard's own hit testing would
  call that a `w` without hesitating; only the decoder disagreed, because it
  measured to the centre of a key as though a key were a point. A key's own
  extent is now free.

Together they reverse the answer: `swiping` cost 0.627 against `stopping`'s
0.584, and now costs 0.251 against 0.281. The corpus agrees — sloppy traces
95% to 96%, the rest unmoved — and the pruning bound had to learn the same
free reach, or it would have started refusing candidates the full cost would
have accepted, which is the one thing a pruning step may never do. That cost
about half the speed won by the pruning: 0.8ms to 1.7ms, still a fifth of what
it was before any of it.

**And `swiping` still is not offered, which is now definitely not geometry.**
The shape ranks it first; frequency puts it fourth. It occurs 236 times against
`stopping`'s 14,667, `song`'s 86,877 and `sweeping`'s 2,435 — sixty, three
hundred and ten times over — and after the square root that is still a factor
of three to seven, where the cost advantage is worth about one and a half. No
honest weighting of a corpus that has barely heard the word will put it top.
The personal key settles it in one hold, and that is the mechanism D8 exists
for rather than a consolation.

### D39e — The strip is the appeal, so order it by the finger

Two bugs in one screenshot, both about what the strip is *for*.

**The last slot was always empty.** A swipe commits its best candidate and
offers the rest (D39), and the ranking returned exactly three — so after the
commit took one there were two left and the third slot stood blank. The fourth
candidate was never worked out at all. It asks for one more than the strip
holds now.

**And two of the three slots were the same word**, `song` and `Song`. A finger
cannot express a capital, so those are one answer to a stroke; the runners-up
are folded case-insensitively, and D24's re-case gesture is how the other
casing is reached. Tapping keeps them distinct, because there the letters typed
already say something about the case.

**The third thing was not a bug so much as a wrong question.** The strip was
ordered the same way the commit is — shape weighed against frequency. But the
strip is only ever read *when the commit was wrong*, so ranking the rest by
frequency again asks the question that has just failed and answers it the same
way. On the observed stroke the four best fits were `swiping`, `sweeping`,
`swooping` and `stopping`, between 0.25 and 0.28, while the strip offered
`song` and `strong` at 0.51 and 0.56 — twice the misfit, on the strength of
being three hundred times commoner. Two of the three slots went to words that
plainly did not match the picture on the screen.

So the commit still weighs both, because for a first guess frequency deserves
its say. The runners-up are ordered by **how well they fit**, ties to the
commoner word. If the frequency table has had its turn and lost, what is left
to consult is the finger. Corpus top-3 stays at 100% and frame-rate sampling
improves two points.

**`swiping` is still not in the strip, and the reason has stopped being
interesting.** It ties `sweeping` and `swooping` exactly — all three trace one
polyline — and loses the tie-break by twelve occurrences in six hundred and
seventy-five million; `seeping`, one letter shorter, edges it on a
root-mean-square over fewer letters. It is a photo finish between words that
are the same stroke, and no principle decides it. The personal key does, in one
hold.

### D39c — The address bar is a search bar

`TYPE_TEXT_VARIATION_URI` was refused suggestions along with email addresses and
search filters, on the grounds that its contents are not prose. On a desktop
that is true. On a phone it is not: the address bar and the search bar are one
box, and Firefox's is the one people type most of their questions into.
Refusing to help there withholds suggestions from a great deal of ordinary
prose in order to avoid interfering with the occasional hand-typed URL — and an
address that matters usually arrives by paste, not by typing.

**The obvious hazard turns out to be self-limiting.** A correction only ever
fires on **space** (D28), and a space in the address bar is precisely the signal
that this is a search and not a hostname: nobody types a space inside a domain.
So the destructive half of the feature reaches the text only in the case where
it is wanted, and nothing has to detect which mode the box is in. That is worth
more than a mode detector would be, because it cannot be wrong.

Swiping follows suggestions (D39), so it returns here too, and that is the
larger part of the gain — a search is exactly the kind of throwaway prose a
swipe is for.

Email and filter fields keep their refusal: those really are not prose, and
neither doubles as anything else. And a browser that genuinely wants no help
can still say so with `NO_SUGGESTIONS`, which is checked before any of this and
is the app's decision rather than a guess made from a variation code.

### D39f — How much of a language a swipe cannot see, and why Dvorak is worse

`scripts/swipe-collisions.py`, run on the shipped wordlists. A word's shape is
the polyline through its letters' key centres, reduced: consecutive repeats
collapse, and a vertex lying *on* the line between its neighbours disappears.
Two words with the same reduced shape cannot be told apart by any decoder,
however good — only by how common they are.

| | QWERTY | Dvorak |
|---|---|---|
| English — typing that collides | 41.6% | 51.8% |
| English — **irreducible error** | **3.10%** | **4.47%** |
| German — typing that collides | 42.3% | 50.8% |
| German — **irreducible error** | **4.91%** | **7.82%** |

The irreducible error is the share of words a perfect decoder must still get
wrong, because all it can do with a collision is answer with whichever member
is commoner. About one word in thirty in English, one in twenty in German.

**Dvorak is markedly worse — half again as bad in German — and the reason is
that it is optimised for exactly the property that destroys a swipe.** Its
design puts the frequent letters on the home row: all five vowels together on
the left, the common consonants on the right. Letters sharing a row are
*collinear*, and a vertex on a straight line leaves no trace in the shape. So
Dvorak turns `in`/`ihn`/`ihnen` into one stroke, and `ein`/`einen`/`essen`/
`eben` into another. Minimising finger travel and maximising home-row use is
the same thing as flattening the shapes, and a swipe is nothing but shape.

The measurement is robust in the ways that could have made it an artifact. It
is unchanged across key aspect ratios from square to 1.8 — collinearity
survives scaling — and unchanged whether the rows are a uniform grid or
stretched to fill the width as this keyboard actually places them. The
collisions are within-row and doubled-letter, not a detail of placement.

Some things worth knowing beyond the totals:

- **`das`/`dass` alone is 0.61% of German typing**, an eighth of the whole
  German error. `the`/`there`/`these` is 0.60% of English.
- **5.35% of English typing cannot be swiped at all** — it is `a` and `I`,
  words of a single key. German's figure is 0.26%, having no common one-letter
  words.
- Case and accent collisions (`wurde`/`würde`) cost German a further 0.45% and
  English nothing, as it has neither.

Which puts a ceiling on the feature and says where the remaining work is. The
decoder is at 96–97% top-1 on synthetic traces against a 3–5% floor it cannot
go below, so **geometry is close to spent**. Getting past it needs context —
knowing that the word before was `ich` makes `das`/`dass` a decidable question
rather than a coin toss weighted by frequency. That is D10 and D12, and this
measurement is the argument for them.

### D41 — `i` is `I`, and `ivll` is `I'll`

Measured first, because the size of it decided the shape. `I` alone is
**20.7 million occurrences — the second commonest word in English**, after
`the`. With `I'm`, `I'll`, `I've` and `I'd` the I-forms are **4.01% of English
typing**.

And the keyboard offered nothing for any of them. Two separate reasons, neither
deliberate:

- **A single letter never reached the strip.** [MIN_PREFIX] wants two before it
  will guess, which is right for *completing* — one letter is not evidence of
  anything and its candidates are most of the alphabet's worth of words — but
  it also turned away the case question, which needs no completion at all.
- **And it was never corrected**, because `Lexicon.knowsExactly` ignores case on
  purpose (D5's reasoning: the typist decides case, `über` versus `uber` is the
  question it exists to answer) and so judged `i` perfectly well spelled.

**This is not a rule about capitals but about which casing the dictionaries
prefer**, which is the only honest way to ask it on a bilingual keyboard:
English has `I` and no lowercase form, German has a lowercase `i` (19,718) and
no capital. Their corpus shares settle it at better than two hundred to one, so
`i` corrects to `I` at 0.996 — **and the German `i` stays in the strip**, because
a keyboard choosing between two real words should show its working. Only single
letters: the same reasoning would capitalise every German noun on sight, `haus`
to `Haus`, which may well be right and is emphatically a separate decision.

**The apostrophe rule got much wider and much simpler.** It knew one pattern —
a word ending `vs` — and had to *build* the answer, because the corpus the
frequencies came from split `don't` into `don` and `t` before counting and the
wordlists carried no contractions. They carry seventy-four now, so the rule
collapses to: put an apostrophe where the `v` is and see whether that is a word.
Every position, not just the last, which is what reaches `ivll` and `ivm`. It
brings `donvt`, `youvre`, `wevre`, `ivve` and `ivd` with it, all above 0.99.

**The old rule stays beside it**, and deleting it was a mistake caught by its
own tests. The lookup only knows the fixed contractions; `'s` is **productive** —
every English noun takes a possessive and every German verb takes the clipped
`es`, so `have's` and `geht's` are real and no wordlist will ever list them all.
One rule for the closed class, one for the open one.

Rejected on the way, and worth recording because both were reasonable:

- **Swipe up on a letter to capitalise it** only works on the top row. Below it,
  an upward swipe is already the start of a swiped word — `de`, `free`, `great`
  all begin by going up — and a rule that works on `i` but turns `s` into `se`
  is worse than no rule.
- **Tap, press again, swipe up**, the general version, is conflict-free but two
  touches with a timing constraint, where shift-then-letter is two touches
  without one. It is not faster than what already exists.

Doing it automatically costs no gesture, no discovery, and nothing to perform
four percent of the time.

### D42 — Sentences after the first one

Auto-capitalisation was applied **once**, when focus arrived, and never again.
The only thing that ever turned shift back on afterwards was the double-space
full stop — and since that gesture only ever writes `.`, while `?` and `!` are
reachable only by long-press, **every question and every exclamation was
followed by a lowercase letter.** So was every sentence whose full stop was
typed from the symbol layer rather than by double-tapping space.

Now the same question is asked after every edit that could have ended a
sentence. A sentence starts at the very beginning of a field, after a newline,
and after a sentence mark followed by a space.

Three details worth having decided:

- **Not on the mark itself.** `Hello.` with the cursor tight against the stop
  is still mid-sentence until a space says otherwise, or `e.g.` and `3.14`
  would fight it.
- **Only ever on.** Turning shift *off* would be second-guessing a press the
  typist made deliberately, and `consumeShift` already spends it on the next
  letter.
- **The field is only asked when the edit could plausibly have ended a
  sentence** — a space, a newline or a mark — which keeps an IPC round trip off
  every keystroke.

And the same bilingual quote trap as D41's: the mark can hide behind a closing
quote, and German closes a quotation with `“` where English opens one with it,
so every quote character counts as one to step over regardless of which side it
nominally belongs to.

### D40 — The personal key

**The strip could only offer to remember a word when it had a slot going
spare**, and that is exactly backwards. The moment you most need to add a word
is when the keyboard is *confident and wrong* — when what you typed is one slip
from three real words, so all three slots are full of them and there is no room
left to say "no, the thing I actually typed". A word the keyboard has never
heard of gets offered readily; a word it thinks it knows better than you cannot
be added at all.

Under D8 the personal store is the only thing that ever teaches this keyboard
anything, so the friction of adding a word sets the ceiling on how good it
becomes. A path that closes precisely when it is needed is not a path.

So the position next to the layer toggle — the globe's, then the trail
toggle's (D38) — becomes a **purple plus**, in the same purple as the trail and
the correction flash, because everything in this keyboard that means "the
keyboard knows something about your words" is that colour and this is the key
that decides what it knows.

**What gets remembered is whatever is between the spaces**, not what the word
tokeniser thinks a word is. Those are different questions and the first version
asked the wrong one. `WordInProgress` stops at the first character that is not
a letter, because suggestions and corrections are about words — so asking it to
remember `john@coonabibba.de` produced `de`. The things people deliberately put
in a personal store are frequently not words by that definition: addresses,
paths, phone numbers, hyphenated compounds. Whitespace is the delimiter the eye
uses and it is the right one here.

Framing punctuation is trimmed — a trailing comma or closing bracket belongs to
the sentence rather than to the thing — and quotes come off **both** ends,
because `“` closes a German quotation and opens an English one and D2 says both
are live in the same paragraph. **The full stop is deliberately kept**: German
abbreviates `z.B.`, `d.h.` and `usw.` with one and a domain is nothing but full
stops, so trimming would break far more than it fixed. The cost is remembering
a sentence's own stop when a word is learned after it, which the launcher can
undo.

**And learning had to be untangled from suggesting.** `learningAllowed` was
defined as `suggestionsAllowed` plus the no-personalized-learning flag, which
bundles two things that only look alike: whether the keyboard should volunteer
completions into a field, and whether the user may deliberately tell it to
remember something typed there. An email field refuses the first because
addresses are not prose — and so refused the second, making it impossible to
remember an email address while standing in the one field an email address is
typed into. Same for URIs and for search boxes carrying `NO_SUGGESTIONS`.

Under D8 nothing is ever absorbed silently; every write to the store is already
somebody asking for it. There is no case for second-guessing that request in a
field whose only sin is not containing sentences. Learning now refuses exactly
three things, each for a reason of its own: **passwords**, because a secret
written to a plain file is a secret no longer; **`IME_FLAG_NO_PERSONALIZED_LEARNING`**,
because the app has said not to and that is not optional; and **anything that is
not a text field**, which has nothing in it worth a place in a vocabulary.

This is the third time the same mistake has been caught in three decisions —
D38's inert toggle, D40's unreachable trail setting, and now this — and they
share a shape: **a capability switched off by a rule that was written for a
different question.** Worth naming, because the next one will look reasonable
too.

**One switch over both pictures of what was just typed.** There are now two:
the keypress trail (D19) and the stroke a swipe leaves on the keyboard (D39).
The stroke is the franker of the pair — the trail says which five keys were
pressed, while the stroke draws the whole word's shape across the board and
then stays there until the next press — so a switch that hid one and not the
other would not mean anything. Both now follow the same setting.

Recording is untouched by it. Unlike the trail, which D38 stops *writing down*
rather than merely stops drawing, a stroke's points are not a record kept for
later: they are how the word is worked out at all, and they are gone the moment
it is. What settles on screen is what the switch controls.

**And the switch needed somewhere to live.** Moving the toggle key into
password fields alone left the setting for every *other* field unreachable —
stuck on, with nothing anywhere to change it. It is a switch in the settings
screen now, which is where a preference that is not urgent belongs; the key
remains for the one case that *is* urgent, which is somebody standing behind
you while you type a password.

**A stroke never appears in a password field regardless**, because swiping is
switched off there entirely: without suggestions there is no dictionary to
decode a path against, so the gesture could produce nothing. That is a
functional limit rather than a policy one, and it is worth saying plainly
because it means the toggle's effect on strokes is invisible in exactly the
field the toggle is on.

**The trail toggle keeps the position in password fields, and only there.**
That was always where its argument lived — the moment somebody is standing
behind you — and D38's per-field settings already meant the two kinds of field
answered separately. Now they carry different keys. The two are the same width
in the same place, so nothing moves under the thumb when focus changes, which
is D16's rule applied to a swap it did not anticipate.

**Three things on one key**, because they are three points on one idea:

- **Tap** opens the quick menu: the handful of stored strings worth inserting
  whole rather than completing towards — an email address, a postcode, a name
  nobody spells right. These are ordinary personal-store words with a flag, not
  a second list, so a string cannot be on the menu without being a word the
  keyboard knows.
- **Hold** remembers the word in front of the cursor. This is the point of the
  key and the answer to the complaint above: it needs no free slot, no offer,
  and no particular state — just the word being there.
- **Double tap** opens the launcher screen, where the stored words are listed,
  tagged and removed.

The order those are tested in is the design. A second tap inside the window
always wins, *including the tap that closes the menu the first one opened* —
which is what makes "tap for the menu, double tap for settings" one motion
rather than two that fight. And a tap with nothing on the menu opens the
settings too rather than doing nothing, which is D38's lesson applied before it
could be relearned: a control that silently does nothing is worse than one that
is absent, and an empty menu means the settings screen is exactly where you
need to go.

**The quick menu is modal, and the long-press popup is not.** It opens on a
*release*, so by the time it is on screen the finger has gone and it has to
survive until a separate press picks something — where the alternates popup
lives and dies inside a single touch. That is why it is not the same mechanism
despite looking like one, and it is drawn as stacked rows rather than
side-by-side cells because addresses are not characters and a row of them would
be unreadable at any width a phone has.

**It scrolls, and it is sorted.** Only about four rows fit above the key, and
the first version simply dropped everything past that — a menu that silently
loses entries as it grows is worse than no menu, because it is the entries you
added most recently that vanish. So the rows live in a viewport with an offset
rather than each having a fixed rectangle: with scrolling there is no fixed
rectangle for a row to have, and computing the position from the offset in both
directions is what keeps drawing and hit testing from disagreeing.

Past the touch slop a drag becomes a scroll and the pressed row is *unselected*,
or letting go at the end of a drag would insert whatever the finger came to rest
on. And the scrollbar is drawn whenever there is anywhere to scroll to, because
a full menu and a menu with six more below the fold look identical otherwise —
nothing else on this keyboard scrolls, so there is no habit to fall back on.

Sorted by the same folded comparison the launcher screen lists everything with,
so a word is in the same place on both, and `Ärztin` sorts under A where it is
looked for rather than after Z where its code point puts it.

**The file format had to stay readable.** A quick entry is the word, a tab, and
a marker; a bare line is an ordinary word, which is every line of every file
written before this. The store also holds one list of entries rather than a
list of words plus a set of tags — two fields cannot be swapped together, and a
reader landing between the two writes would see a word tagged quick that the
store does not have.

### D43 — The unknown-word prior stops being a constant

`hsnging` was not corrected to `hanging`, and `hanging` was the **only**
candidate on the table. It scored 0.045 against a threshold of 0.90.

Nothing was wrong with the correction. What it had to beat was wrong.

D3 makes confidence a share of everything on the table, and that table has
always included `UNKNOWN_WORD_PRIOR` — a standing 1e-6 for "what was typed is a
real word nobody has told the keyboard about: a name, a codeword, jargon". It is
the thing that stops the keyboard rewriting `Coonabibba`. But it was a flat
constant, and a flat constant says `hsnging` is as plausibly somebody's surname
as `Coonabibba` is. `hanging`'s score, discounted for a full-price substitution,
came to 4.7e-8 — twenty times smaller than the standing claim that `hsnging` was
a word all along. No correction of a rare-ish word can ever clear 90% against
that, however obvious it is.

So the prior is now multiplied by a **character-trigram model of the two
languages**, built from the wordlists at startup. `hsn` occurs in no German or
English word; `oon`, `nab` and `bba` all do. `hsnging` now corrects at 0.99.

Four things about it were decided deliberately.

**Built, not shipped.** The wordlists are already being read and parsed at
startup; counting trigrams over them is one more pass and costs 5ms on the disk
thread. An asset would need a format, a provenance note, and a way to stay in
step with the lists it was derived from.

**It can only ever lower the prior, never raise it.** Plausibility is clamped at
one, so a word-shaped string keeps exactly the protection the flat constant gave
it and gains none — every change this model can produce is in the direction of
correcting something that was previously left alone, and no correction that
works today can be weakened by it. The reason is that *plausible-looking typos
are the common kind*: `teh` is word-shaped, because German has `stehen`, and
rewarding it for that would weaken the single most valuable correction in
English.

**Seven nats of floor, chosen by measurement.** `NameProtectionTest` scores
fifty-odd names, place names and pieces of jargon against a set of real slips in
both languages, and sweeps the floor:

| floor | typos fixed | names lost |
|-------|-------------|------------|
| 12    | 17          | 4          |
| 9     | 17          | 3          |
| **7** | **16**      | **1**      |
| 6     | 13          | 1          |
| 5     | 10          | 1          |

Twelve nats correct `Tyberius` to `Tiberius` and `systemd` to `system`. Seven
protects both and gives up almost nothing. Under D8 that trade is not close:
a typo left standing costs a backspace, while a name corrected away can only be
recovered by adding it to the store by hand.

The one casualty at seven is `Jost` → `Just`, which was already at 0.73 before
any of this — a four-letter name one slip from a word in the commonest hundred.
Short names are exposed and no setting here changes that; D40's purple plus
does.

**It declines to have an opinion on a small corpus.** The table has 19,683
cells. Learn it from a few dozen words and it describes *those words* rather
than a language: everything ordinary looks implausible and the prior collapses
for strings it has no business doubting. Below a thousand words it is flat.
That was found by a test fixture, and it is the right behaviour in production
too — if a wordlist asset ever fails to load, the alternative is a keyboard
rewriting text on the strength of nothing.

**Splitting the counts by position does not help, and was measured.** The
obvious next move is that a trigram only sees the word start for the first two
positions — by the third character the boundary has slid out of the window, so
`teh`'s `(t,e,h)` is the same context as `stehen`'s. Bucketing the table by
distance from the nearer end puts them back apart. Built and swept at two, three
and four buckets against the nine slips the prior currently blocks:

| | 1 bucket | 2 | 3 | 4 |
|---|---|---|---|---|
| `thier` | 1.0 | 1.0 | 1.0 | 1.0 |
| `freind` | 1.0 | 1.0 | 1.0 | 1.0 |
| `villeicht` | 1.0 | 1.0 | 1.0 | 1.0 |
| `untill` | 0.75 | 0.49 | 0.92 | 1.0 |

Nothing moves, and the giveaway is the control: the model scores the *correct*
words just as badly — `friend` at 0.089, `until` at 0.060. It has no
discriminating power on this class in any configuration, while names degrade as
the buckets multiply and thin the data (`Sven` from 0.0065 to 0.0013).

The reason is D2 rather than the window. `freind` is built from `rei`, `ein` and
`ind`, all extremely common **German**; `villeicht` is German-plausible
throughout; `thier` looks like `hier` and `Tier`. These sequences are legitimate
somewhere in the union of the two languages, and where in the word they sit does
not change that. **A bilingual keyboard's spelling model is structurally weaker
than a monolingual one**, for the same reason D39f found more swipe collisions
in German than in English and more again in both together. This is a tax on D2,
not a bug to be fixed.

Loosening the falloff instead (D34's slider) trades about one for one — from 7
to 5 buys three of the nine and loses `Tyberius` and `Rhys` — which is far worse
than the sixteen-for-one the shape model itself got. There is no cheap lever
left.

**What this is not** is the context model of D10/D12. It knows nothing about the
previous word; it is a claim about spelling alone, which is precisely why it is
cheap enough to be exact and safe enough to ship ahead of step 6. The gains left
on the table — `Wohnzimer`, `Schmeterling`, `Gescichte` all still sit below the
threshold — are the ones that need to know what sentence they are in.

### D44 — A capital in the middle means a name

Neither of this keyboard's languages puts a capital inside a word. German
capitalises the first letter of a noun, English the first of a sentence or a
proper noun; nothing in either puts one in the middle. So anything that does is
a brand, a product, an identifier or a surname — `iPhone`, `eBay`, `McDonald`,
`JavaScript`, `GmbH`, `PostgreSQL`, `TyberiusPrime` — and it got there by a
deliberate shift press in a deliberate place. **It is the strongest evidence the
keyboard ever gets that the typist knows exactly what they are writing**, and it
is free: no model, no wordlist, no measurement, just a property of the string.

So it turns auto-correction off outright, in the same way D28's "already a word"
does. Candidates are still gathered and the strip still fills, because a
suggestion costs nothing and might be wanted; what stops is replacement.

What it was worth, measured across twenty camel-cased names: **one live bug** —
`iOS` was being replaced by `is` at 0.91 — and three near misses that a nudge to
the falloff slider would have let through, `DeepL` → `Deep` at 0.74, `AGit` →
`Gait`, `macOS` → `Marcos`. Modest, and it costs nothing, because a word with a
capital in the middle is essentially never in either wordlist and so had nothing
to gain from correction in the first place.

**A word in capitals throughout is exempt, and that exception carries the
decision.** Shouting is a styling choice rather than a claim about the word, and
the naive rule would have caught it: `TEH` → `THE`, `UDN` → `UND`, `ADN` →
`AND`, `DONT` → `DON'T` and `HELOL` → `HELLO` all correct perfectly well today.
Five good corrections lost to a rule aimed at `iPhone` would have been a bad
trade — and acronyms like `USA` and `GPL` are protected by other means already,
being nowhere near a dictionary word.

Measuring the exception turned up a bug beside it. `I DONT CARE` came back as
`I Don't CARE`, because `applyTypedCase` only ever restored the *first* letter's
case — the right rule for `Haus` and the wrong one for a word that is all
capitals. A correction inside a shout now stays shouted.

### D45 — A word in both dictionaries is one word

Found by reviewing D2's promises against the build rather than by typing.

**2,933 folded spellings are in both wordlists, and they carry 21.1% of the
German corpus and 21.4% of the English.** `in`, `was`, `so`, `an`, `will`,
`man`, `hand`, `name`, `problem`, `moment`, `baby`, `kind`. That is not a corner
of the vocabulary, it is the part where the two languages actually touch — which
under D1 and D2 is the part this project exists for.

Both copies arrived as separate candidates. Both went into the confidence
divisor and only one could ever be the numerator, so **a word in both languages
stood in its own way**, at a discount measured between 27% and 48%. The effect
was that corrections *toward* a shared word could not reach the threshold at
all. Same slip, same falloff, the only variable being how many lists hold the
target:

| target in both | | target in one | |
|---|---|---|---|
| `hnad` → `hand` | 0.37 | `wrold` → `world` | 0.99 ✓ |
| `probelm` → `Problem` | 0.52 | `peopel` → `people` | 1.00 ✓ |
| `kidn` → `kind` | 0.58 | `hoem` → `home` | 1.00 ✓ |
| `momnet` → `Moment` | 0.64 | `arbiet` → `Arbeit` | 1.00 ✓ |
| `nmae` → `name` | 0.73 | | |

**So candidates are grouped by folded spelling and their weights added.** Not a
thumb on the scale: "the typist meant German `Hand`" and "the typist meant
English `hand`" put the same letters on the screen, so the chance the
replacement is right is the chance of either. The divisor is untouched — the
same scores, grouped — which is why this can only raise confidence in the
candidate that was already winning, never invent one.

Measured after: `nmae` → `name` 1.00, `momnet` → `moment` 1.00, `probelm` →
`problem` 1.00, `bayb` → `baby` 1.00, `wasd` → `was` 0.99, `kidn` → `kind` 0.92,
all now firing. `hnad` → `hand` reaches 0.66 and `alos` → `also` 0.85 and still
do not, beaten by genuine rivals rather than by themselves. Across the wider
sets: 19 → 20 of the classic slips, 16 → 17 of D43's typos, and **no name loses
its protection** — the floor sweep at 7 nats is unchanged at one.

Two things fell out of the same grouping.

**The casing was wrong, and nobody had noticed.** The surviving copy was
whichever corpus weighed the word more, so anyone writing English was handed
`Moment` and `Problem` — German spellings of English words. The merged entry
keeps the **least capitalised** spelling, which is D22's rule arriving from the
other side: that rule kept one casing per word *within* a list and said nothing
about a German noun meeting its English twin. The typist supplies the capital,
exactly as they already do for `Zeit`. Only when the two differ by nothing but
case — `weiß` against `Weiss` is not one spelling of one word, and stays settled
by weight.

**The strip stops repeating itself.** `bab` was spending two of its three slots
on `baby` and `Baby`; `prob`, `mom` and `nam` likewise. One word, one slot.

**The D4 tint goes neutral for a shared word**, which is the honest reading: a
word both lists carry is not evidence of either language, and the indicator
exists to explain corrections rather than to pick a side. It shares the neutral
wash with the personal store, and the two mean the same thing — *this keyboard
makes no language claim about this word*.

**One place is deliberately left doubled.** D41 turns `i` into `I`, and that
runs entirely on the two lists disagreeing about the casing of one letter. For a
single letter the casing is not a detail of the word, it is the word, so
single letters are keyed by their exact spelling and the two survive separately.

**Not done: the same divisor in the swipe path.** `rankGesture` double-counts a
shared word in exactly the same way. It is left alone because gesture scores are
square-rooted before they are summed, so grouping them is an approximation
rather than the identity it is here, and because ordering there decides what
gets committed — a change that needs its own accuracy run against D39's corpus,
not a free ride on this one.

### D46 — The prediction model: counts first, then one multilingual transformer

Answers the open question D12 left standing — *which concrete model and corpus* —
and reverses half of D10 on purpose.

#### The thing D12 assumed and we do not have

D12's case for one joint model is that *"in `das ist ein total edge case`, the
English model scoring `edge` has never seen the German context."* That is right
about models and it quietly assumes **code-switched training data**. Both
corpora here are monolingual OpenSubtitles. A model trained on their union sees
German context followed by German, English followed by English, and never once
sees the switch — so it will put low probability on the first English word after
German context, degrading at exactly the case this project exists for.

The code-switching problem is therefore a **corpus** problem before it is a
model problem, and no architecture fixes it. Two things do, and both are cheap:

- **Interpolate with the unigram prior:** `P = λ·P_LM(w|ctx) + (1−λ)·P_unigram(w)`.
  This makes it arithmetically impossible for the model to score worse than
  today's lookup, which is the same safety shape D43 uses — `WordShape` may only
  lower the unknown-word prior, never raise it. At a switch the LM contributes
  nothing useful and the interpolation falls back to what already works.
- **Synthesise the switches at data-prep time**, splicing clauses of one
  language into sentences of the other at clause boundaries. Crude, and it
  teaches the model that a switch is an ordinary event rather than a shock.

#### Two rules that come before the artefact

**Rank, do not generate.** The model never proposes a word; it scores a
shortlist the lexicons have already produced. Three things follow, and all of
them are things this project has decided elsewhere: the vocabulary stays closed,
so nothing is ever offered whose provenance is not recorded (D13, D22); latency
is bounded and predictable rather than a beam search; and a word from the
personal store gets scored in context beside dictionary words, which raises D8's
ceiling without learning anything.

**The model replaces the unigram weight and nothing else.** Confidence today is
`weight × exp(−decay × cost) / mass`. Substitute `P(w | context)` for `weight`
and the channel model, the unknown-word prior, D45's grouping and the threshold
all stay exactly as they are and stay calibrated. It is a drop-in, which is the
whole value of it: step 6 gets measured on the same harness that measures step 3,
against the same corpora, and a regression is visible rather than arguable.

**Context is what the keyboard typed.** Not what the field contains — D21
refuses to ask, and D23's cursor jump leaves the keyboard unable to vouch for
what is there. Prediction inherits that posture: it works from the keyboard's
own recent output in this field, and where the word is unknown it goes quiet
rather than guessing. Which means the context window is frequently short, and a
model that needs sixty-four tokens of history to be useful is the wrong model.

#### Sequencing: the baseline first, and why that reverses D10

D10 said "not an n-gram-first approach." That was right for correction and is
wrong for prediction, for a reason that did not exist when it was written:
**there is currently no way to tell whether step 6 delivered.** The roadmap
calls step 6 the point where "the project either delivers or does not", and it
has nothing to be compared against. Going into a training project with no
baseline is how one ships a model that is worse than a lookup and cannot tell.

So: a bigram store lands first. It fills D9's empty strip — blank today at every
word boundary and every word's first keystroke — in days rather than months, it
is a hash lookup rather than an inference, it is explainable in the way D4 and
D10 both ask for, and **it is the number the transformer has to beat.**

#### The artefacts

Budget agreed at **~60MB installed**, against 8.6MB today. That is generous
enough that neither stage has to be crippled, and it is the one number that
constrains both.

**Stage 6a — bigram store. Built** (`scripts/build-bigrams.py`), and it came in
where the estimate said: 18.3MB for both languages, taking the APK from 8.6MB
to 24.3MB and leaving some 35MB of the budget for the model.

Full vocabulary rather than a top-N slice, since the budget allowed it. A row
per context word plus one for the sentence start, compressed-sparse-row, each
follower a single 32-bit word — 17 bits of wordlist index above 15 bits of
quantised conditional log probability. Fixed width because a common context has
thousands of followers and the app binary-searches the row rather than scanning
it. Keys are wordlist line indices rather than strings, which is what makes it
this small; the price is that a store belongs to the wordlist it was counted
from, so the header carries that file's entry count and SHA-256 and
`BigramAssetTest` refuses a mismatch.

Counted over 41.6M German and 441M English subtitle lines — 2.7 billion tokens,
2.4 billion in-vocabulary pairs, 24 million distinct ones.

**The two thresholds differ, and the reason is worth keeping.** The floor is
absolute, because the provenance argument is about absolute occurrences and does
not scale with how much corpus there happens to be. Above the floor it is
size against coverage, and English has eleven times German's tokens, so the same
number means something eleven times weaker there. Measured:

| threshold | German | English |
|---|---|---|
| 5 | 95.4% of pair mass, 6.5MB | 99.2%, 31.1MB |
| 10 | 92.9%, 3.7MB | 98.3%, 19.5MB |
| 20 | 90.2%, 2.2MB | 97.1%, 11.8MB |

Shipping German at 5 and English at 20 lands the two within two points of each
other — closer to parity than any single number gets, and **smaller than a
uniform threshold of 10** would have been. Under D1 neither language is the
fallback, and a store that predicted English well and German poorly would have
been that in all but name.

What it predicts, unprompted: `vielen` → `dank` at 0.80, `thank` → `you` at
0.93, `guten` → `morgen`/`Tag`/`Abend`, `how` → `do`/`much`/`to`, and a
sentence-start row led by `ich` and `I` — which is exactly the slot D9 promised
and D21 has been leaving blank.

Backs off to the existing unigram weight, which is the same interpolation the
neural stage will use, so the plumbing is written once.

**Wired into the strip.** `BigramStore` maps the asset straight out of the APK
— no allocation, no parse, and the pages for words nobody types never arrive.
That is why `build.gradle.kts` keeps `.bigrams` uncompressed: `openFd` throws
for a compressed asset, and eighteen megabytes on the heap is how an input
method gets killed mid-sentence. It cost 0.9MB of APK, the stores being
quantised integers and near-incompressible anyway.

The store **refuses rather than guesses**. Its keys are wordlist line indices,
so a store counted against a different wordlist would predict fluently and
wrongly; a word-count mismatch, an unknown version or a truncated file all
produce a store with no opinions, and the strip goes back to being empty
between words. That is the failure worth having, because the other one looks
like it is working.

**Which language answers is decided by the last word, not by a mode.** The two
stores hold conditionals within their own corpus and are not comparable as they
stand — `P_de(next | die)` and `P_en(next | die)` are both perfectly normalised
and describe different worlds. What makes them one distribution is the mixture

    P(next | previous) = Σ P(language | previous) · P_language(next | previous)

with `P(language | previous)` taken from the unigram weights D22 already ships.
`die` is 55 times commoner in German, so German gets almost the whole vote and
the strip fills with German; `in`, `so` and `was` are near-equal, so both
languages answer. **This is D2's per-word language inference in the only form
the evidence supports** — not a guess about what language the sentence is in,
but a weighting by what the last word actually was. Nothing anywhere holds a
current language, and the candidates are merged across the two by D45's rule,
because a word both lists carry is one word here too.

At a sentence start there is no previous word to weight by, so the corpora split
it evenly — which is what D1 says they are — and the openings are capitalised,
since what the strip shows and what it inserts must be the same string.

**The context comes from what the keyboard typed**, tracked in `WordInProgress`
beside the word in progress, so a prediction costs no round trip to the app.
It inherits D21's refusal wholesale: a cursor jump, a backspace into the
previous word, a word read back rather than typed, and the context is
`Unknown` and the strip stays empty. A prediction made from a guess about the
preceding word would be a guess squared.

**Not done: context in the correction path.** D46 says the model eventually
replaces the unigram weight everywhere, which would let `P(next | previous)`
rank completions and rescore corrections. That moves every number D43 and D45
measured, so it needs its own measurement pass rather than a free ride on this
one. Prediction is pure gain — it fills slots that were empty; rescoring can
take slots away.

**Stage 6b — the model.** Joint SentencePiece vocabulary over both languages
(16k, trained on the two corpora together rather than concatenating two
monolingual vocabularies — that shared vocabulary is the part of D12 that
survives intact). A decoder of roughly 8 layers at 384 dimensions, ~25M
parameters, short context. Trained on OpenSubtitles DE+EN interleaved, with the
switch augmentation above. Quantised to int8 and run through TFLite with
XNNPACK, which is Apache 2.0 and one-way compatible with GPLv3 in the same way
the CC BY-SA sources already are. Call it 30MB.

#### The blocker, checked

**hermitdave/FrequencyWords ships unigrams only.** Every count in `de.txt` and
`en.txt` came from a derived list, not from the corpus, so both stages need the
OpenSubtitles corpus itself, from OPUS. Read the terms rather than assumed them,
and the answer reframes something the project already ships.

**OPUS grants no licence.** Its statement is *"We do not own any of the text
from which the data has been extracted. We only offer files that we believe we
are free to redistribute"*, alongside a take-down policy, an attribution request
(a link to opensubtitles.org from the site and from publications) and a citation
requirement — Lison and Tiedemann, LREC 2016. That is a posture, not a grant.
There is no licence chain here to inherit and nothing to relicense as GPLv3.

**So PROVENANCE currently describes the wrong footing for what already ships.**
hermitdave's CC BY-SA 4.0 covers *his compilation* of counts; it cannot cover
the subtitles, because he had no rights in them to pass on. The reason shipping
those counts is defensible is different and stronger: **a frequency table is
facts about a text rather than its expression**, and no corpus can be
reconstructed from it. The bet is a good one and it has already been made — it
is just recorded as a licence chain when it is not one.

**A bigram table stands on exactly that same ground**, and on well-trodden
ground: Google publishes Books Ngrams up to 5-grams under CC BY 3.0 from
in-copyright books, and HathiTrust distributes Extracted Features from
in-copyright volumes, both resting on the non-expressive use that the Google
Books and HathiTrust rulings supported. One condition falls out of the reasoning
and is therefore not optional: **the store carries a documented count
threshold**, pairs seen fewer than a handful of times being dropped. A table
including singletons begins to leak rare phrasings; a thresholded one provably
cannot. That is the difference between a statistic and an index, and it is what
the defence rests on.

**Stage 6b is a different bet, not a continuation of this one.** Trained weights
are contested where n-gram counts are not, and memorisation is an observed
phenomenon rather than a theoretical one. Splitting the stages was decided for
methodological reasons — 6a is the yardstick — and it turns out to matter here
too, because it means the project can stop between them. Recorded in Risks.

**Fallbacks that carry an actual grant**, if the second bet is unwanted: Google
Books Ngrams (CC BY 3.0, German included, n up to 5 — and n-grams are exactly
what 6a needs; the cost is book register, which predicts *der Herr sprach* far
better than *bin gleich da*); Tatoeba (CC BY 2.0 FR, conversational, ~385k DE-EN
units, right register and far too small to train on, but usable as an
interpolation component); Wikipedia (CC BY-SA 4.0, large, wrong register).
**Leipzig Corpora is ruled out** — CC BY-NC, and a field-of-use restriction is
the one thing GPLv3 cannot absorb.

**The code-switched data this decision asks for does not exist
redistributably.** The one real German-English code-switching corpus — Denglisch,
Osmelak and Wintner 2023 — is drawn from social media with no licence stated in
either the paper or the repository. That matters less than it appears, because
its right use is as a **held-out evaluation set**, and measuring against a corpus
requires no right to redistribute it. It supplies the thing this decision
otherwise lacks: a way to find out whether the switch augmentation did anything.

None of the above is legal advice; it is a reading of published terms.

The build script sits beside `build-wordlists.py` and records its sources the
same way, in the same file, at the time it is added — including, this time, the
distinction between an artefact that carries a grant and one that rests on not
being a derivative work.

---

## Architecture

Falls out of the decisions above, particularly D7, D12 and D15.

```
      touch points
           │
           ▼
   ┌───────────────┐   spatial likelihood per key, not a hit test (D15)
   │  TouchModel   │   learns one-thumb drift; gesture paths done (D39)
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
stops insetting the IME window — nor the launcher screen's, where the same
oversight put the status text underneath the clock and the first button behind
the action bar. The action bar is gone and both screens now pad themselves by
the system-bar insets. Unhandled, the system's own hide-keyboard
chevron, IME-switcher globe and gesture pill are composited over the bottom row
and take its taps. The input view is wrapped in a container carrying the
navigation-bar inset as bottom padding.

**Candidates** now has two producers, not one (D39). Taps arrive as characters
with a touch behind each and are matched by spatial edit distance; strokes
arrive as a shape with no characters at all and are matched by comparing paths.
They meet at the candidate set and share everything past it. The diagram's
single arrow into Candidates is a simplification — but only of the input side,
which is exactly what D7 predicted would be the part free to differ.

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
6. **Prediction** (D10/D12/D46), in two stages, because the second needs the
   first to be measurable against:
   1. **The bigram store** — settle the corpus licence, build it beside the
      wordlists, interpolate it with the unigram weight, fill the strip between
      words. This is the baseline the model must beat.
   2. **The multilingual model** — train on the mixed corpus with switch
      augmentation, quantise, integrate behind the same interface, measure
      latency on the actual Fairphone and quality against 6a.
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
- **Step 6b ships weights trained on text nobody granted a licence for**, which
  is a different and less settled position than the counts everything up to 6a
  rests on (D46). The mitigation is the split itself: 6a is the bet this project
  has already made and can defend, 6b is a new one, and the roadmap is arranged
  so that stopping between them costs nothing already built. If the answer turns
  out to be no, the fallback is a smaller model on Google Books Ngrams and
  Tatoeba — worse register, real grant.
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
