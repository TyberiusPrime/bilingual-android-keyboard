# bilingual-android-keyboard

A very personal Android keyboard that is aware of two languages (English and
German) at once, instead of making you switch between them.

**Status: it types, it swipes, it suggests, and it corrects.** - It is my daily driver.

Notes: Space may correct your input, shown by animation and optional vibration.

Tap space twice for a full stop.

The plus key allows quick insertion of learned words. Press long to learn the last word.
Move the cursor by dragging from space. 

Tap-and-a-half on h allows line wise scrolling.

Holding a suggested word enters it without space! No more trouble with Haustür, Haustier...

Swipe left on backspace to delete a word.

Swipe up on shift to toggle casing of last word (tristate, lower, Upper, UPPER).

'vs' at the end of a word leads to "'s" suggestion.

# Warning

This is very much vibe coded house plant software.
I have no intention on getting this into any app store or the like.
Audit and build at your own risk.


# What this fixes for me

- constant language annoyance (especially since the umlauts & letters change when
switching german & english on most android keyboards I could find).
- umlauts replacing 'popup'-numbers when switching to german
- accidentally pressing '.' when trying to enter a space. That happen to me all the damn time
- uncertain what was typed because the letters fade immediately. This keyboard has a gradient
over the last few letters!. Can be manually enabled in password fields
- suggestions in url fields (firefox, that's also a search bar so stop turning off autocomplete!...)
- emails and other 'long inputs'. I have multiple, complicated addresses, and I don't enjoy
keyboards that half complete them, but then leave off the top level domain or such. Just
let me teach you the right ones, mkay?
- possessions ('s), a 'treat this completion as the stem' function (no space, so 
you can enter plural/case ending yourself).
- haptics only on correction
- correction only when it's very certain about the intented word.
- layer key always in the same place (unlike some other keyboards I tried).
- cursor movement trigger by distance, not time, so swiping on space feels good!

# Cool stuff

- sub-letter Position aware correction 
- no automatic learning
- see your swipe! See your last few registered key presses!
- timings adjustable to your liking.
- purple in suggention bar means: 'space will correct to this'.


## What is here

The design decisions [`docs/design.md`](docs/design.md).

It's a bit of a big vibed mess though. That's claude for you.

But hey, I got this thing together with only a single crashing
version.


| Path | |
|---|---|
| `app/src/main/java/.../BilingualKeyboardService.kt` | the `InputMethodService` |
| `app/src/main/java/.../KeyboardView.kt` | key drawing and hit-testing |
| `app/src/main/java/.../KeyboardLayout.kt` | layout definitions |
| `app/src/main/java/.../SuggestionStripView.kt` | the suggestion strip |
| `app/src/main/java/.../DictionarySuggestions.kt` | both languages, one ranking |
| `app/src/main/java/.../SetupActivity.kt` | setup, and the learned-word list |
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
comment. Side load it however your os requires.


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

GPLv3, so that AOSP-lineage keyboard source and GPL wordlists — the good German
ones in particular — are usable. Dictionary and model files carry their [own
provenance and licence notes](app/src/main/assets/wordlists/PROVENANCE.md).
