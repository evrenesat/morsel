package io.evren.morsel.domain

/**
 * Lifecycle of a feeding request. READY is the absence of an operation and is
 * represented by a null operation, not a persisted state.
 *
 * Unresolved states survive process death and closing the card; they block new
 * submissions until the user explicitly acknowledges (acknowledgement records
 * the resolution, it never erases the entry).
 */
enum class FeedState {
    /** Saved durably before the HTTP write leaves the device. */
    DISPATCHING,

    /** Cloud accepted the command (code 0). Physical completion NOT confirmed. */
    ACCEPTED_UNCONFIRMED,

    /** A proven correlated work record matches serial, request id and amount. */
    REPORTED_SUCCESS,

    /** A proven correlated work record matches but the amount differs. No top-up. */
    REPORTED_MISMATCH,

    /** The API documented an explicit rejection. Final; no physical claim either way. */
    REJECTED,

    /** Timeout, transport failure, malformed response, unknown code, storage error,
     *  or a DISPATCHING entry restored after process death. Never replayed. */
    UNKNOWN,
    ;

    /** Unresolved states keep the journal entry active and block fresh submissions. */
    val unresolvedState: Boolean
        get() = this == DISPATCHING || this == ACCEPTED_UNCONFIRMED || this == UNKNOWN

    companion object {
        const val MIN_PORTIONS = 1

        /** PLAF108 hardware maximum; the user cap can only be lower. */
        const val ABSOLUTE_MAX_PORTIONS = 16

        fun valid(portions: Int, cap: Int): Boolean = portions in MIN_PORTIONS..minOf(cap, ABSOLUTE_MAX_PORTIONS)
    }
}

/** One feeding request, frozen at dispatch time and persisted in the journal. */
data class FeedOperation(
    val id: String,
    val serial: String,
    val portions: Int,
    val requestId: String,
    val createdAtEpochMs: Long,
    val state: FeedState,
    /** False when the baseline history read failed before dispatch. */
    val baselineKnown: Boolean = false,
    /** True when the write itself hit 1009; the UI may suggest re-sign-in. */
    val authExpiredDuringWrite: Boolean = false,
    /** Set only by the user's explicit "I checked the feeder" acknowledgement. */
    val acknowledgedAtEpochMs: Long? = null,
) {
    /** Unresolved entries survive restarts and block new submissions until acknowledged. */
    val unresolved: Boolean
        get() = state.unresolvedState && acknowledgedAtEpochMs == null
}
