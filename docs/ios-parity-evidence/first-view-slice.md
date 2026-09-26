# First view slice — design contract, shell, Stations, settings, player

Task IDs / owner: VIS-01–06, RAD-01, RAD-06–07, SET-01 (all W) / opencode (2026-09-16)

Android baseline commit / build: `21782fd233b4ab089dc1e6ea56e758a2aa2a8477` (source read for palette, type, icons, chrome, controls, Stations, Settings, Now Playing)

iOS commit / build / device / OS: `ee947cb`, `4c88b38`, `3753476`; iPhone 17 simulator, iOS 26.5; Xcode 26.6

Fixture and reproduction steps:

```sh
cd ios
xcodegen generate
swift test --package-path CliampDesign   # 15 tests
swift test --package-path CliampCore     # 11 tests
xcodebuild build -project Cliamp.xcodeproj -scheme Cliamp \
  -destination 'platform=iOS Simulator,name=iPhone 17,OS=latest' CODE_SIGNING_ALLOWED=NO
xcodebuild test -project Cliamp.xcodeproj -scheme Cliamp \
  -destination 'platform=iOS Simulator,name=iPhone 17,OS=latest' CODE_SIGNING_ALLOWED=NO  # 3 app tests
```

Preview hooks (DEBUG only) drive the screens for capture: `-cliamp-preview-player` plays the first seed station and opens the player, `-cliamp-preview-playing` does the same without opening it, `-cliamp-preview-url <url>` plays any URL, `-cliamp-preview-directory` / `-cliamp-preview-custom` open those filters (and the custom add form).

Functional result and test link: design suite 15/15 (palette values, alpha-first hex, SVG path arcs/sweep/adjacent decimals, icon shape counts); core suite 11/11 (seed list, m3u parsing, model derived fields, time formatting); app suite 3/3 (package linkage, root construction, all six font faces registered). Stations fetched 15 live channels from `streams.m3u` on the simulator and fell back to the twelve-channel seed when parse/fetch fails (fallback path unit-tested via `parseGarbage`, live offline test still open). AVPlayer played the Lofi stream with ON AIR, buffering and error states observed.

Visual result and paired capture link: simulator captures reviewed during development — Stations in oxide-light and oxide, Now Playing (dark), Settings (dark). Paired Android captures do not exist yet (FND-02); gates stay unchecked.

Experience result and video/device notes: transport press travel renders in both themes; favourite star toggles in place; tab bar and rail switch at the landscape breakpoint. No device feel review, no video, no VoiceOver pass.

Persistence / offline / error checks: favourites, theme choice and all settings are in-memory for this slice; the app returns to "nothing playing" on relaunch. Offline playlist fallback is implemented but not exercised on-device. A failed stream surfaces "STREAM ERROR" text; no backoff yet (RAD-10).

Known difference or blocker / decision ID / next action: at the time of this slice the player had no ICY metadata, artwork, directory, search, queue or persistence; all but queue/search/persistence landed in later commits recorded in the activity log, and the codex review of the shell/model/radio code was resolved before any gate was set.

Reviewer / date: pending
