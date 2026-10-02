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

### Checkpoint 2 — domain/data layer with fault gates (this commit)

Done, verified locally on p100:

- `PetlibroClient` (strict envelope handling, single-shot write, redirects refused, finite timeouts, no logging of bodies), `PetlibroFeederRepository` (reads re-login once on 1009; write never retries), `AuthManager` + `CredentialStore`/`CredentialVault` (AES-GCM Keystore; instrumented Keystore tests still pending), `SettingsStore` + `DataStoreFeedJournal` (no-backup storage), `FeedCoordinator` (atomic dispatch guard, journal-before-write, restore DISPATCHING→UNKNOWN, read-only polling at 3/10/25 s with injectable timing), `HistoryReconciler` (correlation-only confirmation), `DemoFeederRepository` (six labelled scenarios, isolated from real journal/serial/credentials).
- **59 JVM unit tests green**, covering the plan's unit gates: envelope shapes, MD5 Unicode, 0/17 rejection, exactly one write under 20 concurrent taps and under timeout/500/malformed/auth/disconnect faults, redirect refusal, journal failure before AND after the write, restore-never-resends, missing serial fail-closed, offline no delayed send, uncorrelated records never confirm, correlated mismatch no top-up, logout/rebind cannot bypass unresolved journal. Full-stack coordinator→client→MockWebServer tests included.

```
./gradlew --no-daemon spotlessCheck lintDebug testDebugUnitTest assembleDebug
BUILD SUCCESSFUL in 1m 6s (60 actionable tasks); tests: 59 completed, 0 failed
```

CI run: link recorded after push.

### Checkpoint 3 — complete UI, art, localization, demo scenarios (this commit)

Done, verified locally on p100:

- `FeedPopupActivity` hosts `FeedCard` (header with cat name/gear 48dp, original Canvas cat + cup art with independent ears/tail/eyes, blinking/attention/sending/waiting/happy/unsure moods, 48dp counter buttons, Feed N portions, honest status area, quiet footer), `SetupCard` (demo route without credentials; real sign-in with session-conflict note; discovery auto-binds exactly one PLAF108, multi requires explicit pick, zero shows setup message), `SettingsCard` (cat name, lower-only cap 1..16, haptics, reduce motion, demo scenario picker, sign out).
- Application-scoped `AppGraph` behind `MorselGraph`/`FeedingCoordinator`/`MorselSettingsStore` interfaces; real and demo worlds share nothing (separate repositories, journals, serials).
- ViewModel actions read cached source state (no stale-UI decisions); plus/minus never reach the coordinator; feed gating includes onboarding, unresolved ops, in-session success latch, demo-offline.
- Full English + Dutch strings incl. plurals; TalkBack labels, live region status, decorative art excluded from semantics; reduce-motion setting + system animator scale 0 both freeze decorative animation.
- Unit tests now 68 green (8 ViewModel tests added; all previous coordinator/client/fault gates intact).

```
./gradlew --no-daemon spotlessCheck lintDebug testDebugUnitTest assembleDebug
BUILD SUCCESSFUL in 1m 21s (60 actionable tasks); tests: 68 completed, 0 failed
```

CI run: link recorded after push.

## Remaining

- Step 2: protocol client, vault/stores, coordinator + journal, reconciler, unit-test gates (single-write under faults).
- Step 3: full UI, artwork, en/nl, accessibility, demo scenarios.
- Step 4: production client wiring behind explicit setup; demo isolation.
- Step 5: emulator CI (API 30/36, no KVM on p100), release workflow + verify-apk.sh, unsigned release build until signing secrets exist.
- Supervisor review, then signing key + secrets (owner/supervisor), then signed v0.1.0 prerelease.

## Explicitly NOT done (truth)

- Real Petlibro account, shared-account acceptance, physical dispensing, Galaxy S21/One UI behavior: NOT TESTED. No live API traffic has been generated.
- No APK release published yet; per instruction the signed release waits for supervisor review and signing secrets.
