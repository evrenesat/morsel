# Implementation status

Worker: ZCode GLM-5.3-Flash on p100 (authorized). One implementation worker; supervising Codex chat reviews.

## Checkpoints

### Checkpoint 1 — bootstrap (this commit)

Done, verified locally on p100 (JDK 17.0.20, SDK platform 36 + build-tools 36.0.0):

- Gradle 8.13 wrapper (distribution SHA256 pinned), AGP 8.13.2, Kotlin 2.2.21, Compose BOM 2026.06.01, all versions pinned in `gradle/libs.versions.toml`.
- **Pin deviation, documented:** plan suggested OkHttp latest; `com.squareup.okhttp3:okhttp:5.5.0` (okhttp-android) requires compileSdk 37, incompatible with the pinned SDK 36 and AGP 8.13.2 max. Pinned **OkHttp 5.1.0** (newest line 5.x compatible with compileSdk 36). Only that pin changed.
- Floating `FeedPopupActivity` (windowIsFloating theme, bounded 336dp card), original vector launcher icon, `allowBackup=false` with exclusion rules, GPL-3.0 LICENSE + NOTICE with upstream attribution.
- `docs/api-contract.md`: protocol fields extracted from pinned upstream commit with permalinks + synthetic fixtures. Key finding: pinned source reads only `type`/`recordTime`/`actualGrainNum` from work records — **no request-ID correlation field exists in observed evidence**, so Morsel's reconciler will never convert time+amount alone into success; correlation code paths will be exercised via fake scenarios.
- CI: `.github/workflows/ci.yml` (push main + PRs; spotlessCheck, lintDebug, testDebugUnitTest, assembleDebug; SDK 36 bootstrap; reports uploaded on failure; contents:read only; concurrency cancel; 45m timeout).

Verification commands and results (local, p100):

```
./gradlew --no-daemon spotlessCheck lintDebug testDebugUnitTest assembleDebug
BUILD SUCCESSFUL in 2m 12s (57 actionable tasks)
```

CI run: pending first push (link recorded after push).

## Remaining

- Step 2: protocol client, vault/stores, coordinator + journal, reconciler, unit-test gates (single-write under faults).
- Step 3: full UI, artwork, en/nl, accessibility, demo scenarios.
- Step 4: production client wiring behind explicit setup; demo isolation.
- Step 5: emulator CI (API 30/36, no KVM on p100), release workflow + verify-apk.sh, unsigned release build until signing secrets exist.
- Supervisor review, then signing key + secrets (owner/supervisor), then signed v0.1.0 prerelease.

## Explicitly NOT done (truth)

- Real Petlibro account, shared-account acceptance, physical dispensing, Galaxy S21/One UI behavior: NOT TESTED. No live API traffic has been generated.
- No APK release published yet; per instruction the signed release waits for supervisor review and signing secrets.
