# Morsel

A small Android floating card for choosing portions and deliberately requesting food from one Petlibro Air Smart Feeder (PLAF108). Android 11 or newer; English-only UI.

## Install and try

Download the signed **[Morsel v0.1.0 APK](https://github.com/evrenesat/morsel/releases/download/v0.1.0/app-release.apk)** from the [prerelease page](https://github.com/evrenesat/morsel/releases/tag/v0.1.0), open it on Android, and allow installation from your browser or file manager when Android asks. Open Morsel and choose **Try the demo** to explore without an account, network requests, or dispensing food.

This is a software-tested prerelease. Real Petlibro sign-in, shared-account access, Galaxy S21 / One UI behavior, and physical feeding still need owner validation. Demo and emulator results do not prove that a real feeder dispensed food.

Choose portions locally, then tap Feed once. Morsel does not queue or automatically repeat feeding requests. An uncertain result stays visible after reopening; check the feeder before acknowledging it and feeding again.

## Verification

The [release workflow](https://github.com/evrenesat/morsel/actions/runs/36991746027) passed formatting, lint, 91 JVM tests, 17 instrumented tests on each of Android API 30 and 36, restart and visual checks, and installation/launch of the downloaded signed APK. See the [final review](docs/final-review.md), [testing notes](docs/testing.md), and [release verification and signing fingerprint](docs/release.md).

## Development

Kotlin, Jetpack Compose, OkHttp, coroutines and DataStore. One app module, no backend, overlay permission, scheduling, or automatic feeding retries.

Requirements: JDK 17, Android SDK platform 36 and build-tools 36.0.0.

~~~bash
./gradlew --no-daemon spotlessCheck lintDebug testDebugUnitTest assembleDebug
bash scripts/ci-emulator.sh  # connected emulator and Android SDK tools required
~~~

CI runs the API 30/36 emulator matrix with fake transports only and uploads reports/screenshots. Version tags run full CI before signing and publishing, then download and launch the published APK. Durable screenshot files survive test-app removal; logcat is a fallback.

See [architecture](ARCHITECTURE.md), [privacy](docs/privacy.md), [implementation status](docs/implementation-status.md), and the [implementation plan](plans/implementation.md). GPL-3.0; see LICENSE and NOTICE for protocol attribution.
