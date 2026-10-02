package io.evren.morsel.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryReconcilerTest {

    private val op = FeedOperation(
        id = "op-1",
        serial = "SN",
        portions = 3,
        requestId = "req-1",
        createdAtEpochMs = 1_000L,
        state = FeedState.ACCEPTED_UNCONFIRMED,
    )

    private fun record(
        correlationId: String? = null,
        time: Long,
        amount: Int? = null,
    ) = FeederRecord(correlationId = correlationId, recordTimeEpochMs = time, actualGrainNum = amount)

    @Test
    fun `correlated matching record confirms`() {
        val history = listOf(record(time = 5_000L), record(correlationId = "req-1", time = 6_000L, amount = 3))
        assertEquals(
            HistoryReconciler.Conclusion.Confirmed::class,
            HistoryReconciler.reconcile(op, history)::class,
        )
    }

    @Test
    fun `correlated different amount is a mismatch`() {
        val history = listOf(record(correlationId = "req-1", time = 6_000L, amount = 4))
        val conclusion = HistoryReconciler.reconcile(op, history)
        assertEquals(HistoryReconciler.Conclusion.Mismatch::class, conclusion::class)
    }

    @Test
    fun `time and amount alone never confirm`() {
        val history = listOf(
            record(time = op.createdAtEpochMs + 2_000, amount = 3),
            record(correlationId = null, time = op.createdAtEpochMs + 3_000, amount = 3),
        )
        assertEquals(HistoryReconciler.Conclusion.Unconfirmed, HistoryReconciler.reconcile(op, history))
    }

    @Test
    fun `different serial correlation id cannot confirm`() {
        val history = listOf(record(correlationId = "other-request", time = 6_000L, amount = 3))
        assertEquals(HistoryReconciler.Conclusion.Unconfirmed, HistoryReconciler.reconcile(op, history))
    }

    @Test
    fun `out of order input uses newest correlated record`() {
        val history = listOf(
            record(correlationId = "req-1", time = 6_000L, amount = 3),
            record(correlationId = "req-1", time = 7_000L, amount = 2),
        )
        val conclusion = HistoryReconciler.reconcile(op, history)
        assertEquals(HistoryReconciler.Conclusion.Mismatch::class, conclusion::class)
    }

    @Test
    fun `duplicate correlated records cannot flip a confirmation`() {
        val history = listOf(
            record(correlationId = "req-1", time = 6_000L, amount = 3),
            record(correlationId = "req-1", time = 7_000L, amount = 5),
        )
        val conclusion = HistoryReconciler.reconcile(op, history)
        // Newest correlated wins; duplicates cannot confirm twice.
        assertEquals(HistoryReconciler.Conclusion.Mismatch::class, conclusion::class)
    }

    @Test
    fun `correlated record without readable amount stays unconfirmed`() {
        val history = listOf(record(correlationId = "req-1", time = 6_000L, amount = null))
        assertEquals(HistoryReconciler.Conclusion.Unconfirmed, HistoryReconciler.reconcile(op, history))
    }

    @Test
    fun `baseline identities and new-since-baseline work on identity tuples`() {
        val baseline = listOf(record(time = 1_000L, amount = 2))
        val identities = HistoryReconciler.baselineIdentities(baseline)
        val newer = listOf(record(time = 1_000L, amount = 2), record(time = 2_000L, amount = 3))
        assertEquals(listOf(2_000L), HistoryReconciler.newSinceBaseline(newer, identities).map { it.recordTimeEpochMs })
    }
}
