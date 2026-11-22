# Beta test guide

## Android (5 Eye Yeti, 5IY Pocket, Little Yeti)

**Quick beta (sideload):** every push builds `5IY.apk`, `5IY-Pocket.apk` and `Little-Yeti.apk` (and `Wildwood-Hall.apk`, the game, which is still in development and not part of the beta)
(GitHub → Actions → *Android app* → the run → Artifacts). Testers allow "install unknown apps"
for their browser or file manager and open the APK. These builds are signed with the public test
key in `android/app/foxbox-debug.keystore`: fine for testing, never for Google Play.

**Play internal testing (recommended):**
1. Make an upload key and add it to the repository secrets (`android/play/release-checklist.md`).
   Only then does CI build `5IY-release.aab`: a bundle signed with the public test key would make
   Play adopt that key as your upload key, so CI refuses to build one without the real key.
2. Play Console → create the app → *Testing → Internal testing* → upload the `.aab`, add testers
   by email (up to 100), share the opt-in link.
3. Each new beta: raise `versionCode` in `android/app/build.gradle.kts` (now 18, version 1.7.0).

5IY Pocket (`app.fiveiy.pocket`) and Little Yeti (`app.fiveiy.kids`) are separate Play listings
when you're ready; they're APK-only for now. Little Yeti targets children, so its listing goes
through Google Play's Families policy (ages 5 to 10).

## iOS (5 Eye Yeti)

TestFlight: see `ios/README.md` → *TestFlight beta* (four secrets, then run the workflow by hand).
The iOS version is the first port (title screen and the studio's instruments); see that README for
what is still Android-only.

## Automated beta check

The *Beta check* workflow runs 5 Eye Yeti, 5IY Pocket and Little Yeti on emulators (Wildwood Hall is still in development and left out) (Pixel 6, a small phone, a
tablet) and the *iOS app* workflow runs the iOS app on simulators (a Pro Max, an SE, an iPad). Each
opens every main screen and saves screenshots plus crashes and errors as artifacts.

## What to ask testers

- Device, Android/iOS version, and headphones / speaker / interface used.
- Any crash (5 Eye Yeti offers to share a crash report on the next launch).
- Audio: crackles, dropouts, latency on the pads, anything unexpectedly loud or quiet.
- Text cut off or too small, buttons hard to hit, anything under the camera notch.
- Saving: projects, notebooks and sketches still there after closing and reopening.
