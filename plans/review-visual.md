# Visual acceptance review — b474f43

Supervisor inspected actual API36 screenshots from run36962065566 under /root/operations/morsel/evidence/36962065566/api36. The floating card is visible over the launcher and process-death screenshot displays UNKNOWN with Check status/Done/acknowledgement. Two main tests still fail; no final approval.

## P2 — Cat and cup Canvas have no height
Both light/dark screenshots show only a nose/whiskers/handle near the counter, with the entire intended cat/cup illustration blank. SceneArea in ui/FeedCard.kt gives its Row a heightIn(min96,max170) but CatScene/CupScene children receive only Modifier.weight(), providing width and no explicit nonzero height. Canvas has no intrinsic height, so its proportional body/head/eyes/cup geometry collapses vertically. Fix the scene sizing with a concrete bounded scene height and child height/fillMaxHeight so both canvases have real dimensions. Preserve small-screen scrolling and reduce decorative height for constrained layouts. Verify actual light/dark screenshots show the complete fluffy cat and cup with legible contrasting eyes.

## P2 — Large-font portion label breaks within words
At font_scale2.0 the center reads separate lines '0', 'portion', 's', leaving the main count hard to scan and wasting vertical space. Make the middle counter a large numeric-only count, with the localized portion unit outside the narrow counter row (or another explicit layout that never splits words). Preserve full localized TalkBack quantity and 48dp +/- hit targets. Verify zero, multi-digit16, normal/2x fonts and landscape; Feed and settings must remain reachable by scrolling.

## Locale evidence is invalid
dutch-card.png has 'Food time', '0 portions', 'Feed' and English footer. The current shell cmd locale invocation did not set app locale. Use supported locale control (inspect adb shell cmd locale help, or API33+ LocaleManager applicationLocales in a test), relaunch as needed, assert a Dutch string exists before capturing. Do not label an English screenshot Dutch. Keep physical-device testing honestly pending.

## Follow-up evidence
Capture after the fixes: light/dark cat+cup, nonzero kibble selection, 2x font, Dutch, and restored uncertainty. Main-suite screenshots are currently lost when Gradle uninstalls its APK; capture/export them before cleanup or use installed direct instrumentation for that suite with robust test-failure detection. Upload direct phase instrument-*.log too. Keep no-op shell errors from masquerading as successful visual coverage.

Implement via ZCode, run existing local checks + full emulator matrix, update test docs, commit/push. Supervisor reviews actual images and reports before release.
