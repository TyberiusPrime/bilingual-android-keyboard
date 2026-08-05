# bilingual-android-keyboard

An Android keyboard that is aware of two languages at once, instead of making
you switch between them.

**Status: it types, and it suggests.** German and English wordlists are queried
together on every keystroke, so a suggestion from either language can win a slot
mid-sentence without anything switching. Correction proper — replacing what you
actually typed, at a confidence worth acting on — is not written yet; the design
is being worked out in [`docs/design.md`](docs/design.md).

## What is here

| Path | |
|---|---|
| `app/src/main/java/.../BilingualKeyboardService.kt` | the `InputMethodService` |
| `app/src/main/java/.../KeyboardView.kt` | key drawing and hit-testing |
| `app/src/main/java/.../KeyboardLayout.kt` | layout definitions |
| `app/src/main/java/.../SuggestionStripView.kt` | the suggestion strip |
| `app/src/main/java/.../DictionarySuggestions.kt` | both languages, one ranking |
| `app/src/main/java/.../SetupActivity.kt` | setup, and the learned-word list |
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

## Licence

GPLv3. Relicensed from MIT early in the project (decision D13) so that
AOSP-lineage keyboard source and GPL wordlists — the good German ones in
particular — are usable. Dictionary and model files carry their own provenance
and licence notes.
