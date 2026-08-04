# bilingual-android-keyboard

An Android keyboard that is aware of two languages at once, instead of making
you switch between them.

**Status: scaffold.** The app builds, installs, and types. The interesting parts
— multilingual prediction and correction — are not written yet; the design is
being worked out in [`docs/design.md`](docs/design.md).

## What is here

| Path | |
|---|---|
| `app/src/main/java/.../BilingualKeyboardService.kt` | the `InputMethodService` |
| `app/src/main/java/.../KeyboardView.kt` | key drawing and hit-testing |
| `app/src/main/java/.../KeyboardLayout.kt` | layout definitions |
| `app/src/main/res/xml/method.xml` | one subtype, deliberately |
| `docs/android-ime-api.md` | what the platform gives an IME, and what it withholds |
| `docs/design.md` | the design document, in progress |

## Getting a build onto the phone

Every pull request builds a debug APK and posts a download link as a PR
comment. GitHub serves it as a zip; unzip and:

```sh
adb install -r bilingual-keyboard-<sha>.apk
```

Debug builds from different CI runs are signed with different debug keys, so
installing over an older build fails with a signature mismatch — `adb uninstall
de.coonabibba.bikeyboard.debug` first. The debug build uses a `.debug`
application ID suffix, so it coexists with a locally built copy.

Then: open **Bilingual Keyboard** from the launcher, enable it in system
settings, and select it as the active input method. Keep a second keyboard
installed — if this one crashes, the phone becomes untypeable.

## Building locally

Needs a JDK 17+ and an Android SDK (compileSdk 35, build-tools 35). Point at it
via `local.properties` (`sdk.dir=/path/to/android-sdk`) or `ANDROID_HOME`.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.
