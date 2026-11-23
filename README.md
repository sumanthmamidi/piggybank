# PiggyBank

On-device SMS spend tracker. See [PLAN.md](PLAN.md) for the design and
[TEST_CASES.md](TEST_CASES.md) for agreed behaviour.

## Layout (M0)

```
shared/      Kotlin Multiplatform: money & income maths (core/), SQLite schema v1 (sqldelight/),
             database drivers (data/), Compose UI shell (ui/). Targets: Android, JVM (tests), iOS.
androidApp/  Android entry point, manifest hardening, permission allowlist check.
```

For M0, everything shared lives in one `shared` module. It gets split into
core/parser/data/backup/ui modules when those parts grow (M1–M4).

## Build

Needs JDK 17. The Android app also needs the Android SDK (platform 36, build-tools,
platform-tools), via Android Studio or `sdkmanager`, with `ANDROID_HOME` set or
`sdk.dir` in `local.properties`.

```bash
./gradlew :shared:jvmTest              # shared logic and schema tests, no SDK needed
./gradlew :androidApp:assembleDebug    # APK at androidApp/build/outputs/apk/debug/
./gradlew :androidApp:check            # includes verifyPermissions (no INTERNET, allowBackup=false)
./gradlew :androidApp:installDebug     # install on a connected phone
```

If the wrapper can't download Gradle on your network, a local Gradle 8.14 works the same
(`gradle :shared:jvmTest`).

## Release

Release builds are signed with the key in `~/.piggybank-release/` (via the git-ignored `keystore.properties`;
in CI, repository secrets). Every push builds a signed APK and app bundle in GitHub Actions.
Google Play setup: [docs/PLAY_STORE.md](docs/PLAY_STORE.md).
