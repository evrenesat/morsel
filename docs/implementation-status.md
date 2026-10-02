# Implementation status

Worker: ZCode GLM-5.3-Flash on p100 (authorized). One implementation worker; supervising Codex chat reviews.

## Current state (evidence-proven test synchronization fixes, commit 457f3ca + this commit)

Both remaining CI failures are root-caused from run 36981516152's artifacts (below) and fixed test-side with no production change. Emulator evidence (per-test screenshots, phase logs, dual logcat streams) is preserved and uploaded on every run; the next full CI matrix adjudicates.

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
| Success attribution by operation id; honest Boolean waits; real AVD profile; third evidence channel | 4aa782a | [36974801304](https://github.com/evrenesat/morsel/actions/runs/36974801304) 16/17 BOTH APIs — only outside-tap DETECTION failed, dismissal itself proven by the failure screenshot (launcher foreground) |
| dumpsys-window foreground detection for FloatingWindowTest | 8269ba6 | [36976338217](https://github.com/evrenesat/morsel/actions/runs/36976338217) FAILED — the in-app dumpsys read never matched the launcher (Back dismissals that passed in 36974801304 now failed; screenshots again show the launcher foreground); the catch hid the cause, and the API 36 InFlight reopen render stalled again |
| OR-combined foreground signals with full diagnostics; logged settings-kick for the starved reopen render | 27f0aa5 | [36977619841](https://github.com/evrenesat/morsel/actions/runs/36977619841) FAILED 2/17 BOTH APIs — but the diagnostics named everything: after the outside tap ALL three signals still reported the Morsel window focused (the card never dismissed), and the InFlight render stall survived the settings kick |
| Non-translucent floating popup (true wrap-content window); ViewModel first-emission diagnostics | 4abc3e6 | [36979628029](https://github.com/evrenesat/morsel/actions/runs/36979628029) FAILED 1/17 API30, 2/17 API36 — outside tap still not dismissed (dumpsys signal dead), InFlight stall again on 36; a system vold log-storm rotated the 16M buffer mid-phase and lost most phase-1 screenshots |
| Working dumpsys parse; rotation-proof phase-1 logcat streamer; dead local-tmp channel removed; sharper VM diagnostics | 457f3ca | [36981516152](https://github.com/evrenesat/morsel/actions/runs/36981516152) FAILED exactly outsideTap + InFlight UNKNOWN on BOTH APIs, plus one incomplete API36 shot — both failures root-caused from its artifacts (below) |
| Evidence-proven test synchronization: measured outside tap with delivery logging; compose-clock pumping for the raw-restart reopen; second logcat stream | 1ddf8ce | [36986559378](https://github.com/evrenesat/morsel/actions/runs/36986559378) GREEN — 17/17 BOTH APIs, 0 skipped, all evidence channels complete |
| Mechanism-correction comments (input-surface close-on-touch, not ACTION_OUTSIDE); stream2 in upload paths | this commit | pending CI |

## What runs 36981516152 + 36986559378 proved (both failures root-caused and green since 1ddf8ce/36986559378)

- **InFlightDismissReopenTest reopen stall — the compose test rule's frame clock, not the app.** The failure screenshot shows the card in the exact `stateIn` default state (real mode, no DEMO tag, selection 0) for the whole 10s wait, while the logcat shows the reopened activity RESUMED and its FeedViewModel emitting the real state (`demo=true, screen=FEED`) 93–265ms after creation on both APIs. Mechanism, verified against the compose-ui-test 1.11.4 sources: while any compose test rule is active, `WindowRecomposerPolicy.withFactory({ recomposer })` overrides the PROCESS-WIDE window recomposer factory, so the raw `startActivity` relaunch composes under the rule's `TestMonotonicFrameClock` — which advances only inside compose test APIs (`waitUntil`, assertions). The test polled with plain `device.wait`, so the reopened window received zero frames after its initial composition; pass/fail was a ~100ms race between the VM's first emission and that initial composition (hence the API-dependent intermittency). Fixed test-side: the three recomposition-dependent waits now poll the same UiAutomator conditions through `compose.waitUntil`, which advances the clock per iteration and still fails loudly on timeout. No production change; no refresh action; coordinator/pid/send-count assertions unchanged (plans/review-reopen-test.md satisfied: ordinary dismissal/relaunch, no settings kick).
- **outsideTapDismissesWithoutTouchingWhatIsBeneath — the tap was delivered into the window but within the close-on-touch slop.** The failure timeline shows the card stayed RESUMED and focused for the full 10s (the launcher-only failure screenshot is the `@After` pressHome, which runs before the failure capture — the card never left), so all three focus signals were honest. Run 36986559378's delivery log then named the exact mechanism: the floating window's INPUT surface extends beyond the visible card frame (window frame == accessibility root == `Rect(100,384-980,1968)` on API 30, `Rect(100,413-980,1997)` on API 36), so a tap beside the card is delivered to THIS window as a plain DOWN/UP — `ACTION_OUTSIDE` never fires because the window is its own touch target. Dismissal is the platform's `Window.shouldCloseOnTouch` (verified against AOSP android-11 sources): it closes on `ACTION_OUTSIDE` or on an `ACTION_UP` landing beyond the window-touch slop outside the decor bounds — the UP branch is what actually fires. The old 8px-from-frame tap sat within that slop and was swallowed silently; the dim provably works (pixel comparison of the captures measures exactly the 0.24 dim). Fixed test-side: the test measures frame/attrs/decor, logs every `dispatchTouchEvent` (action, raw coordinates, handled) through a `Window.Callback` delegator, and taps in the middle of the measured frame-to-display-edge gap (50px from the frame — beyond the slop on both APIs), asserted outside both frame and root. The no-passthrough guarantee holds structurally: the morsel input surface covers the display, so the launcher beneath never receives the tap — the dimmed area absorbs it. No theme flags changed.
- **Evidence pipeline — one silent mid-stream drop.** API 36 lost chunks 422–431 of `demo-unknown-unknown` from the continuous stream (no logd warning; other processes' lines kept flowing), and a phase-1 shot has no other channel after gradle's uninstall, so the per-file gate failed the job. `ci-emulator.sh` now runs TWO independent `adb logcat` readers (logd delivers to each separately) and one merged decoder across both streams and both ring-buffer dumps; the phase fails only for shots that arrived through no channel, and the run-as pull runs before that gate so a current-install shot delivered only by run-as counts.

## Concise history (superseded hypotheses removed; rows in the table carry per-run detail)

- The popup window is a genuine wrap-content window since 4abc3e6 (`windowIsFloating`, non-translucent): the smaller-than-display assertion passes on both APIs and the captures show the card floating over the home screen with the 0.24 dim.
- Foreground detection after dismissals is dumpsys-based and working since 457f3ca (the ICU-regex parse bug is fixed); Back dismissals pass consistently.
- Success attribution by current operation id (4aa782a) closed the stale-success latch; its JVM regressions and the on-device DemoUnconfirmedFlowTest pass.
- English-only UI is proven on-device under a Dutch locale (`DutchLocaleEnglishUiTest`, per-app LocaleManager).
- Evidence survives gradle's uninstall only via the logcat mirror; `/data/local/tmp` copies never worked (app-UID DAC) and are gone; phase-1 shots cannot be re-pulled after the phases 3–5 reinstall.
- The earlier full-screen-window translucency hypothesis (runs 36971344588..36977619841) is superseded by the frame-margin finding above; earlier per-run narratives are in git history.

## What the on-device evidence changed (commit 49f05fa)

Failure screenshots decoded from the run-36967722345 logcat mirror identified each remaining defect precisely; none were guesswork:

- **Acknowledgement retirement (fixed in eb3ed13, hardened in 49f05fa).** Acknowledging now retires the operation only after the journal records it: polling for that operation is cancelled, `unresolvedOperation` clears, `lastResolved` keeps the acknowledged entry. DemoUnconfirmedFlowTest passed on API 36 in run 36967722345 after this change. API 30 additionally hit a race where a poll tick reconciled the acknowledged operation and `persistOutcome` overwrote the resolution with success (failure screenshot shows the success panel); `persistOutcome` now refuses to rewrite a retired operation (memory + journal), and the poll loop re-checks its guard after the suspending history read. Demo prepare helpers now fail loudly if the scripted scenario never lands.
- **Floating window outside tap.** `Activity.onTouchEvent` → `Window.shouldCloseOnTouch` acts on `ACTION_OUTSIDE`, but nothing sets `FLAG_WATCH_OUTSIDE_TOUCH` on a floating activity window (dialogs set it themselves), so the theme attribute and `setFinishOnTouchOutside` alone are inert. The card sets the flag explicitly. Separately, the tests read the window bounds while the wrapping window was still composing (a click landed at (28,80)); they now wait for the window to reach card size first.
- **In-flight dismissal/reopen.** The rewritten test holds the single write on a test-only gate (`DemoFeederRepository.SendGate`), dismisses mid-request, waits for the coordinator's durable UNKNOWN (asserted), reopens in the same process, asserts the UNKNOWN panel with check-status and acknowledgement available, and continuously asserts exactly one send attempt (no replay). The earlier version passed without ever exercising cancellation.
- **Evidence pipeline.** Every capture is mirrored into the logcat as `MORSEL_SHOT` base64 chunks (executeShellCommand cannot do shell redirects, so a file-copy channel from the app cannot reach the host). `scripts/ci-emulator.sh` decodes the mirror, verifies the card owns the foreground before each host screenshot, creates the host-shot directory on both APIs, and uploads `instrument-*.log` phase logs.
- **Visual acceptance (plans/review-visual.md).** Cat/cup canvases had no intrinsic height and collapsed; `SceneArea` now gives them a definite bounded height (reduced on short layouts). The counter is numeric-only at large font with the localized plural on a full-width line and a full content description. Dutch evidence comes from `DutchLocaleEnglishUiTest` via per-app `LocaleManager`: the app is English-only by owner decision (plans/owner-english-only.md, Dutch resources removed), and the test asserts English strings on screen under a Dutch locale before capture. `SelectionVisualTest` captures nonzero kibble; `ImeVisualTest` captures the setup card with the IME raised and after Back-dismiss.

## Test evidence (local, p100: JDK 17, SDK 36)

```
./gradlew --no-daemon spotlessCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest
BUILD SUCCESSFUL; unit tests: 91 completed, 0 failed (this commit)
shellcheck scripts/*.sh: clean
```

CI [36986559378](https://github.com/evrenesat/morsel/actions/runs/36986559378) (1ddf8ce): static job green; emulator jobs green on API 30 and API 36 with 17/17 tests, 0 failures, 0 skipped (XML inspected); all 12 in-app shots complete on API 36 including the previously lost demo-unknown-unknown; five API 36 visual phases each `OK (1 test)`; both previously failing tests pass with their delivery/geometry logs in the logcat stream.

Unit gates cover every fault path in plans/implementation.md including exact HTTP call counts; instrumented tests run in CI emulator jobs (see table).

## CI pipeline

- `ci.yml`: static job plus emulator jobs on API 30 and API 36. The emulator AVD uses `profile: pixel_5` — without a profile the emulator-runner produces a 320x640@160dpi screen on which the card's 320dp width equals the display width, making the floating-geometry assertions unsatisfiable. One script (`scripts/ci-emulator.sh`) owns test status and evidence collection: Gradle suite (process-death pair and visual-only classes excluded, they run as direct `adb shell am instrument` phases), then visual phases (API 36: seed, IME, selection, Dutch-locale-English with on-screen assertions, plus host shots for light/dark/2x-font/reduced-motion), then a real force-stop/relaunch process-death phase judged by `am instrument`'s own OK summary. Screenshots, XML/HTML reports, logcat and phase logs upload even on failure.
- In-app screenshot evidence reaches the host through the `MORSEL_SHOT` logcat mirror — read by TWO independent continuous `adb logcat` streams during phase 1 (logd silently dropped a 10-chunk window for a single reader in run 36981516152) plus both ring-buffer dumps — and the run-as pull for shots of the current install. One merged decoder fails the phase only for shots that arrived through none of these channels.
- `release.yml`: full CI gates via `workflow_call` on the exact tag → signed `assembleRelease` (secrets only, key materialized in runner temp and removed) → certificate pinned against `MORSEL_CERT_SHA256` → APK + `SHA256SUMS` prerelease → post-publication emulator job installs the published APK and runs `scripts/release-health-check.sh`. Publishing awaits supervisor review; no tag has been cut.

## Owner correction: English-only UI

All app UI is English-only (plans/owner-english-only.md). The Dutch resource file was removed; a Dutch system/app locale must render English, which `DutchLocaleEnglishUiTest` proves on-device. Earlier history in this file and DEVLOG that mentions EN/NL strings predates the correction.

## Explicitly NOT done (truth)

- Real Petlibro account, shared-account acceptance, physical dispensing, Galaxy S21/One UI behavior: NOT TESTED. No live API traffic has been generated.
- No APK release published; the signed release waits for supervisor review (workflow merged, unexercised).
- plans/review-reopen-test.md is implemented and now has GREEN CI evidence (36986559378): ordinary dismissal/relaunch, no settings kick, UNKNOWN panel with check-status and acknowledgement on screen (evidence screenshot inspected).
- The outside-tap dismissal is green on both APIs with the delivery log proving the mechanism (in-window DOWN/UP closed by the platform's UP-beyond-slop branch; the launcher never receives the tap).
- Landscape and IME evidence exists as in-app screenshots; physical-device layout review is the supervisor's.
