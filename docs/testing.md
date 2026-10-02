# Testing

Morsel is tested without any real Petlibro account, device or feeding command. All transport in tests is fake (scripted fakes or local MockWebServer). Unit and instrumented suites run in GitHub Actions on every push to main and every PR.

## Local commands

```bash
./gradlew --no-daemon spotlessCheck lintDebug testDebugUnitTest assembleDebug
./gradlew --no-daemon connectedDebugAndroidTest   # needs an emulator/device
bash scripts/verify-apk.sh <apk> <cert-sha256>    # release APK check
```

## Unit gates (JVM, `app/src/test`)

85 tests as of commit a8f9971, all green locally (`BUILD SUCCESSFUL`; 85 completed, 0 failed). They assert the plan's safety gates, including HTTP call counts under faults:

- envelope shapes (zero/null/object/array data), lowercase MD5 Unicode password digest, 0/17 portion rejection;
- exactly one aggregate write for 3 portions; concurrent 20 taps produce exactly one write; plus/minus selection never writes;
- read retry (single re-login on 1009) separated from the write; write timeout/5xx/malformed/auth/disconnect never replay; redirects refused;
- journal failure before AND after the write: nothing sent or UNKNOWN shown, entry stays unresolved;
- unreadable/undecodable journal fails closed: reads throw, writes refuse, dispatch blocks with zero transport calls, nothing silently resets;
- scope restart: a joined-cancel reopen keeps dispatch + outcome (regression for CI 36951827220);
- acknowledged entries count as resolved when trimming; genuinely unresolved entries preserved; ≥26 acknowledged operations never break trimming;
- restore maps persisted DISPATCHING to UNKNOWN; recreate-after-dispatch never resends; missing saved serial never substituted; offline blocks with no delayed send;
- baseline history failure stays unconfirmed; duplicates/out-of-order/scheduled competing records never confirm; correlated mismatch never tops up; logout/rebind cannot bypass the unresolved journal;
- ViewModel: fresh session starts at zero with Feed disabled, clamp to 0/cap, in-session Done latch attributed to the CURRENT attempt's operation id (a restored or previous-attempt success never latches — plans/review-success-attribution.md), demo routing, blocked-attempt notices, read-failure preservation, cancelled dispatch reconnects as UNKNOWN with no resend.

## Instrumented gates (emulator, `app/src/androidTest`)

Real production code on a device/emulator: production DataStore stores (isolated dirs), production AES-GCM Keystore vault, production coordinator + demo repository, production floating window.

- `StorageInstrumentedTest` — journal persists across scope restart in noBackupFilesDir; corrupt journal fails closed on device; settings round-trip; vault round-trip, ciphertext (not plaintext) on disk, corrupt data reads as signed-out.
- `FloatingWindowTest` — genuinely floating window smaller than the display over the launcher; Back dismisses; outside tap dismisses without touching what is beneath; dismissal + reopen keeps the same process and coordinator. The outside tap is measured, not guessed: the test reads the system-recorded window frame (`AccessibilityWindowInfo.getBoundsInScreen`), window attributes (FLAG_WATCH_OUTSIDE_TOUCH/FLAG_DIM_BEHIND) and decor geometry, logs every touch event the activity dispatches via a `Window.Callback` delegator (run 36981516152 proved the old 8px-from-root tap landed inside the invisible window padding margin — real window surface), then taps in the middle of the measured frame-to-display-edge gap, asserted outside both frame and root.
- `DemoFlowTest` — all six scenarios through the production ViewModel and coordinator: success with correlation (Done latches, Feed does not re-arm), accepted-unconfirmed (Check status + explicit acknowledgement), rejected, timeout/UNKNOWN, correlated mismatch (no automatic top-up), offline (disabled with hint). Demo banner visible; Feed disabled at zero.
- `FailureStatusTest` — a blocked attempt shows the visible localized "nothing was sent" failure text through the production ViewModel and card.
- `InFlightDismissReopenTest` — the single write is held on a test-only gate (`DemoFeederRepository.SendGate`), the card is dismissed mid-request, the dismissal is confirmed at the window level, the coordinator's durable UNKNOWN is asserted, the card reopens in the same process via raw `startActivity` and the test waits for the Morsel window to own the foreground before asserting the UNKNOWN panel with check-status and acknowledgement available, and exactly one send attempt is asserted continuously (no replay). The reopened window is polled through `compose.waitUntil` wrapping UiAutomator conditions: the compose test rule overrides the process-wide window recomposer factory, so the raw relaunch composes under the rule's test frame clock, which only advances inside compose test APIs — a plain `device.wait` loop would starve the pending recomposition forever (root cause of the run 36981516152 stalls). No settings kick; no refresh action.
- `LandscapeUsabilityTest` — controls stay present and reachable after a landscape recreation.
- Visual evidence classes (API 36 visual phase; all UI English-only by owner decision): `VisualSetupTest` seeds demo mode; `ImeVisualTest` asserts the IME over the setup sign-in card via `dumpsys input_method` and captures it plus the post-Back form; `SelectionVisualTest` captures a nonzero kibble selection; `DutchLocaleEnglishUiTest` switches the per-app locale with `LocaleManager` (API 33+) and asserts ENGLISH strings ON SCREEN before capturing — the app is English-only by owner decision (plans/owner-english-only.md); it ships no Dutch resources, so a Dutch system/app locale must still render English.

Every capture reaches the host through the `MORSEL_SHOT` base64 logcat mirror — carried by TWO independent continuous `adb logcat` streams during the gradle phase (one reader silently dropped a 10-chunk window in run 36981516152; logd delivers to each reader separately) plus both ring-buffer dumps — and the run-as pull for shots of the current install. Phase-1 shots cannot be re-pulled after Gradle's uninstall, so the streams are their only channel; the merged decoder in `scripts/ci-emulator.sh` fails the phase only for shots that arrived through none of these paths. Host screenshots (light/dark/2x font/reduced-motion/process-death relaunch) require the card to own the foreground first (`dumpsys window` check), so a launcher capture can never pass as evidence.

CI runs these on API 30 and API 36 emulators (`.github/workflows/ci.yml`, `emulator` job, `pixel_5` hardware profile — a real-phone display, without which the card's 320dp width equals the whole 320x640@160dpi default screen) and uploads XML/HTML reports, screenshots and sanitized logcat even on failure.

## CI evidence (exact runs)

| Run | Commit | Result |
|---|---|---|
| [36955392704](https://github.com/evrenesat/morsel/actions/runs/36955392704) | efc898d | GREEN — static checks, 77 unit tests, debug build |
| [36957103405](https://github.com/evrenesat/morsel/actions/runs/36957103405) | cedc886 | FALSELY GREEN — 15 tests / 8 failures in XML; never trust job color alone |
| [36959990525](https://github.com/evrenesat/morsel/actions/runs/36959990525) | 2ceef07 | FAILED — 6/17 both APIs (stuck unresolved panel, shade tap, dead phases) |
| [36962065566](https://github.com/evrenesat/morsel/actions/runs/36962065566) | b474f43 | FAILED — 2/17 both APIs (ack kept card stuck; outside tap wrong target) |
| [36965758511](https://github.com/evrenesat/morsel/actions/runs/36965758511) | eb3ed13 | FAILED — 3/17 both APIs; evidence pipeline still lossy |
| [36967722345](https://github.com/evrenesat/morsel/actions/runs/36967722345) | ca3a548 | FAILED — API36 2/17, API30 3/17; failure screenshots pinpointed every race; visual P2s verified on-device (Dutch card real, 2x font clean, kibble visible) |
| [36969353064](https://github.com/evrenesat/morsel/actions/runs/36969353064) | 49f05fa | FAILED — API36 6/17, API30 5/17 (FloatingWindow text-node bounds, InFlight reopen, DemoUnconfirmed ack on 36) |
| [36971344588](https://github.com/evrenesat/morsel/actions/runs/36971344588) | 072f033 | FAILED — 4/17 both APIs, each root-caused from artifacts: stale-success latch (plans/review-success-attribution.md), 320x640 AVD geometry, InFlight reopen render + Boolean-wait masking, logcat mirror tail loss |

## Explicitly not tested

Real Petlibro account sign-in, session conflicts, a real PLAF108 (binding, discovery, manual feeding write), feeder history correlation on live data, and Galaxy S21 / One UI floating behavior. Demo and fake transports only; no live API traffic has ever been generated by this project.
