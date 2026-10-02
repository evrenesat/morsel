package io.evren.morsel.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Outcome of a deliberate submit attempt. No outcome ever queues a retry. */
sealed class SubmissionResult {
    /** Duplicate tap while a dispatch or unresolved operation holds the guard. */
    data object IgnoredDuplicate : SubmissionResult()

    data class Blocked(val reason: Reason) : SubmissionResult() {
        enum class Reason {
            UNRESOLVED_OPERATION,
            INVALID_PORTIONS,
            NO_BINDING,
            SERIAL_MISSING,
            WRONG_MODEL,
            OFFLINE,
            PREFLIGHT_FAILED,

            /** The operation journal could not be read; nothing may be sent. */
            STORAGE_ERROR,
        }
    }

    /** Journal write failed before the HTTP write; nothing was sent. */
    data object JournalWriteFailed : SubmissionResult()

    /** Cloud accepted; physical completion unconfirmed. */
    data class Dispatched(val operation: FeedOperation) : SubmissionResult()

    /** Write definitively rejected by a documented API rejection. */
    data class Rejected(val operation: FeedOperation) : SubmissionResult()

    /** Write failed or produced an unknown result; never replayed. */
    data class Unresolved(val operation: FeedOperation, val authExpired: Boolean) : SubmissionResult()
}

data class CoordinatorState(
    val unresolvedOperation: FeedOperation? = null,
    val lastResolved: FeedOperation? = null,
    val feederHistory: List<FeederRecord> = emptyList(),
    /** True only while the single HTTP write is in flight. */
    val dispatching: Boolean = false,
    /**
     * Latched storage failure: the journal could not be read, so earlier
     * operation state is unknown. All sending stays blocked until this clears
     * (only a successful startup read clears it); nothing is reset silently.
     */
    val storageError: Boolean = false,
) {
    /** Unresolved entries and unreadable storage block new submissions. */
    val blocksNewSubmissions: Boolean
        get() = storageError || unresolvedOperation?.unresolved == true
}

/** Contract the UI programs against; fakes implement this in tests. */
interface FeedingCoordinator {
    val state: StateFlow<CoordinatorState>

    suspend fun submit(portions: Int): SubmissionResult

    suspend fun restore()

    suspend fun checkStatus(): StatusCheckOutcome

    suspend fun acknowledgeUnresolved(): Boolean

    suspend fun unresolvedAfterRebind(): FeedOperation?
}

/**
 * Application-scoped coordinator for the one deliberate feeding action.
 *
 * Guarantees:
 * - duplicate taps are ignored, never queued (atomic guard + unresolved check);
 * - the operation (serial, portions, requestId) is frozen in the journal
 *   BEFORE the HTTP write; persistence failure prevents the send;
 * - the write HTTP call happens at most once per operation, whatever the fault;
 * - 1009 on the write never triggers re-login/replay;
 * - DISPATCHING entries found at startup become UNKNOWN and are never resent;
 * - polling after acceptance is read-only, at injectable delays.
 */
class FeedCoordinator(
    private val repository: FeederRepository,
    private val journal: FeedJournal,
    private val settingsSource: FeedSettingsSource,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val requestIdFactory: () -> String = {
        UUID.randomUUID().toString().replace("-", "")
    },
    private val pollDelaysMs: List<Long> = listOf(3_000L, 10_000L, 25_000L),
    private val pollDelay: suspend (Long) -> Unit = { delay(it) },
) : FeedingCoordinator {
    private val dispatchGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    private val mutableState = MutableStateFlow(CoordinatorState())
    override val state: StateFlow<CoordinatorState> = mutableState.asStateFlow()

    private var pollingJob: Job? = null

    /** Deliberate submission. One invocation results in at most one HTTP write. */
    override suspend fun submit(portions: Int): SubmissionResult {
        if (!dispatchGuard.compareAndSet(false, true)) {
            return SubmissionResult.IgnoredDuplicate
        }
        try {
            mutableState.update { it.copy(dispatching = true) }
            if (state.value.storageError) {
                return SubmissionResult.Blocked(SubmissionResult.Blocked.Reason.STORAGE_ERROR)
            }
            if (state.value.blocksNewSubmissions) {
                return SubmissionResult.Blocked(SubmissionResult.Blocked.Reason.UNRESOLVED_OPERATION)
            }
            if (!FeedState.valid(portions, settingsSource.snapshot().portionCap)) {
                return SubmissionResult.Blocked(SubmissionResult.Blocked.Reason.INVALID_PORTIONS)
            }
            val serial = settingsSource.snapshot().boundSerial
                ?: return SubmissionResult.Blocked(SubmissionResult.Blocked.Reason.NO_BINDING)

            // Preflight reads (auth re-login for reads happens inside the repository).
            val devices = try {
                repository.devices()
            } catch (_: FeederException) {
                return SubmissionResult.Blocked(SubmissionResult.Blocked.Reason.PREFLIGHT_FAILED)
            }
            val matches = devices.filter { it.serial == serial }
            val bound = matches.firstOrNull()
                ?: return SubmissionResult.Blocked(SubmissionResult.Blocked.Reason.SERIAL_MISSING)
            if (bound.model != PLAF108_MODEL) {
                return SubmissionResult.Blocked(SubmissionResult.Blocked.Reason.WRONG_MODEL)
            }
            val online = try {
                repository.status(serial).online
            } catch (_: FeederException) {
                return SubmissionResult.Blocked(SubmissionResult.Blocked.Reason.PREFLIGHT_FAILED)
            }
            if (!online) {
                return SubmissionResult.Blocked(SubmissionResult.Blocked.Reason.OFFLINE)
            }

            // Baseline history identities; failure keeps attribution unconfirmed
            // but does not block the deliberate send.
            val now = clock()
            val (baselineHistory, baselineKnown) = try {
                val history = repository.feederHistory(serial, now - HISTORY_WINDOW_MS, now)
                history to true
            } catch (_: FeederException) {
                emptyList<FeederRecord>() to false
            }

            val operation = FeedOperation(
                id = UUID.randomUUID().toString().replace("-", ""),
                serial = serial,
                portions = portions,
                requestId = requestIdFactory(),
                createdAtEpochMs = clock(),
                state = FeedState.DISPATCHING,
                baselineKnown = baselineKnown,
            )
            try {
                journal.upsert(operation)
            } catch (_: JournalPersistenceException) {
                return SubmissionResult.JournalWriteFailed
            }
            mutableState.update {
                it.copy(
                    unresolvedOperation = operation,
                    feederHistory = baselineHistory,
                    lastResolved = it.lastResolved,
                )
            }

            // THE single write. No retry under any failure.
            return try {
                repository.sendFeed(operation.serial, operation.portions, operation.requestId)
                val accepted = operation.copy(state = FeedState.ACCEPTED_UNCONFIRMED)
                val presented = persistOutcome(accepted)
                schedulePolling(presented)
                if (presented.state == FeedState.ACCEPTED_UNCONFIRMED) {
                    SubmissionResult.Dispatched(presented)
                } else {
                    // Outcome could not be persisted: present conservative UNKNOWN.
                    SubmissionResult.Unresolved(presented, false)
                }
            } catch (e: FeederException) {
                val (nextState, authExpired) = when (e) {
                    is FeederException.DocumentedRejection -> {
                        val rejected = operation.copy(state = FeedState.REJECTED)
                        persistOutcome(rejected)
                        return SubmissionResult.Rejected(rejected)
                    }
                    is FeederException.AuthExpired -> FeedState.UNKNOWN to true
                    else -> FeedState.UNKNOWN to false
                }
                val unresolvedOp = operation.copy(state = nextState, authExpiredDuringWrite = authExpired)
                persistOutcome(unresolvedOp)
                SubmissionResult.Unresolved(unresolvedOp, authExpired)
            }
        } finally {
            mutableState.update { it.copy(dispatching = false) }
            dispatchGuard.set(false)
        }
    }

    /**
     * Startup recovery: a DISPATCHING entry from a previous process becomes
     * UNKNOWN and is never resent. Loads the last resolved operation too.
     * An unreadable journal latches [CoordinatorState.storageError]: all
     * sending blocks and the UI shows a clear status; nothing is reset.
     */
    override suspend fun restore() {
        val all = try {
            journal.all()
        } catch (_: JournalReadException) {
            mutableState.update { it.copy(storageError = true) }
            return
        }
        val recovered = all.map {
            if (it.state == FeedState.DISPATCHING) {
                it.copy(state = FeedState.UNKNOWN)
            } else {
                it
            }
        }
        recovered.zip(all).filter { (new, old) -> new != old }.forEach { (new, _) ->
            // Recovery is best-effort; an unwritable journal keeps the raw entry
            // and the in-memory UNKNOWN still blocks resends.
            runCatching { journal.upsert(new) }
        }
        val unresolvedOp = recovered.lastOrNull { it.unresolved }
        val lastResolved = recovered.lastOrNull { !it.unresolved }
        mutableState.update {
            it.copy(unresolvedOperation = unresolvedOp, lastResolved = lastResolved)
        }
    }

    /**
     * Read-only status check (user-initiated, repeatable). Reconciles the
     * unresolved operation against current feeder history and refreshes the
     * displayed feeder history.
     */
    override suspend fun checkStatus(): StatusCheckOutcome {
        val op = state.value.unresolvedOperation
        val now = clock()
        return try {
            val history = if (op != null) {
                repository.feederHistory(op.serial, now - HISTORY_WINDOW_MS, now)
            } else {
                val serial = settingsSource.snapshot().boundSerial
                if (serial == null) {
                    mutableState.update { it.copy(feederHistory = emptyList()) }
                    return StatusCheckOutcome.NO_OPERATION
                }
                repository.feederHistory(serial, now - HISTORY_WINDOW_MS, now)
            }
            mutableState.update { it.copy(feederHistory = history) }
            if (op != null && op.state == FeedState.ACCEPTED_UNCONFIRMED) {
                when (val conclusion = HistoryReconciler.reconcile(op, history)) {
                    is HistoryReconciler.Conclusion.Confirmed -> {
                        persistOutcome(op.copy(state = FeedState.REPORTED_SUCCESS))
                        StatusCheckOutcome.RESOLVED_SUCCESS
                    }
                    is HistoryReconciler.Conclusion.Mismatch -> {
                        persistOutcome(op.copy(state = FeedState.REPORTED_MISMATCH))
                        StatusCheckOutcome.RESOLVED_MISMATCH
                    }
                    HistoryReconciler.Conclusion.Unconfirmed -> StatusCheckOutcome.STILL_UNCONFIRMED
                }
            } else if (op == null) {
                StatusCheckOutcome.NO_OPERATION
            } else {
                StatusCheckOutcome.STILL_UNCONFIRMED
            }
        } catch (_: FeederException) {
            StatusCheckOutcome.READ_FAILED
        }
    }

    /**
     * Explicit user acknowledgement of an unresolved operation ("I checked the
     * feeder"). Records the resolution in the journal; the entry stays visible.
     */
    override suspend fun acknowledgeUnresolved(): Boolean {
        val op = state.value.unresolvedOperation ?: return false
        if (!op.unresolved) return false
        val acknowledged = op.copy(acknowledgedAtEpochMs = clock())
        val recorded = try {
            journal.upsert(acknowledged)
            true
        } catch (_: JournalPersistenceException) {
            // The unresolved entry stays visible; a failed acknowledgement is
            // never recorded as resolution.
            false
        }
        if (!recorded) return false
        mutableState.update {
            it.copy(unresolvedOperation = acknowledged, lastResolved = acknowledged)
        }
        return true
    }

    /** Explicit logout/rebind must surface, not hide, the unresolved operation. */
    override suspend fun unresolvedAfterRebind(): FeedOperation? = state.value.unresolvedOperation

    private fun schedulePolling(operation: FeedOperation) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            for (delayMs in pollDelaysMs) {
                pollDelay(delayMs)
                val current = mutableState.value.unresolvedOperation
                if (current == null || current.id != operation.id || current.state != FeedState.ACCEPTED_UNCONFIRMED) {
                    return@launch
                }
                val history = try {
                    val now = clock()
                    repository.feederHistory(operation.serial, now - HISTORY_WINDOW_MS, now)
                } catch (_: FeederException) {
                    // Read failures stop polling; the user's status check can repeat reads.
                    return@launch
                }
                mutableState.update { it.copy(feederHistory = history) }
                when (val conclusion = HistoryReconciler.reconcile(operation, history)) {
                    is HistoryReconciler.Conclusion.Confirmed -> {
                        persistOutcome(operation.copy(state = FeedState.REPORTED_SUCCESS))
                        return@launch
                    }
                    is HistoryReconciler.Conclusion.Mismatch -> {
                        persistOutcome(operation.copy(state = FeedState.REPORTED_MISMATCH))
                        return@launch
                    }
                    HistoryReconciler.Conclusion.Unconfirmed -> Unit
                }
            }
        }
    }

    /**
     * Persist the outcome BEFORE it is presented. A storage error after the
     * request means the presented state stays UNKNOWN and the journal entry
     * remains unresolved (startup recovery will map it again). Returns the
     * operation as it should be presented.
     */
    private suspend fun persistOutcome(operation: FeedOperation): FeedOperation {
        val toPresent = try {
            journal.upsert(operation)
            operation
        } catch (_: JournalPersistenceException) {
            operation.copy(state = FeedState.UNKNOWN)
        }
        mutableState.update { current ->
            current.copy(
                unresolvedOperation = if (toPresent.unresolved) {
                    toPresent
                } else {
                    null
                },
                lastResolved = toPresent,
            )
        }
        return toPresent
    }

    companion object {
        const val PLAF108_MODEL = "PLAF108"

        /** Reference window for reconciliation reads (matches upstream's 30 days). */
        const val HISTORY_WINDOW_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}

enum class StatusCheckOutcome {
    RESOLVED_SUCCESS,
    RESOLVED_MISMATCH,
    STILL_UNCONFIRMED,
    NO_OPERATION,
    READ_FAILED,
}
