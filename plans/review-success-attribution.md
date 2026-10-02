# Success-panel attribution review — 49f05fa

P1: FeedViewModel can show success from a previous operation for a new attempt that sent nothing.

FeedViewModel.kt sets dispatchMadeThisSession=true before coordinator.submit. Its success collector tests only lastResolved.state==REPORTED_SUCCESS and unresolvedOperation==null. FeedCoordinator.submit first emits dispatching=true while preserving lastResolved and before making an operation; preflight then suspends. Consequently a previous success loaded from the application coordinator can latch successThisSession during this new preflight. Even if preflight fails offline, the new attempt subsequently displays Done/success. If it proceeds to unresolved, the stale success resurfaces after acknowledgement. The existing comment says restored success must not latch, but the boolean permits it on the next attempt. This is not fixed by retiring acknowledged operations.

Use the current operation's identity to attribute in-session success. Capture the newly submitted operation ID from the submit result (Dispatched/Unresolved/etc.) and require the resolved ID to match it; observe the latest coordinator state when assigning the ID so a rapid completed poll is not missed. Clear/reset this identity appropriately for a genuinely new attempt. Do not infer new success from a previously resolved record or from a preflight/blocked outcome. Keep duplicate guards and Done latching for a genuinely successful current operation.

Regression with existing production ViewModel/coordinator fakes:
1. Start from lastResolved=old REPORTED_SUCCESS, no unresolved operation, fresh ViewModel.
2. Tap plus/feed with preflight held across coroutine scheduling, then fail it offline. Assert no request sent, successThisSession=false, failure visible, no success panel.
3. Same initial old success, new accepted-unconfirmed request, then acknowledge. Assert successThisSession remains false and normal fresh feed controls return.
4. New correlated success for the actual current request still latches Done even if resolution arrives rapidly.

ZCode implements; run existing local gates and emulator matrix. Update status docs and push. This review must be resolved before supervisor approves release. Avoid changing unrelated state/storage architecture.
