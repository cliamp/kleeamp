# Third-party notices

kleeamp mobile ships under the licence in `LICENSE`. The components below do
not. They are redistributed under their own terms.

## Bundled in the APK

| Component | Licence | Notes |
| --- | --- | --- |
| Poppins | SIL Open Font License 1.1 | Full text in `licenses/Poppins-OFL.txt`. Redistributing the font files requires shipping this notice. |
| JetBrains Mono 2.304 | SIL Open Font License 1.1 | Full text in `licenses/JetBrainsMono-OFL.txt`. Redistributing the font files requires shipping this notice. |
| AndroidX (core, lifecycle, activity, compose, datastore) | Apache License 2.0 | Copyright The Android Open Source Project |
| AndroidX Media3 (ExoPlayer, session, HLS, okhttp datasource) | Apache License 2.0 | Copyright The Android Open Source Project |
| OkHttp 5 | Apache License 2.0 | Copyright Square, Inc. |
| kotlinx.serialization, kotlinx.coroutines | Apache License 2.0 | Copyright JetBrains s.r.o. |
| Kotlin stdlib | Apache License 2.0 | Copyright JetBrains s.r.o. |

Apache 2.0 permits redistribution in a proprietary product provided the licence
and attribution are preserved. This file is that attribution.

## Assets

The cliamp mark (the eight-bar spectrum) is taken from `Cliamp.svg` in the
cliamp desktop project, which is MIT licensed and held by the same copyright
holder. It is used here under that ownership, not under the MIT grant.

## Network services

Neither of these is bundled; the app talks to them at runtime.

- `radio.cliamp.stream` for the cliamp channel list and listener statistics.
- `radio-browser.info` for the community station directory. Their API asks
  clients to identify themselves and to report plays back to the click
  counter; the app does both. Station metadata is contributed by their
  community and is not covered by this project's licence.
