# Emulator and release gate review — cedc886
Supervisor review; ZCode GLM-5.3-Flash implements fixes. This is a release blocker.

## P1 — Emulator failures are reported as successful CI
Run 36957103405 reports success for both emulator jobs, but downloaded JUnit XML shows 15 tests / 8 failures on BOTH API30/API36. Neither artifact contains PNG screenshots. Private evidence downloaded to /root/operations/morsel/evidence/36957103405/{api30,api36}. Never mark this run passing in documentation.

The emulator action script has multiple independent lines, attempting to preserve shell status across them. Move test execution and evidence collection into a repository shell script invoked as ONE emulator-runner script command. Capture Gradle exit status in that same shell, collect logcat/screenshots even on failure, exit with original test failure. Do not disable failing tests or use ignoreFailures. Verify a deliberately failing temporary command in a local shell test makes this wrapper return nonzero; remove the deliberate failure. Run shellcheck.

Inspect actual test reports and fix causes:
- DemoMismatch: demo banner absent.
- DemoSuccess: confirmed success text absent.
- DemoUnconfirmed / DemoUnknown: missing initial Feed or timeout.
- All four FloatingWindow tests fail launcher visibility/return assertions (bounds assertions themselves did not fail).
- FailureStatus and all four storage/Keystore tests passed.
Check cross-test settings/journal leakage, lifecycle isolation, coroutine/Compose idling, and actual launcher foreground. Preserve behavioral assertions. Capture screenshot on failure as well as success and ensure the pull path matches actual Screenshots.kt output.
Re-run full emulator matrix and inspect XML, not only job color.

## P1 — Release must require emulator gates and verify delivered APK
release.yml currently requires only static/unit gates, so a tagged commit can publish while emulator tests fail or never ran. Reuse the existing CI workflow via workflow_call (or an equally small exact-commit gate) and make signing/publishing depend on all API30/API36 and static jobs passing. A tag must not bypass this.

Complete the planned signed APK smoke check and post-publication health check: download the exact published APK and SHA256SUMS, validate checksum/certificate/package/version, install and launch it in a GitHub-hosted emulator, capture screenshot and check for launch crash. Use only setup/demo, no real account/dispensing. Preserve test/source links in release notes; corresponding source must be attached or clearly linked to exact tag/commit. Keep existing key/secrets; never regenerate. A failed check must fail the workflow visibly.

## Remaining acceptance evidence
Current FloatingWindow same-process test only reopens the setup card; it does not exercise an in-flight request. Add the planned fake in-flight dismissal/reopen test and process-death/relaunch persistence test using production coordinator/client transport injection, never a real feeder. Also produce representative light/dark/large-font/Dutch screenshots and verify usable controls in landscape/IME, as already required in implementation.md. Do not claim these from compilation alone.

## Order
1. Fix false-green status and missing evidence.
2. Diagnose/fix the 8 actual failures per API.
3. Fill required lifecycle/visual evidence and gate release on exact-commit matrix.
4. Run spotlessCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest, then full CI; inspect reports/screenshots.
5. Update docs with exact passing counts/run links and remaining physical limitations. Commit/push coherent changes; supervisor reviews before tag/release.
