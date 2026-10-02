# FND-01 — foundation decisions and runnable project

Task ID / owner: FND-01 / opencode (2026-09-16)

Android baseline commit / build: `21782fd233b4ab089dc1e6ea56e758a2aa2a8477` (inspected only; no device audit yet)

iOS commit / build / device / OS: `d894f39` scaffold + the CI/docs commit that follows it; iPhone 17 simulator, iOS 26.5; Xcode 26.6 (17F113), XcodeGen 2.45.4

Fixture and reproduction steps:

```sh
cd ios
xcodegen generate
swift test --package-path CliampCore
xcodebuild build -project Cliamp.xcodeproj -scheme Cliamp \
  -destination 'platform=iOS Simulator,name=iPhone 17,OS=latest' CODE_SIGNING_ALLOWED=NO
xcodebuild test -project Cliamp.xcodeproj -scheme Cliamp \
  -destination 'platform=iOS Simulator,name=iPhone 17,OS=latest' CODE_SIGNING_ALLOWED=NO
xcrun simctl install <device> "$(find ~/Library/Developer/Xcode/DerivedData/Cliamp-*/Build/Products/Debug-iphonesimulator -maxdepth 1 -name Cliamp.app)"
xcrun simctl launch <device> stream.cliamp.mobile
```

Functional result and test link: package tests 2/2 passed (`swift test`), app tests 2/2 passed (`xcodebuild test`), unsigned simulator build succeeded, app launched to the placeholder shell. Gate F: P (local). Warnings are errors on both paths: app targets via `SWIFT_TREAT_WARNINGS_AS_ERRORS` / `GCC_TREAT_WARNINGS_AS_ERRORS`, CliampCore via `-warnings-as-errors` in its manifest (verified in the `swiftc` invocations for both `swift test` and the Xcode build). CI workflow `.github/workflows/ios.yml` pins `macos-26` + Xcode 26.6 + XcodeGen 2.45.4; its first GitHub run is pending a push from the owner.

Visual result and paired capture link: not applicable — the placeholder shell has no product visuals; VIS work starts in phase 1. Gate V: NA.

Experience result and video/device notes: not applicable — no product interactions exist yet. Gate X: NA.

Persistence / offline / error checks: not applicable to the scaffold.

Decisions recorded (ios/README.md): iOS 18.0 floor (DEC-03 surfaces), universal iPhone + iPad portrait/landscape, SwiftUI + Swift 6, XcodeGen generated project, app + local SPM packages, unsigned simulator CI, automatic device signing with no repo credentials. Device matrix: CI on iPhone 17 simulator; visual review on iPhone 17e / 17 Pro Max / iPad mini / iPad Pro 11; physical iPhone named by owner before `RAD-11`.

Known difference or blocker / decision ID / next action: CI has not executed on GitHub yet; run it on the next push and move FND-01 to `D` if green. XcodeGen 2.45.4 is pinned in CI but not enforced locally against a different version; `minimumXcodeGenVersion` guards only the manifest schema. A codex `gpt-6-astra` review of the first two commits (2026-09-16) found four issues — package warnings-as-errors not enforced, a wrong Clang warnings-as-errors key, device-matrix references pointing nowhere, and stale tracker prose — all fixed in the follow-up commit. FND-02 (Android reference capture) and FND-03 (audio spike) are the next dependency-ready rows.

Reviewer / date: pending
