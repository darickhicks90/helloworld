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

## What's in this first version

- Title screen, then the studio: every block on the rail, the XY pad, key /
  octave / scale and the chord buttons (hold to play), pads for Pickup and Minds,
  play / stop and tempo.
- Same engines and sound as Android (Gusts, Reflections, Leaves, Minds, Bumble,
  Pickup), mixed with the same flower rack, reverb and limiter.

Still to port from Android: projects and saving, the Jam / Clips bar and
recording, the sound drawer and presets, sequencers, Pickup's banks and chops,
Flowers, Clibbins (and the eye, and the hall), audio tracks, MIDI.
