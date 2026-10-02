# Review: validate ordinary reopen without changing settings

P1, high confidence — app/src/androidTest/java/io/evren/morsel/ProcessDeathAndLifecycleTest.kt, InFlightDismissReopenTest (introduced by 27f0aa5).

The test now responds to a reopened card failing to render UNKNOWN by calling graph.settingsStore.setDemoScenario(TIMEOUT_UNKNOWN) and then accepting a later successful render. This bypasses the supported user path: a user reopening a card does not perform that internal settings write. A broken reopen can therefore pass the required regression test, even though the failure log explicitly calls it a render bug. The original failure matters because an unresolved feed must remain visible and block another attempt. Logging the workaround does not make a passing test validate ordinary reopening.

Smallest fix: remove the test-only settings write and require UNKNOWN to appear after ordinary dismissal/relaunch, with bounded waits. Retain the existing assertions for one send attempt, same process, check status and acknowledgement. Use the first-emission diagnostics to fix any actual app or instrumentation-lifecycle cause; do not mutate product state to make assertions pass. A longer bounded wait is acceptable if measurements demonstrate scheduling delay, but no hidden refresh action.

Verification: existing local gates, then full API30/API36 CI. Inspect InFlightDismissReopenTest XML and logcat: it must pass without any settings kick. Also retain outside-tap dismissal and no pass-through assertions. Update docs/implementation-status.md honestly and commit/push the plan with the focused fix. ZCode implements; supervisor review remains required before release.
