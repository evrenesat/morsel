# Releases

APK releases are built and signed only by the GitHub Actions `Release` workflow (`.github/workflows/release.yml`) on `v*` tags (and `workflow_dispatch`, which runs the gates but publishes nothing unless it is a tag ref). Releases start as **prerelease** until the owner validates the app on a real feeder.

## Pipeline

1. **Gates job** — the full CI workflow (`workflow_call`: static checks, unit tests, API 30 + API 36 emulator suites) must pass on the exact tagged commit. Signing never starts on a failing commit.
2. **Release job** (only for `evrenesat/morsel`, `contents: write`) —
   - decodes `MORSEL_KEYSTORE_BASE64` into `$RUNNER_TEMP` (never the workspace), `chmod 600`;
   - runs `assembleRelease` with `MORSEL_STORE_PASSWORD`, `MORSEL_KEY_ALIAS`, `MORSEL_KEY_PASSWORD` from repository secrets; Gradle reads them from the environment (`app/build.gradle.kts`), so local builds stay unsigned;
   - verifies the APK's signing certificate SHA-256 equals `MORSEL_CERT_SHA256` (uppercase hex, no colons) before anything is published;
   - writes `SHA256SUMS`, removes the key file from the runner (`if: always()`), then publishes the APK + checksums as a prerelease via `gh release`. Any earlier step failure aborts the job before publishing.
3. **Post-publication health check** — downloads the exact published APK and `SHA256SUMS`, re-verifies checksum, certificate and package identity, then installs and launches the published APK in a fresh GitHub-hosted emulator (`scripts/release-health-check.sh`; setup/demo only, no account) and uploads the evidence. A failed check fails the workflow visibly; the release stays a prerelease until the owner validates it.

Fork PRs never see signing secrets: the release workflow has no `pull_request` trigger and the publish job is repository-gated.

## Verifying an installed APK

```bash
bash scripts/verify-apk.sh app-release.apk <MORSEL_CERT_SHA256>
```

Checks signature certificate match, package name `io.evren.morsel`, and prints the version. The expected fingerprint equals the repository secret `MORSEL_CERT_SHA256` and the fingerprint recorded at key creation.

## Signing key custody

The PKCS12 keystore lives outside this checkout (private operations storage plus an off-machine backup). It is never committed, never printed, and only reaches CI as a base64 repository secret, materialized in runner temp and deleted after each run. To rotate: create a new key, update the five repository secrets, and cut a new release — installed apps signed with the old certificate will need uninstall/reinstall.

## Updating and rollback

Install a release APK over an existing install only when it is signed with the same certificate and has an equal-or-higher `versionCode`. Android does not support certificate-changing or version-downgrading installs; rolling back means uninstalling (which deletes local settings, journal and credentials) and installing the older APK, which stays published on the releases page.

## Versioning

`versionCode` must increase monotonically with every published APK (`app/build.gradle.kts`). Current: versionCode 1, versionName 0.1.0.
