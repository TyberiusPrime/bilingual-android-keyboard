# Design document

**Status: decisions D1–D30 settled; architecture drafted from them. Roadmap
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
- [ ] Corrections cannot fix a mistyped **first** letter (D23), because the scan
      is bucketed by it. How often does that bite in real typing?
- [ ] Is the unknown-word prior right (D28)? Everything the auto-correction
      threshold does, it does relative to that one number, and it was guessed.
- [ ] Auto-correction cannot reach a word whose first letter was mistyped, and
      cannot spell `Straße` from `strasse` (D28).
- [ ] A correction query costs ~3ms on a laptop and has not been timed on the
      phone (D23). Per keystroke, on a word with no completions, that is the
      first thing in this keyboard with a latency budget worth watching.

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

**Backspace puts it back** — D14, finally built. The keystroke immediately
after a replacement restores exactly what was typed, space and all, and the
strip turns into an offer to remember the word, which under D8 is the only way
the keyboard ever learns anything. Anything other than that one backspace closes
the window.

Measured on a laptop: 1.5ms per space. It was 18ms before the edit-distance
table stopped normalising Unicode in its inner loop, which is worth recording
because the profile is entirely unlike the rest of the keyboard — thousands of
tiny comparisons rather than one lookup.

Known limits: the search is bucketed by first letter (D23), so `hte` cannot
reach `the`. And `ß` folds to `s` one character at a time here, so `strasse`
does not reach `Straße` cheaply enough to be corrected.

### D29 — Haptics, three states

Off, light, strong. Not a switch, because "on" means something different on
every phone, and because the difference between the two strengths is what makes
a correction distinguishable from a keypress by feel alone.

A keypress is one tick, as short as the hardware will honour, since it happens
hundreds of times a minute and anything longer is a buzz. An applied correction
is **two**: the keyboard did something unasked, and that is worth noticing while
the thumb is still moving — it is the only signal that D14's undo window is
open and about to close.

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
stops insetting the IME window — nor the launcher screen's, where the same
oversight put the status text underneath the clock and the first button behind
the action bar. The action bar is gone and both screens now pad themselves by
the system-bar insets. Unhandled, the system's own hide-keyboard
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
