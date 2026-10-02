# Checkpoint 3 material review — 67269e1
Reviewer: supervising Codex. ZCode GLM-5.3-Flash implements the focused corrections. Review scope: existing UI-to-coordinator flow; no new features.

## P2 — Show failed preflight and journal writes
FeedViewModel.feed() discards SubmissionResult except an empty UNRESOLVED_OPERATION branch. On OFFLINE, SERIAL_MISSING, WRONG_MODEL, NO_BINDING, PREFLIGHT_FAILED or JournalWriteFailed, the coordinator stops before sending and the card silently returns to Feed. checkStatus() also ignores READ_FAILED. A real offline feeder, expired login/network failure, or unwritable journal therefore gives no actionable explanation.

Add a small UI error/status field mapped to localized EN/NL resource strings. Say no feed request was sent only for known pre-send outcomes; status-read failure must preserve the unresolved operation and say confirmation could not be refreshed. Never display raw exception/account data. Clear stale feedback on a fresh deliberate attempt or appropriate screen change. Keep all existing duplicate/persistence guards.

Test representative OFFLINE, SERIAL_MISSING, PREFLIGHT_FAILED and JournalWriteFailed paths through the ViewModel, asserting visible status and zero writes. Test READ_FAILED preserves uncertainty and performs no feed request. Include one emulator assertion of visible failure text.

## P2 — Recover cancellation after the durable dispatch
FeedViewModel runs submit in viewModelScope. Closing the floating activity cancels that caller; PetlibroClient.manualFeeding uses withContext(IO), whose result delivery can throw CancellationException even after the blocking HTTP request was sent. FeedCoordinator catches only FeederException around that request; finally clears dispatching but leaves unresolvedOperation.state=DISPATCHING. Application startup is the only place restore() is called. Reopening within the same process therefore renders only a disabled Feed button, since StatusArea handles ACCEPTED_UNCONFIRMED/UNKNOWN but not leftover DISPATCHING. The user cannot check or acknowledge the result until process death.

Add focused CancellationException handling after the durable operation exists: conservatively record/present UNKNOWN using a bounded non-cancellable local journal update and rethrow cancellation. Preserve exactly-one-send behavior; do not retry or create a queue. If the outcome was already durably resolved, do not overwrite it. Ensure a storage error retains an in-memory unresolved block.

Regression: hold a fake feed response after request receipt, cancel the submitting caller as activity dismissal does, then reconnect a ViewModel to the same application coordinator. Assert exactly one request, UNKNOWN visible, Check status/acknowledgement available, no resend. Keep process-restart test too; it is a different lifecycle.

## Verification and record
1. Add focused tests reproducing both findings, apply small fixes.
2. Run ./gradlew --no-daemon spotlessCheck lintDebug testDebugUnitTest assembleDebug.
3. Run emulator CI API30/API36 with the same-process dismissal/reopen case and failure status assertion.
4. Commit/push and record actual evidence in docs/implementation-status.md. No release until supervisor reviews the result.
