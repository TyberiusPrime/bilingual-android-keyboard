# What an Android (/e/OS) keyboard actually sees

Reference notes for design decisions. /e/OS is a deGoogled LineageOS fork; the
input-method framework in it is stock AOSP, unmodified. Everything below applies
identically on a Fairphone running /e/OS and on a Pixel — the differences are in
distribution and defaults, not API (see the last section).

API levels are noted where they matter. A Fairphone 4/5 on current /e/OS is
Android 12–14, i.e. **API 31–34**.

---

## 1. The shape of the thing

A keyboard is a `Service`, not an app with a window:

```
InputMethodService  (you subclass this)
  ├── EditorInfo         — a static description of the field, handed to you once per focus
  ├── InputConnection    — an async IPC pipe to the text field, the ONLY way to touch text
  └── InputMethodSubtype — the system's notion of "which language mode am I in"
```

The system binds to your service (`BIND_INPUT_METHOD`), you hand back a `View`,
and from then on you are a peripheral: you do not own the text, you do not see
the app, you send edits down a pipe and hope.

Three consequences that shape the whole design:

- **You never hold the document.** There is no "give me the text field's
  contents" call. There is only "give me up to N characters around the cursor",
  answered asynchronously by the other process, which may return `null`, may
  truncate, and in WebViews frequently lies.
- **You cannot enable yourself.** Only the user can, in Settings, past a scary
  warning dialog. This is why the project needs the sideload-an-APK loop.
- **Everything is per-keystroke IPC.** Latency budget is real; a keyboard that
  does a blocking round trip per key feels broken.

---

## 2. `InputMethodService` — the lifecycle you get

| Callback | When | Why it matters here |
|---|---|---|
| `onCreate()` | service bound, once | load dictionaries here, not per field |
| `onCreateInputView()` | window (re)created | returns the keyboard `View`; recreated on config/theme change |
| `onCreateCandidatesView()` | same | the suggestion strip, if you want the framework-managed one |
| `onStartInput(EditorInfo, restarting)` | field gains focus | no view yet; state reset goes here |
| `onStartInputView(EditorInfo, restarting)` | field gains focus, view live | pick layer, caps state, per-app language guess |
| `onUpdateSelection(oldStart, oldEnd, newStart, newEnd, candStart, candEnd)` | cursor moved | **the only notification that the user tapped elsewhere or the app rewrote text** — you must abandon your composing state here or you corrupt text |
| `onUpdateCursorAnchorInfo(CursorAnchorInfo)` | opt-in, API 21+ | character bounding boxes + insertion-marker position; needed for popups anchored to text |
| `onFinishInputView` / `onFinishInput` | focus lost | flush learning, drop context |
| `onEvaluateFullscreenMode()` | orientation | AOSP default is "yes" in landscape, giving you the ugly extract-edit UI; virtually every modern keyboard returns `false` |
| `onEvaluateInputViewShown()` | hardware kb attached | decide whether to show at all |
| `onComputeInsets(Insets)` | layout | declares which region is touchable vs. content; wrong values = you eat taps outside the keyboard |
| `onKeyDown` / `onKeyUp` | hardware keys | physical keyboard, and Back handling |
| `onCurrentInputMethodSubtypeChanged` | user switched subtype | the layout-swap hook we intend not to use |
| `onStartStylusHandwriting` | API 33+ | only if we opt in via `method.xml` |

And things you can call: `setInputView`, `requestHideSelf`,
`switchToNextInputMethod(onlyCurrentIme)` (**API 28+**, hence our `minSdk`),
`shouldOfferSwitchingToNextInputMethod()`, `sendDownUpKeyEvents`,
`getCurrentInputEditorInfo()`, `getCurrentInputConnection()`.

---

## 3. `EditorInfo` — everything the app tells you about the field

Delivered once per focus. This is your entire situational awareness.

**Type information**
- `inputType`: a class (`TEXT` / `NUMBER` / `PHONE` / `DATETIME`) + a variation
  (`PASSWORD`, `VISIBLE_PASSWORD`, `WEB_PASSWORD`, `EMAIL_ADDRESS`, `URI`,
  `PERSON_NAME`, `POSTAL_ADDRESS`, …) + flags (`CAP_SENTENCES`, `CAP_WORDS`,
  `CAP_CHARACTERS`, `AUTO_CORRECT`, `AUTO_COMPLETE`, `NO_SUGGESTIONS`,
  `MULTI_LINE`).
- `imeOptions`: the Enter-key action (`IME_ACTION_SEND` / `GO` / `SEARCH` /
  `DONE` / `NEXT`) plus flags (`IME_FLAG_NO_FULLSCREEN`,
  `IME_FLAG_NO_EXTRACT_UI`, `IME_FLAG_NO_PERSONALIZED_LEARNING`).

**Identity — the useful part for us**
- `packageName`: **which app you are typing into.** The single strongest signal
  available for guessing language. `org.thoughtcrime.securesms` vs. a work
  Slack vs. a terminal emulator are different language distributions, and this
  is free and reliable.
- `fieldId`, `fieldName`, `hintText`, `label`, `privateImeOptions`.
- `hintLocales` (`LocaleList`, **API 24+**): the app explicitly declaring which
  language it expects, via `EditText.setImeHintLocales`. Exactly what a
  bilingual keyboard wants — and almost nothing sets it. Worth honouring when
  present, worth nothing as a primary strategy.

**Content snapshots (API 30+)**
- `getInitialTextBeforeCursor(n, flags)`, `getInitialTextAfterCursor`,
  `getInitialSelectedText` — the field's contents at focus time without an IPC
  round-trip. Cheap context for a first prediction. Below API 30 you have to
  ask over the `InputConnection`.
- `initialSelStart` / `initialSelEnd` (API 24+).

**Rich content**
- `contentMimeTypes` — what the field will accept from `commitContent`
  (images, GIFs, stickers).
- `supportedHandwritingGestureTypes` (API 34+).

---

## 4. `InputConnection` — the only pipe to the text

Asynchronous IPC to the *app's* process. Every read can fail.

**Writing**
- `commitText(text, newCursorPosition)` — final text.
- `setComposingText(text, newCursorPosition)`, `setComposingRegion(start, end)`,
  `finishComposingText()` — the underlined "word in progress". **This is the
  entire mechanism for correction**: keep the word composing, and you can
  replace it wholesale when you decide it should have been something else. Once
  committed, changing it means counting characters backwards and deleting.
- `deleteSurroundingText(before, after)`,
  `deleteSurroundingTextInCodePoints` (API 24+ — the one that doesn't cut
  emoji in half).
- `replaceText(start, end, text, …)` (**API 34+**) — finally a sane primitive.
- `sendKeyEvent(KeyEvent)` — raw key events. Needed because some targets
  (terminals, games, a few WebViews) ignore `deleteSurroundingText` and only
  honour a real `KEYCODE_DEL`.
- `performEditorAction(actionId)`, `performContextMenuAction(id)`,
  `setSelection(start, end)`.
- `beginBatchEdit()` / `endBatchEdit()` — coalesce; without it a multi-step
  correction visibly flickers in the target app.
- `commitContent(InputContentInfo, …)` (API 25+) — images/stickers.

**Reading — where the pain is**
- `getTextBeforeCursor(n, flags)`, `getTextAfterCursor(n, flags)`,
  `getSelectedText(flags)` — may return `null` (app not ready, or refusing),
  may be truncated. `n` in the low hundreds is the practical ceiling.
- `getSurroundingText(beforeLength, afterLength, flags)` (**API 31+**) — one
  call, consistent snapshot. Prefer it where available.
- `getExtractedText(ExtractedTextRequest, flags)` with
  `GET_EXTRACTED_TEXT_MONITOR` — a subscription to the field's contents. More
  than most apps implement well.
- `getCursorCapsMode(reqModes)` — should the next letter be capitalised.
- `requestCursorUpdates(mode)` — `CURSOR_UPDATE_IMMEDIATE` / `_MONITOR`, feeds
  `onUpdateCursorAnchorInfo`.
- `takeSnapshot()` (API 33+), `performSpellCheck()` (API 31+),
  `setImeConsumesInput(boolean)` (API 33+).

**The rule that follows from all of this:** the keyboard must maintain its own
model of what it typed, and treat the app as a partially-observable environment
that may contradict it at any moment (`onUpdateSelection` is the contradiction
notice). Every mature keyboard has this bookkeeping layer, and every keyboard
bug report about "text got scrambled in app X" is a bug in it.

---

## 5. Subtypes — the thing that causes your layout-swapping complaint

`InputMethodSubtype` is the framework's model of "one IME, N language modes".
Each subtype carries a locale/language tag, a mode, an icon, a label. The
system-level language switcher (globe key, spacebar swipe in stock keyboards)
cycles *subtypes*, and every mainstream keyboard binds layout + dictionary +
autocorrect language to the active subtype as a unit. Hence: switch language,
get a different layout.

Relevant API: `getCurrentInputMethodSubtype()`, `switchInputMethod(id, subtype)`,
`setAdditionalInputMethodSubtypes()` (add subtypes at runtime),
`InputMethodSubtype.getLanguageTag()`.

Nothing forces the coupling. Declaring **one** subtype and handling multiple
languages internally is legal and invisible to the system — the framework never
asks what language you think you are in. That is the core architectural bet of
this project, and it is available to us for free. `res/xml/method.xml` in this
repo already does exactly that.

---

## 6. Dictionaries, spell check, and learning

- **System user dictionary** (`UserDictionary` / `content://user_dictionary/words`):
  gated by `READ_USER_DICTIONARY`, and access has been progressively restricted
  across releases and varies by OEM/ROM. Treat as read-mostly-unavailable; plan
  on our own storage. It is single-locale-per-entry anyway, so it does not
  express what we want.
- **System spell checker** (`TextServicesManager`, `SpellCheckerSession`,
  `SuggestionsInfo`): available to an IME, and on /e/OS is AOSP's, which is
  per-locale and mediocre. Usable as a signal, not as the engine.
- **Anything we build** — n-grams, per-language dictionaries, learned words —
  lives in our own storage, and is ours to keep on-device.

**Privacy signals we are obliged to honour** (and which also bound what we can
learn from):
- `IME_FLAG_NO_PERSONALIZED_LEARNING` (API 26+) — set by incognito/private
  fields. Do not learn.
- Any password variation, and `TYPE_TEXT_FLAG_NO_SUGGESTIONS`. No prediction,
  no learning, no logging.
- `EMAIL_ADDRESS` / `URI` / `PERSON_NAME` variations — different tokenisation,
  and generally not corpus material.

---

## 7. What you are *not* given

- The full document. Ever.
- Reliable text at all — reads can return `null`, and do, in WebViews.
- Any signal of intended language, except the rarely-set `hintLocales` and the
  subtype you chose yourself.
- The screen, or anything about the app beyond its package name.
- The ability to install, enable, or select yourself.
- Anything before first unlock, unless the service is `directBootAware` with
  device-encrypted storage.

---

## 8. /e/OS and Fairphone specifics

The API is stock AOSP — nothing added, nothing removed. What differs:

- **No Play Services, no Play Store.** Distribution is sideload (`adb install`)
  or F-Droid. This is exactly why the CI produces a per-PR APK.
- **Debug signing keys differ per CI run**, so installing a newer PR build over
  an older one fails with a signature mismatch until you uninstall. Noted in
  the PR comment the workflow posts.
- **Default keyboard** on /e/OS is typically AOSP LatinIME or a HeliBoard/
  OpenBoard derivative — all GPL AOSP lineage, so their behaviour is readable
  source when we want to know how something is *supposed* to work.
- **The enable flow** shows the standard AOSP warning that the input method
  "may be able to collect all the text you type, including personal data".
  Unavoidable; every IME gets it.
- **API 31–34** on current Fairphone /e/OS builds, so `getSurroundingText`
  (31) is available; `replaceText` (34) is not, on the older ones.
- Being your daily driver keyboard means a crash makes the phone untypeable.
  A stable fallback IME should stay installed on the device.

---

## 9. Mapping to the four original complaints

| Complaint | Where the API stands |
|---|---|
| Only a single dictionary | Entirely our choice. Nothing in the framework says one subtype = one dictionary. Free to fix. |
| Layout changes with language | Caused by subtype-coupled design in other keyboards, not by the framework. Free to fix — one subtype, one layout. |
| Tiny space bar, hitting `.` | Entirely our layout and hit-testing. Free to fix, and additionally fixable *statistically* (touch-target biasing), which is where the real win is. |
| Prediction/correction sucks | The hard one, and the one the API constrains: limited, unreliable context; correction only cheap while text is composing; all learning must be ours and must respect the no-learning flags. |

Three of the four are pure design freedom. The fourth is the actual project.
