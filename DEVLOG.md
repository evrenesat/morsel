# Development log

## 2026-10-02
- Authorized Morsel as a public Android project, implemented by ZCode GLM-5.3-Flash on p100.
- Prepared decision-complete handoff from supplied proposal. Public repository and CI signed APK delivery replace the proposal's private distribution.
- Real account/physical feeder/S21 checks require later owner access; no unattended food dispensing is authorized.
- p100 lacks /dev/kvm and has limited free disk, so use GitHub-hosted emulator CI.

## 2026-10-02 (continuation pass)
- Fixed CI 36951827220: joined (cancelAndJoin) the old DataStore scope before reopening a file; regression kept.
- Journal now fails closed on corruption: no ReplaceFileCorruptionHandler, undecodable payload throws JournalReadException and refuses writes; coordinator latches storageError and blocks all sending with a clear EN/NL status; zero transport calls asserted.
- Applied checkpoint-3 review: blocked-attempt notices (nothing-was-sent wording), read-failure status, cancellation after durable dispatch records UNKNOWN once without retry.
- Committed the first worker's preserved work: floating-window wrap fix, SettingsStore directory-injected test constructor, full androidTest suite.
- CI now runs instrumented tests on API 30/36 emulators with report/screenshot/logcat artifacts; release workflow + scripts/verify-apk.sh added; publishing still awaits supervisor review.

## 2026-10-02 (emulator gate + visual acceptance pass, commits b474f43..49f05fa)
- Supervisor review plans review-emulator-release.md and review-visual.md implemented with on-device evidence at every step (no green-color trust).
- Acknowledgement now retires the operation only after the journal records it (polling cancelled, unresolvedOperation cleared, lastResolved keeps the acknowledged entry); persistOutcome refuses to rewrite a retired operation after the API 30 evidence showed a late poll tick overwriting an acknowledged resolution with a fake success. Unit tests: ack during polling stays resolved through every tick; acknowledgement returns the session to the deliberate Feed state.
- Outside tap: AOSP Window.shouldCloseOnTouch needs ACTION_OUTSIDE, which a floating activity never receives without FLAG_WATCH_OUTSIDE_TOUCH (dialogs set it themselves; the theme attribute alone is inert). Card sets the flag explicitly. Tests now wait for the card-sized window before reading bounds (an earlier run tapped (28,80) against a composing window).
- InFlightDismissReopenTest rewritten around a test-only DemoFeederRepository.SendGate: request held mid-flight, card dismissed, durable UNKNOWN asserted at the coordinator, reopen in the same process, UNKNOWN panel with check-status/ack available, exactly one send attempt asserted continuously.
- Evidence pipeline: screenshots mirror into logcat (UiAutomation.executeShellCommand does not interpret shell redirects - the /data/local/tmp mirror silently produced nothing); ci-emulator.sh decodes MORSEL_SHOT chunks, fails on missing/incomplete shots, requires card foreground before host shots, uploads instrument-*.log. Verified the decoder against synthetic logcat locally.
- Visual review: cat/cup scenes had no intrinsic height (collapsed); definite bounded height now, reduced on short layouts. Counter is numeric-only with the localized plural on a full-width line (no mid-word breaks at 2x) plus full TalkBack quantity. Dutch evidence via per-app LocaleManager asserting "Eettijd" on screen before capture; invalid `cmd locale` shell route removed. Selection (kibble) and IME captures added.
- Run 36967722345: 15/17 passing on API 36, 14/17 on API 30, every remaining failure pinpointed by its own screenshot; fixes pushed in 49f05fa, next CI run pending at time of writing.

## 2026-10-02 (failure-attribution + evidence-integrity pass, this commit)
- Run 36971344588 (072f033) failed 4/17 on BOTH APIs; every failure root-caused from its artifact, none from job color.
- DemoUnconfirmedFlowTest (both APIs): the failure screenshot shows the SUCCESS panel after acknowledgement. DemoSuccessFlowTest leaves lastResolved=REPORTED_SUCCESS in the same-process demo coordinator, and the ViewModel success collector latched during the NEW attempt's preflight window. This is precisely the P1 in plans/review-success-attribution.md. Fixed by attributing in-session success to the current attempt's operation id (currentAttemptOpId, assigned only from submit's result, with a one-shot latest-state check for a rapidly-resolved poll); the review's four JVM regressions added (91 unit tests green).
- FloatingWindow width/outside-tap (both APIs): the CI emulator-runner step set no hardware profile, so the AVD was 320x640@160dpi — card width EQUALS display width and the outside-tap target coerced to x=0 (status bar). CI now uses profile: pixel_5; assertions unchanged and now meaningful. The earlier "passing" width checks had been measuring the buggy small-text-node bounds.
- InFlightDismissReopenTest (both APIs): the reopened card rendered the default real-mode state for the whole assert budget while settings provably stayed in demo mode (mode-flip logcat silent, pre-reopen check true); and assertNotNull(device.wait(Until.hasObject(...))) accepts a plain false, so earlier waits had been passing without the panel. All Until.hasObject waits now assert ==true with named messages, and the reopen follows the sequence proven to render in FloatingWindowTest: confirm the dismissed card left the foreground, relaunch, wait for the Morsel window to own the foreground, then assert content. Send-count and pid guarantees unchanged.
- Evidence pipeline (API 36): the logcat mirror lost the TAIL chunks of two shots (54/59 and 94/96 present, no END marker, other processes' lines continue) — logd drops under burst. Screenshots now pace chunks and ALSO copy to /data/local/tmp/morsel-screens under adopted shell identity (survives gradle's uninstall); ci-emulator.sh pulls that directory BEFORE the logcat decode so the incompleteness gate only fails for shots no channel delivered.
- FeedViewModelTest compile fixed (nullable submitGate receivers); local gates: spotlessCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest BUILD SUCCESSFUL, shellcheck clean.
- Not yet proven: the combined fixes' CI run on the new pixel_5 AVD (geometry changes every emulator evidence capture); status docs keep this explicit.
