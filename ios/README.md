# 5 Eye Yeti for iOS

The iOS app shares its sound core with the Android app: the engines, the
sequencer, the transport and the flowers live in the Kotlin Multiplatform module
`android/shared`, built into `Shared.xcframework`. The screens are native SwiftUI
(`Sources/`), drawn in the same 16-bit console style.

## Building

On a Mac with Xcode 16 and JDK 17:

```sh
cd android && ./gradlew :shared:assembleSharedDebugXCFramework
cd ../ios && brew install xcodegen && xcodegen generate
open FiveEyeYeti.xcodeproj
```

Pick a simulator and run. To run on your own iPhone, choose your team under
Signing & Capabilities (a free Apple ID works for 7-day installs; TestFlight and
the App Store need a paid Apple Developer account).

CI (`.github/workflows/iOS app`) builds it for the simulator on every push and
uploads the app plus screenshots.

## TestFlight beta

The *iOS app* workflow has a `testflight` job that archives a signed Release build and
uploads it to App Store Connect. It runs only when you start the workflow by hand
(GitHub → Actions → iOS app → Run workflow) and these repository secrets are set
(Settings → Secrets and variables → Actions):

| Secret | Where it comes from |
| --- | --- |
| `APPLE_TEAM_ID` | developer.apple.com → Membership (10 characters) |
| `APPSTORE_API_KEY_ID` | App Store Connect → Users and Access → Integrations → App Store Connect API: create a key with the **Admin** role (needed so Xcode can create the signing certificate and profile) |
| `APPSTORE_ISSUER_ID` | the same page, above the keys list |
| `APPSTORE_API_KEY_P8_BASE64` | the downloaded `AuthKey_XXXX.p8`, as `base64 -i AuthKey_XXXX.p8` |

Once: create the app in App Store Connect (My Apps → +) with the bundle ID
`app.foxbox.fiveeyeyeti`, then add yourself and your testers under TestFlight. Each run
uploads a new build numbered with the run number; it shows up in TestFlight after Apple
processes it (usually 10 to 30 minutes). External testers need a one-time beta review.

The app declares no tracking and no data collection (`Resources/PrivacyInfo.xcprivacy`)
and no non-exempt encryption, so uploads don't stop at export-compliance questions.

## What's in this first version

- Title screen, then the studio: every block on the rail, the XY pad, key /
  octave / scale and the chord buttons (hold to play), pads for Pickup and Minds,
  play / stop and tempo.
- Same engines and sound as Android (Gusts, Reflections, Leaves, Minds, Bumble,
  Pickup), mixed with the same flower rack, reverb and limiter.

Still to port from Android: projects and saving, the Jam / Clips bar and
recording, the sound drawer and presets, sequencers, Pickup's banks and chops,
Flowers, Clibbins (and the eye, and the hall), audio tracks, MIDI.
