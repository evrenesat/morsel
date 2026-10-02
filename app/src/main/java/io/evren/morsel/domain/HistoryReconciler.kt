package io.evren.morsel.domain

/**
 * Conservative correlation of feeder work records against one operation.
 *
 * Only a proven matching request/operation id AND serial can establish this
 * operation's completion. Time+amount similarity never confirms anything; such
 * records are surfaced separately as "Feeder history".
 */
object HistoryReconciler {

    sealed class Conclusion {
        /** Record with matching correlation id, matching amount. */
        data class Confirmed(val record: FeederRecord) : Conclusion()

        /** Record with matching correlation id but different amount. No top-up. */
        data class Mismatch(val record: FeederRecord) : Conclusion()

        /** Nothing proven; the operation stays ACCEPTED_UNCONFIRMED. */
        data object Unconfirmed : Conclusion()
    }

    /**
     * [history] must already be the records for [operation]'s serial (the read
     * itself binds the serial). The newest correlated record wins; duplicates
     * and out-of-order records cannot confirm twice.
     */
    fun reconcile(operation: FeedOperation, history: List<FeederRecord>): Conclusion {
        val correlated = history
            .filter { it.correlationId != null && it.correlationId == operation.requestId }
            .sortedByDescending { it.recordTimeEpochMs }
        val record = correlated.firstOrNull() ?: return Conclusion.Unconfirmed
        val amount = record.actualGrainNum ?: return Conclusion.Unconfirmed
        return if (amount == operation.portions) {
            Conclusion.Confirmed(record)
        } else {
            Conclusion.Mismatch(record)
        }
    }

    /**
     * Identities (time + amount + correlation) of records observed BEFORE a
     * dispatch. Used only for display ("new" detection), never to confirm.
     */
    fun baselineIdentities(history: List<FeederRecord>): Set<String> = history.map { it.identity() }.toSet()

    fun newSinceBaseline(history: List<FeederRecord>, baseline: Set<String>): List<FeederRecord> = history.filter { it.identity() !in baseline }

    private fun FeederRecord.identity(): String = "$recordTimeEpochMs:$actualGrainNum:${correlationId ?: "-"}"
}
