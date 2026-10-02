# Implementation status

Worker: ZCode GLM-5.3-Flash on p100 (authorized). One implementation worker; supervising Codex chat reviews.

## Current state (after visual-acceptance fixes, commits b474f43..49f05fa)

All review findings to date are fixed with regression tests. Emulator evidence (per-test screenshots, phase logs) is preserved and uploaded on every run; the last commits are awaiting the next full CI matrix and supervisor review.

| Checkpoint / review | Commit | CI |
|---|---|---|
| Bootstrap (floating card, CI, contract docs) | a884040 | superseded |
| Domain/data with fault gates (JVM tests) | 80f5d4d | superseded |
| Full UI, art, EN/NL, accessibility, demo scenarios | b9ba545 | [36951136153](https://github.com/evrenesat/morsel/actions/runs/36951136153) green (pre-emulator era) |
| Checkpoint-2 storage fixes (DataStore extensions, trimming) | 67269e1 | FAILED (scope-restart test bug, fixed) |
| Fail-closed journal + storage-error gate | efc898d | [36955392704](https://github.com/evrenesat/morsel/actions/runs/36955392704) green, 77 unit tests |
| Checkpoint-3 fixes + instrumented suite | a8f9971 | recorded below |
| Emulator CI + release workflow + verify script | 1ead6ac..cedc886 | [36957103405](https://github.com/evrenesat/morsel/actions/runs/36957103405) falsely green — never trust job color alone |
| Real emulator gates + evidence pipeline | 2ceef07 | [36959990525](https://github.com/evrenesat/morsel/actions/runs/36959990525) FAILED 6/17 both APIs |
| Ack leaves unresolved UI; adb-driven restart phases | b474f43 | [36962065566](https://github.com/evrenesat/morsel/actions/runs/36962065566) FAILED 2/17 both APIs |
| Durable ack retires operation; evidence survives uninstall | eb3ed13 | [36965758511](https://github.com/evrenesat/morsel/actions/runs/36965758511) FAILED 3/17 both APIs (evidence pipeline still lossy) |
| Visual P2s (scene height, counter, Dutch) + watch flag + logcat mirror | ca3a548 | [36967722345](https://github.com/evrenesat/morsel/actions/runs/36967722345) FAILED 2/17 API36, 3/17 API30 — screenshots pinpointed every remaining race |
| Race fixes from on-device evidence | 49f05fa | [36969353064](https://github.com/evrenesat/morsel/actions/runs/36969353064) FAILED 6/17 API36, 5/17 API30 (FloatingWindow bounds, InFlight reopen, DemoUnconfirmed ack on 36) |
| Deterministic window bounds; leftover-card @After; mode-flip diagnosis | 072f033 | [36971344588](https://github.com/evrenesat/morsel/actions/runs/36971344588) FAILED 4/17 BOTH APIs — every failure root-caused from artifacts (below) |
| Success attribution by operation id; honest Boolean waits; real AVD profile; third evidence channel | this commit | pending CI |

## What run 36971344588's artifacts showed (all fixed in this commit)

- **DemoUnconfirmedFlowTest (both APIs) — the stale-success latch from plans/review-success-attribution.md.** The failure screenshot shows the SUCCESS panel after the acknowledgement: the previous class (DemoSuccessFlowTest) leaves `lastResolved = REPORTED_SUCCESS` in the same-process demo coordinator, and the ViewModel's success collector latched `successThisSession` during the NEW attempt's preflight window (dispatchMade=true, old success still lastResolved, nothing unresolved). The latched Done panel is masked by the unresolved panel, and takes over the moment the acknowledgement retires the operation — so the Feed button never returns. Fixed by attributing in-session success to the CURRENT attempt's operation id only (`currentAttemptOpId`), with the four regressions the review plan specified. DemoUnconfirmedFlowTest is itself the on-device regression: its scenario always starts from the stale success.
- **floatingCardIsSmallerThanDisplayOverHome + outsideTapDismisses… (both APIs).** The CI emulator-runner step set no hardware `profile`, so the AVD was 320x640 @160dpi: the card's 320dp width EQUALS the 320px display (the assertion is unsatisfiable), and the outside-tap target `(left-8)` coerced to x=0 — the status bar — so no ACTION_OUTSIDE ever reached the card. CI now creates the AVD with `profile: pixel_5` (1080x2340 @420dpi); assertions unchanged, now meaningful. This also exposed that earlier "passing" width assertions were measured against the buggy small-text-node bounds.
- **InFlightDismissReopenTest (both APIs).** The reopened card rendered the default real-mode state for the whole 20s assert budget — while settings provably stayed in demo mode (the 072f033 mode-flip logcat shows no flip, and the pre-reopen check reads demo=true). Separately, `assertNotNull(device.wait(Until.hasObject(…)))` accepts a plain `false` (Until.hasObject yields a Boolean; only a timeout yields null), so the earlier UNKNOWN/ack "assertions" passed without the panel being there. Fixed: every `Until.hasObject` wait is asserted `== true` with a named message; the reopen follows the sequence proven to render correctly in FloatingWindowTest (card-actually-left check, relaunch, wait for the Morsel window to own the foreground, then content asserts). Send-count and pid guarantees unchanged.
- **Evidence pipeline (API 36).** The logcat mirror lost the TAIL chunks of two shots (demo-offline-offline 54/59, landscape-card 94/96; no END marker, other processes' lines continue normally): logd silently dropped them under the burst. The mirror now paces its chunks, and every capture is ALSO copied to /data/local/tmp/morsel-screens under adopted shell identity — a channel that survives gradle's uninstall. ci-emulator.sh pulls that directory BEFORE decoding the logcat stream, so the incompleteness gate now fails only for shots that arrived through no channel.
- **FeedViewModelTest did not compile** (two nullable `submitGate` receivers) — the unit gate log (/tmp/gates12.log) failed before tests ran; fixed, 91 unit tests green locally.

## What the on-device evidence changed (commit 49f05fa)

Failure screenshots decoded from the run-36967722345 logcat mirror identified each remaining defect precisely; none were guesswork:

- **Acknowledgement retirement (fixed in eb3ed13, hardened in 49f05fa).** Acknowledging now retires the operation only after the journal records it: polling for that operation is cancelled, `unresolvedOperation` clears, `lastResolved` keeps the acknowledged entry. DemoUnconfirmedFlowTest passed on API 36 in run 36967722345 after this change. API 30 additionally hit a race where a poll tick reconciled the acknowledged operation and `persistOutcome` overwrote the resolution with success (failure screenshot shows the success panel); `persistOutcome` now refuses to rewrite a retired operation (memory + journal), and the poll loop re-checks its guard after the suspending history read. Demo prepare helpers now fail loudly if the scripted scenario never lands.
- **Floating window outside tap.** `Activity.onTouchEvent` → `Window.shouldCloseOnTouch` acts on `ACTION_OUTSIDE`, but nothing sets `FLAG_WATCH_OUTSIDE_TOUCH` on a floating activity window (dialogs set it themselves), so the theme attribute and `setFinishOnTouchOutside` alone are inert. The card sets the flag explicitly. Separately, the tests read the window bounds while the wrapping window was still composing (a click landed at (28,80)); they now wait for the window to reach card size first.
- **In-flight dismissal/reopen.** The rewritten test holds the single write on a test-only gate (`DemoFeederRepository.SendGate`), dismisses mid-request, waits for the coordinator's durable UNKNOWN (asserted), reopens in the same process, asserts the UNKNOWN panel with check-status and acknowledgement available, and continuously asserts exactly one send attempt (no replay). The earlier version passed without ever exercising cancellation.
- **Evidence pipeline.** Every capture is mirrored into the logcat (`MORSEL_SHOT` base64 chunks; `executeShellCommand` cannot do shell redirects, so the previous /data/local/tmp mirror silently produced nothing). `scripts/ci-emulator.sh` decodes the stream, fails on missing/incomplete shots, verifies the card owns the foreground before each host screenshot, creates the host-shot directory on both APIs, and uploads `instrument-*.log` phase logs.
- **Visual acceptance (plans/review-visual.md).** Cat/cup canvases had no intrinsic height and collapsed; `SceneArea` now gives them a definite bounded height (reduced on short layouts). The counter is numeric-only at large font with the localized plural on a full-width line and a full content description. Dutch evidence comes from `DutchLocaleEnglishUiTest` via per-app `LocaleManager`: the app is English-only by owner decision (plans/owner-english-only.md, Dutch resources removed), and the test asserts English strings on screen under a Dutch locale before capture. `SelectionVisualTest` captures nonzero kibble; `ImeVisualTest` captures the setup card with the IME raised and after Back-dismiss.

## Test evidence (local, p100: JDK 17, SDK 36)

```
./gradlew --no-daemon spotlessCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest
BUILD SUCCESSFUL; unit tests: 91 completed, 0 failed (this commit)
shellcheck scripts/*.sh: clean
```

Unit gates cover every fault path in plans/implementation.md including exact HTTP call counts; instrumented tests run in CI emulator jobs (see table).

## CI pipeline

- `ci.yml`: static job plus emulator jobs on API 30 and API 36. The emulator AVD uses `profile: pixel_5` — without a profile the emulator-runner produces a 320x640@160dpi screen on which the card's 320dp width equals the display width, making the floating-geometry assertions unsatisfiable. One script (`scripts/ci-emulator.sh`) owns test status and evidence collection: Gradle suite (process-death pair and visual-only classes excluded, they run as direct `adb shell am instrument` phases), then visual phases (API 36: seed, IME, selection, Dutch-locale-English with on-screen assertions, plus host shots for light/dark/2x-font/reduced-motion), then a real force-stop/relaunch process-death phase judged by `am instrument`'s own OK summary. Screenshots, XML/HTML reports, logcat and phase logs upload even on failure.
- In-app screenshot evidence reaches the host through three channels — the /data/local/tmp copies (shell identity, survive the gradle uninstall), the MORSEL_SHOT logcat mirror (decoded from both dumps), and the run-as pull — and the script fails the phase only for shots that arrived through none of them.
- `release.yml`: full CI gates via `workflow_call` on the exact tag → signed `assembleRelease` (secrets only, key materialized in runner temp and removed) → certificate pinned against `MORSEL_CERT_SHA256` → APK + `SHA256SUMS` prerelease → post-publication emulator job installs the published APK and runs `scripts/release-health-check.sh`. Publishing awaits supervisor review; no tag has been cut.

## Owner correction: English-only UI

All app UI is English-only (plans/owner-english-only.md). The Dutch resource file was removed; a Dutch system/app locale must render English, which `DutchLocaleEnglishUiTest` proves on-device. Earlier history in this file and DEVLOG that mentions EN/NL strings predates the correction.

## Explicitly NOT done (truth)

- Real Petlibro account, shared-account acceptance, physical dispensing, Galaxy S21/One UI behavior: NOT TESTED. No live API traffic has been generated.
- No APK release published; the signed release waits for supervisor review (workflow merged, unexercised).
- plans/owner-english-only.md and plans/review-success-attribution.md are implemented (Dutch resources removed + on-device English-under-Dutch proof; success attributed to the current attempt's operation id with the review's four JVM regressions), but their combined CI evidence is the pending run in the table above — not resolved until it is green with artifacts inspected.
- The pixel_5 AVD change alters every emulator job's screen geometry; the first run on it is the pending one, so visual evidence at the new size has not been reviewed yet.
- IME screenshots assert the keyboard state via `dumpsys input_method`; the capture-timing fix (drawing settle) has not yet been through a CI run.
- Landscape and IME evidence exists as in-app screenshots; physical-device layout review is the supervisor's.
