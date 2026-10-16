# Android release

Keep `io.github.hun8902.coolplayer.limited` and the existing signing key. Increase both `versionCode` and `versionName` in `android/app/build.gradle`. Update `android/RELEASE_NOTES.md`, build with the private signing properties, and commit/push the source before publication.

```sh
cd android
COOLPLAYER_SIGNING_PROPERTIES=/private/path/release.properties ./gradlew assembleRelease
cd ..
git tag android-v1.4.1
git push origin main android-v1.4.1
python3 scripts/release-android.py \
  --apk android/app/build/outputs/apk/release/app-release.apk \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0" \
  --notes android/RELEASE_NOTES.md --publish
```

Use the new version in the tag for each release. The script reads the package/version/minimum SDK from the signed APK, checks APK signing, creates `Cool-Player-Android.apk`, `android-update.json`, and `SHA256SUMS.txt`, uploads all three to a draft, then publishes it. GitHub authentication uses `GH_TOKEN` or the local Git credential helper; credentials and signing keys must stay out of the repository. Without `--publish` it only prepares the files.

The Android app reads public `android-v*` stable releases and their `android-update.json` assets, selects the highest compatible `versionCode`, and offers download. A release must include this metadata to appear in the app. APK SHA-256, package ID, increasing version code and installed signer are checked before Android's installer opens. macOS releases and prereleases are excluded.

Automatic checks occur on app launch at most once per day after a successful check. Download and installation remain explicit user actions. Android may request permission to install updates from Cool Player. The app never silently installs an APK. Existing versions before 1.4.0 need one manual APK update to obtain the updater.

After publishing, update the Android version and direct APK link in `docs/index.html` and README. Keep the Mac download pinned to the Mac release: the global latest release can be Android. GitHub Pages serves `main:/docs`.

The limited-distribution package is a separate installation from the legacy com.coolplayer.android app. Existing library data is not automatically migrated. Register eligible devices in Android Developer Console before limited distribution.
