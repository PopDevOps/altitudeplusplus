# Altitude++

Unofficial apps for [Altitude+](https://www.altitudeplus.com), the streaming service for Altitude Sports in Colorado. Their official apps have become somewhat cluttered over the last few years; this streamlines the interface and focus in on what's most important.

![Altitude++ on Apple TV](appletv/docs/screenshots/live.jpg)

| Platform | Folder | Status |
|---|---|---|
| Apple TV | [`appletv/`](appletv/) | Works: live 24/7 channel, guide, Avalanche and Nuggets on-demand. [Install guide](appletv/README.md). |
| Android phone | [`android/`](android/) | Scaffold: builds and launches; playback not yet tested. [README](android/README.md). |
| Android TV | — | Not started. [`docs/API.md`](docs/API.md) has what a port needs. |

> **Unofficial.** Altitude++ isn't affiliated with or endorsed by Altitude Sports & Entertainment, Kroenke Sports & Entertainment, or ViewLift. You need your own Altitude+ subscription and must be inside Altitude's broadcast territory. **No encryption is bypassed:** streams play through each platform's own DRM, the same way the official apps play them, and Altitude++ doesn't record, download, or share anything. It may stop working if Altitude+ changes its service. See [License and trademarks](#license-and-trademarks).

## Repository layout

- **`appletv/`:** the tvOS app, its installer, and Apple-specific docs.
- **`android/`:** the Android phone app (Kotlin, Compose, Media3).
- **`docs/API.md`:** how Altitude+ works (sign-in, location, live and on-demand streams, DRM, guide), for any platform.
- **`tools/probe.py`:** checks the Altitude+ API from any computer with Python 3, including as Apple TV or Fire TV.

## License and trademarks

**Code.** The source code is available under the [MIT License](LICENSE).

**Names, logos, and artwork.** This project doesn't own any of the names, logos, or artwork it shows, and the MIT License doesn't cover them:

- Altitude, Altitude Sports, and Altitude+, and their logos, belong to Altitude Sports & Entertainment.
- Team and league names and logos (the Colorado Avalanche, Denver Nuggets, NHL, NBA, and others) belong to their owners.
- The app icon and Top Shelf images are adapted from Altitude's logo.
- The screenshots show program artwork and logos from the Altitude+ service.

They're included only to identify the service and show what the app looks like.

**Encryption.** No encryption is bypassed. Protected streams (the live channel and full replays) are decrypted only inside the platform's own DRM (FairPlay on Apple TV), using licenses Altitude+ issues to your signed-in account, the same way the official apps work. This project never extracts keys and never handles decrypted video. Clips that Altitude+ serves without encryption play as they are.
