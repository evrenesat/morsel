# Morsel
A small native Android floating card for selecting portions and explicitly requesting food from one Petlibro Air Smart Feeder (PLAF108).

Implementation is in progress. No physical feeder/account or Galaxy S21 validation has been performed. Follow [the implementation plan](plans/implementation.md) and [status](docs/implementation-status.md).

Kotlin, Jetpack Compose, OkHttp, coroutines and DataStore; Android 11+. One app module, no backend, Home Assistant, overlay permission, schedules or automatic feeding retries.

Public source and APK releases are authorized. GPL-3.0; protocol adaptation attribution must accompany releases.

## Building and testing

Requirements: JDK 17, Android SDK platform 36 + build-tools 36.0.0 (or let CI provision them).

```bash
./gradlew --no-daemon spotlessCheck lintDebug testDebugUnitTest assembleDebug
./gradlew --no-daemon connectedDebugAndroidTest   # emulator/device needed
```

Install the debug APK on a device to try the app; use **Try the demo** on the setup card — the demo simulates a feeder with no account, no network and no food. See [docs/testing.md](docs/testing.md), [docs/privacy.md](docs/privacy.md) and [docs/release.md](docs/release.md). Release APKs are signed only in GitHub Actions; verify with `scripts/verify-apk.sh`.

