# Altitude++ for Android phones

Unofficial Android phone client for Altitude+: live 24/7 channel with guide, and Avalanche / Nuggets on-demand.
Built from [`docs/API.md`](../docs/API.md) and modeled on the Apple TV app in [`appletv/`](../appletv/).

**Status: scaffold. Builds and launches; sign-in and playback not yet tested against a real subscription.**

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

## DRM

Protected streams are decrypted only inside Android's Widevine module, using licenses Altitude+ issues to the
signed-in account. The app never extracts keys or touches decrypted video. Emulators generally only have
Widevine L3, so test live playback on a real phone.

## Open questions to test

- Does the Fire TV identity get a Widevine license on a phone (L1 vs L3, HDCP policy)?
- Does a phone signed in as "Fire TV" count against device or stream limits?
- Live license tokens last ~12 hours; very long sessions will need a re-fetch.
