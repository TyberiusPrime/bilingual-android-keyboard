# Design document

**Status: decisions D1–D40 settled; architecture drafted from them. Roadmap
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

**The file format had to stay readable.** A quick entry is the word, a tab, and
a marker; a bare line is an ordinary word, which is every line of every file
written before this. The store also holds one list of entries rather than a
list of words plus a set of tags — two fields cannot be swapped together, and a
reader landing between the two writes would see a word tagged quick that the
store does not have.

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
