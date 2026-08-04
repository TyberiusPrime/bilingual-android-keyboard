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

- [ ] Which two (or more) languages, and what is the realistic mix?
- [ ] Layout family: QWERTY / QWERTZ / something else. Umlauts: dedicated keys,
      long-press, or neither?
- [ ] Should the keyboard ever visibly indicate which language it currently
      believes it is in, or stay silent?
- [ ] Correction aggressiveness: silent auto-replace vs. suggest-only.
- [ ] Where should punctuation live, given complaint 3?
- [ ] Swipe/glide typing: in scope or not?
- [ ] Prediction engine: dictionary + n-gram, or something heavier?
- [ ] Learning: what is stored, where, and how is it inspected/reset?

## Decisions

_(none yet)_
