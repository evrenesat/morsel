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
