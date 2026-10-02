# Implementation status

Worker: ZCode GLM-5.3-Flash on p100 (authorized). One implementation worker; supervising Codex chat reviews.

## Current state (after checkpoint-3 corrections, commit a8f9971)

All four review findings to date are fixed with regression tests; CI is green on the last reviewed commit.

| Checkpoint | Commit | CI |
|---|---|---|
| Bootstrap (floating card, CI, contract docs) | a884040 | superseded |
| Domain/data with fault gates (59 JVM tests) | 80f5d4d | superseded |
| Full UI, art, EN/NL, accessibility, demo scenarios | b9ba545 | [36951136153](https://github.com/evrenesat/morsel/actions/runs/36951136153) green |
| Checkpoint-2 storage fixes (DataStore extensions, trimming) | 67269e1 | [36951827220](https://github.com/evrenesat/morsel/actions/runs/36951827220) FAILED (scope-restart test bug) |
| Fail-closed journal + storage-error gate | efc898d | [36955392704](https://github.com/evrenesat/morsel/actions/runs/36955392704) green, 77 unit tests |
| Checkpoint-3 fixes + instrumented suite | a8f9971 | recorded below after completion |

## Review fixes applied

- **Checkpoint-2 (plans/review-checkpoint2.md)** — DataStore filenames end `.preferences_pb` (P1); trimming counts acknowledged entries as resolved and preserves genuinely unresolved ones (P2); zero-selection acceptance (fresh session starts at 0, empty cup, Feed disabled until plus) implemented and unit-tested (b9ba545/67269e1).
- **CI 36951827220 root cause** — a new DataStore must not open a file until the previous store's scope is fully joined; tests now `cancelAndJoin` before reopening (efc898d). Regression kept.
- **Journal corruption fail-closed (supervisor recovery prompt)** — removed `ReplaceFileCorruptionHandler`; undecodable payloads throw `JournalReadException` on read and refuse writes instead of silently resetting to empty state. `FeedCoordinator.restore` latches `storageError`; `submit` returns `Blocked(STORAGE_ERROR)` before any preflight traffic (asserted: zero transport calls). Card shows a clear EN/NL status with no Feed button (efc898d).
- **Checkpoint-3 (plans/review-checkpoint3.md)** — blocked pre-send outcomes (offline, serial missing, wrong model, no binding, preflight failed, journal write failed) now show localized EN/NL notices saying nothing was sent; `checkStatus` READ_FAILED keeps the unresolved operation visible and explains itself; stale notices clear on a fresh attempt/acknowledgement. Caller cancellation after the durable dispatch records UNKNOWN via one bounded non-cancellable journal write and stays blocking; never retried, resolved outcomes never overwritten (a8f9971).

## Test evidence (local, p100: JDK 17.0.x, SDK 36)

```
./gradlew --no-daemon spotlessCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest
BUILD SUCCESSFUL; unit tests: 85 completed, 0 failed (a8f9971)
```

Unit gates cover every fault path in plans/implementation.md including exact HTTP call counts; instrumented suite (production DataStore/Keystore/floating window/demo flows/failure text) compiles and runs in CI emulator jobs below.

## CI pipeline (this commit)

- `ci.yml`: static job (formatting, lint, unit tests, debug build) plus **emulator jobs on API 30 and API 36** (`reactivecircus/android-emulator-runner@v2`, KVM perms, no-window) running `connectedDebugAndroidTest`; XML/HTML reports, screenshots and sanitized logcat uploaded even on failure.
- `release.yml` (not yet exercised — publishing awaits supervisor review): gates → signed `assembleRelease` using repository secrets `MORSEL_KEYSTORE_BASE64`, `MORSEL_STORE_PASSWORD`, `MORSEL_KEY_ALIAS`, `MORSEL_KEY_PASSWORD`, `MORSEL_CERT_SHA256`; keystore materialized only in runner temp and removed after; certificate pinned against `MORSEL_CERT_SHA256` before publishing; APK + `SHA256SUMS` attached as prerelease on `v*` tags; `scripts/verify-apk.sh` verifies package identity and certificate.

## Explicitly NOT done (truth)

- Real Petlibro account, shared-account acceptance, physical dispensing, Galaxy S21/One UI behavior: NOT TESTED. No live API traffic has been generated.
- No APK release published yet; per instruction the signed release waits for supervisor review (release workflow merged but unexercised).
- Emulator CI results for a8f9971: recorded here with exact run links and numbers once the first emulator run completes.
