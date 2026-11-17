# Publishing 5 Eye Yeti on Google Play

1. **Create an upload key** (once, on your computer, and keep it safe and private):
   `keytool -genkeypair -v -keystore foxbox-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`
2. **Give CI the key:** in GitHub → Settings → Secrets and variables → Actions, add:
   * `FOXBOX_UPLOAD_KEYSTORE_BASE64`: the output of `base64 -w0 foxbox-upload.jks`
   * `FOXBOX_UPLOAD_STORE_PASSWORD`, `FOXBOX_UPLOAD_KEY_ALIAS` (`upload`), `FOXBOX_UPLOAD_KEY_PASSWORD`
   Without these, builds are signed with the public test key. That's fine for
   trying the app, but those builds can't be uploaded to Play.
3. **Package name:** `app.foxbox` must be unique on Play. If it's taken, change
   `applicationId` in `app/build.gradle.kts` (for example `com.yourname.foxbox`).
4. **Build:** push to GitHub. The *Android app* workflow produces
   `5IY-release.aab` (upload this to Play) and `5IY.apk` (for sideloading).
5. **Play Console:** create the app, turn on *Play App Signing*, fill in the
   listing from `listing.md`, the privacy policy from `privacy-policy.md` and the
   data-safety answers, then upload the `.aab` to *Internal testing* first.
6. **Each update:** raise `versionCode` (and `versionName`) in `app/build.gradle.kts`.
