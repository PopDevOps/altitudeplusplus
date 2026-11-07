# Altitude++ for Android phones

Unofficial Android phone client for Altitude+: live 24/7 channel with guide, and Avalanche / Nuggets on-demand.
Built from [`docs/API.md`](../docs/API.md) and modeled on the Apple TV app in [`appletv/`](../appletv/).

**Status: working on a real phone and subscription** — sign-in, live channel, on-demand, and saving videos.

## Build and install

Requirements: Android Studio (or the Android SDK with platform 37) and a JDK 17+.

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open `android/` in Android Studio and press Run. Requires Android 11+.

## How it works

| Area | File | Notes |
|---|---|---|
| Config, device profile | `AltitudeConfig.kt` | Presents as **Fire TV** (no Android identity exists). Web browser profile is a fallback in Settings. |
| HTTP / GraphQL | `api/ViewLiftClient.kt` | Raw JWT in `Authorization`, public `x-api-key`. |
| Sign-in | `auth/AuthService.kt` | Email OTP, token refresh (GraphQL then REST fallback). Tokens in app-private prefs. |
| Location | `location/LocationProvider.kt` | Phone location for the territory check; IP fallback if denied. |
| Streams | `playback/StreamResolver.kt` | Picks the `widevine` DASH entry; unencrypted clips use `hls`. |
| Player | `playback/PlayerActivity.kt` | Media3 ExoPlayer, Widevine with `X-AxDRM-Message` license header, multi-session for key rotation, picture-in-picture. |
| Guide | `guide/GuideRepository.kt` | Public XMLTV feed. |
| On-demand | `ondemand/OnDemandRepository.kt` | GraphQL `page` query with `FIRETV` layouts, paged rows. |
| Saving | `download/OfflineStore.kt`, `download/ClipFiles.kt` | Clips as MP4s in `Internal storage/A++`; replays downloaded ahead with Media3. |

## DRM

Protected streams are decrypted only inside Android's Widevine module, using licenses Altitude+ issues to the
signed-in account. The app never extracts keys or touches decrypted video. Emulators generally only have
Widevine L3, so test live playback on a real phone.

## Saving videos

Long-press a video and choose **Save / download ahead**.

- **Clips** (unencrypted) are saved as ordinary MP4 files in a top-level `A++` folder in internal storage,
  using the progressive MP4 Altitude+ offers (up to 720p). They play anywhere, fully offline. Creating a
  top-level folder needs Android's "All files access", which the app asks for the first time.
- **Full replays** (Widevine) are downloaded ahead inside the app and play from the phone, but **need internet
  to start**: each playback gets a normal streaming license. Altitude+ doesn't allow saved licenses — its
  license server (Axinom) answers persistent-license requests with *"the entitlement message does not allow
  persisting it"* for every device identity. The app doesn't work around this.
- Settings → **Check live channel**, or long-press → **Check offline playback**, asks the license server for a
  saved license and shows its actual answer.

## On-device tests

Instrumented tests run against the signed-in app on a connected phone. Install and run them with `am instrument`
rather than `connectedAndroidTest`, which uninstalls the app (and its sign-in) afterwards:

```sh
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class com.altitudeplusplus.android.SavedClipPlaybackTest \
  com.altitudeplusplus.android.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.altitudeplusplus.android.DownloadAheadTest -e replayId <id> -e useService true \
  com.altitudeplusplus.android.test/androidx.test.runner.AndroidJUnitRunner
```

`OfflineLicenseProbeTest` (`-e replayId <id>`) logs the license server's answer to a saved-license request for each identity.

## Open questions to test

- Does a phone signed in as "Fire TV" count against device or stream limits?
- Live license tokens last ~12 hours; very long sessions will need a re-fetch.
