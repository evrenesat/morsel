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
