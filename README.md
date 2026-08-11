# bilingual-android-keyboard

An Android keyboard that is aware of two languages at once, instead of making
you switch between them.

**Status: it types, it suggests, and it corrects.** German and English wordlists
are queried together on every keystroke, so a suggestion from either language
can win a slot mid-sentence without anything switching. On space, a word is
replaced outright when the keyboard is sure — and it is only sure when *where
your thumb landed* says the letters were a slip rather than a decision. Backspace
puts it back. The design is being worked out in
[`docs/design.md`](docs/design.md).

## What is here

| Path | |
|---|---|
| `app/src/main/java/.../BilingualKeyboardService.kt` | the `InputMethodService` |
| `app/src/main/java/.../KeyboardView.kt` | key drawing and hit-testing |
| `app/src/main/java/.../KeyboardLayout.kt` | layout definitions |
| `app/src/main/java/.../SuggestionStripView.kt` | the suggestion strip |
| `app/src/main/java/.../DictionarySuggestions.kt` | both languages, one ranking |
| `app/src/main/java/.../SetupActivity.kt` | setup, and the learned words: add, tick, forget |
| `app/src/main/java/.../TouchModel.kt` | what each tap nearly hit |
| `app/src/main/java/.../SpatialEditDistance.kt` | distance in slips, not edits |
| `app/src/main/java/.../SettingsActivity.kt` | sizes, timings, correction, vibration |
| `app/src/main/assets/wordlists/` | the wordlists, and where they came from |
| `scripts/build-wordlists.py` | how the wordlists are regenerated |
| `app/src/main/res/xml/method.xml` | one subtype, deliberately |
| `docs/android-ime-api.md` | what the platform gives an IME, and what it withholds |
| `docs/design.md` | the design document, in progress |

## Getting a build onto the phone

Every pull request builds a debug APK and posts a download link as a PR
comment. GitHub serves it as a zip; unzip and:

```sh
scripts/install.sh bilingual-keyboard-<sha>.apk
```

No uninstall step. Two things used to make one necessary, and both are handled:

- **Signature mismatch.** AGP generates a debug keystore per machine, so every
  CI runner signed with a different key and Android refused the update.
  `app/debug.keystore` is committed and shared by every build instead. It signs
  debug builds only and is not a trust anchor — releases must never use it.
- **The system keeps running the old keyboard.** Replacing the package kills
  the process, but `InputMethodManagerService` does not reliably rebind to the
  new service, so the update looks like it did nothing. The script re-selects
  the input method to force the rebind.

The debug build uses a `.debug` application ID suffix, so it coexists with a
locally built copy.

First time only: open **Bilingual Keyboard** from the launcher and enable it in
system settings. Keep a second keyboard installed — if this one crashes, the
phone becomes untypeable.

## Building locally

Needs a JDK 17+ and an Android SDK (compileSdk 35, build-tools 35). Point at it
via `local.properties` (`sdk.dir=/path/to/android-sdk`) or `ANDROID_HOME`.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

### With Nix

`flake.nix` pins what the workflows pin — temurin JDK 17, an Android SDK with
platform 35 and build-tools 35, and the Gradle the wrapper names — so there is
nothing to install and nothing to point at:

```sh
nix run .#ci                # tests, lint, assembleDebug: the Android workflow
nix run .#test              # or one step at a time
nix run .#lint
nix run .#release -- 0.2.0  # the release workflow's build, signed and verified
nix develop                 # the same toolchain, with ./gradlew in your hands
```

`nix run .#ci` copies the APK to `artifacts/` under the same name CI gives it,
so the install instructions above work unchanged on a locally built one. The
release app derives its `versionCode` from the version the same way the
workflow does, and signs with the committed debug key unless the four
`RELEASE_*` variables are set — again as the workflow does.

## Changelog

Newest first. Nothing is tagged yet, so everything is unreleased; the reasoning
behind each entry is in [`docs/design.md`](docs/design.md) under the decision it
names.

### Unreleased

- **A number layer, two presses of `?123` away** (D52). A calculator: ten digits
  in a dialpad block of equal, generously wide keys, `+ - × ÷ = % ( )` beside
  them, and both decimal separators — German and English disagree about which
  of `.` and `,` splits a number, so neither hides behind a hold. `*` and `/`
  are one hold behind `×` and `÷`. The layer key is a ring now — letters,
  symbols, numbers, letters — and says what the next press gives: `?123`, then
  `123`, then `ABC`. A numeric field opens on it, so a PIN pad is a PIN pad.
- **A field on the launcher screen adds anything to the personal store, spaces
  and all** (D51). Holding the + key remembers whatever lies between two spaces
  (D40), which cannot express a phrase — `Anna Maria`, `mit freundlichen
  Grüßen`, a street with a space in it. Typed into the new field it goes in as
  it stands, and the Quick tick beside it puts it straight on the menu the +
  key opens. A pasted line break or tab becomes a space, since an entry is one
  line of a file.
- **The enter key does what the field actually asked for, and says so** (D49).
  In a chat box — Telegram, and anything else built on a multi-line `EditText`
  — pressing it submitted the field and hid the keyboard instead of starting a
  new line, because the field's `IME_ACTION_DONE` was read and its
  `IME_FLAG_NO_ENTER_ACTION` was not. The flag wins now. Where the key really
  does perform an action it wears that action's glyph (`→`, `✓`, `⇥`, `⇤`)
  rather than a `↵` it is not going to honour, and where a field will neither
  take a newline nor perform an action the key is left off and the space bar
  takes its width. It stays on ordinary single-line fields: there it is the
  only way to submit a search, a login or a web form.
- **A suggestion too long for its slot loses its front, not its end** (D50).
  `…digkeitsbegrenzung` rather than `Geschwindigkeitsbe…`: the front is what
  you already typed and can see, the tail is what the keyboard is telling you.
  The `+` on the add-word offer is exempt — it says what the slot does, so it
  cannot be the thing that gets cut.

## Licence

GPLv3. Relicensed from MIT early in the project (decision D13) so that
AOSP-lineage keyboard source and GPL wordlists — the good German ones in
particular — are usable. Dictionary and model files carry their own provenance
and licence notes.
