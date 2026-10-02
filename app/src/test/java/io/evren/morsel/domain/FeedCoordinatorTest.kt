package io.evren.morsel.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedCoordinatorTest {

    private fun TestScope.newCoordinator(
        repository: FakeRepository,
        journal: FeedJournal,
        serial: String? = "SN1",
        cap: Int = 16,
    ): FeedCoordinator = FeedCoordinator(
        repository = repository,
        journal = journal,
        settingsSource = fakeSettings(serial, cap),
        scope = backgroundScope,
        clock = { 1_000_000L + testScheduler.currentTime },
        pollDelaysMs = listOf(3_000L, 10_000L, 25_000L),
    )

    @Test
    fun `happy path sends exactly one write and polls three times`() = runTest {
        val repo = FakeRepository()
        val journal = FakeJournal()
        val coordinator = newCoordinator(repo, journal)
        coordinator.restore()

        val result = coordinator.submit(3)

        assertTrue(result is SubmissionResult.Dispatched)
        assertEquals(1, repo.writeCalls.get())
        assertEquals("SN1", repo.written.single().first)
        assertEquals(3, repo.written.single().second)
        assertEquals(1, repo.deviceCalls.get())
        assertEquals(1, repo.statusCalls.get())
        assertEquals(1, repo.historyCalls.get()) // baseline only
        val op = (result as SubmissionResult.Dispatched).operation
        assertEquals(FeedState.ACCEPTED_UNCONFIRMED, op.state)
        assertEquals(FeedState.ACCEPTED_UNCONFIRMED, journal.ops.single().state)
        assertEquals(op.requestId, journal.ops.single().requestId)
        assertTrue(coordinator.state.value.blocksNewSubmissions)

        // Poll reads at 3s/10s/25s, read-only: history call count grows to 4, writes stay 1.
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(2, repo.historyCalls.get())
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(3, repo.historyCalls.get())
        advanceTimeBy(25_001)
        runCurrent()
        assertEquals(4, repo.historyCalls.get())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(4, repo.historyCalls.get()) // polling finished
        assertEquals(1, repo.writeCalls.get())
    }

    @Test
    fun `correlated success arrives via polling`() = runTest {
        val repo = FakeRepository()
        val coordinator = newCoordinator(repo, FakeJournal())
        coordinator.restore()

        val dispatched = coordinator.submit(3) as SubmissionResult.Dispatched
        repo.enqueueHistory(
            listOf(record(correlationId = dispatched.operation.requestId, time = 9_000L, amount = 3)),
        )
        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(FeedState.REPORTED_SUCCESS, coordinator.state.value.lastResolved?.state)
        assertNull(coordinator.state.value.unresolvedOperation)
        assertFalse(coordinator.state.value.blocksNewSubmissions)
    }

    @Test
    fun `concurrent 20 taps produce exactly one write`() = runTest {
        val repo = FakeRepository()
        val coordinator = newCoordinator(repo, FakeJournal())
        coordinator.restore()

        val gate = CompletableDeferred<Unit>()
        repo.writeGate = gate
        val deferreds = (1..20).map { async { coordinator.submit(2) } }
        runCurrent() // first submit holds the guard inside the in-flight write; rest bounce
        gate.complete(Unit)
        runCurrent()
        val results = deferreds.awaitAll()

        assertEquals(1, repo.writeCalls.get())
        val dispatched = results.filterIsInstance<SubmissionResult.Dispatched>()
        assertEquals(1, dispatched.size)
        assertTrue(results.count { it is SubmissionResult.IgnoredDuplicate || it is SubmissionResult.Blocked } == 19)
    }

    @Test
    fun `invalid quantities rejected with zero http calls`() = runTest {
        val repo = FakeRepository()
        val coordinator = newCoordinator(repo, FakeJournal())

        assertEquals(
            SubmissionResult.Blocked.Reason.INVALID_PORTIONS,
            (coordinator.submit(0) as SubmissionResult.Blocked).reason,
        )
        assertEquals(
            SubmissionResult.Blocked.Reason.INVALID_PORTIONS,
            (coordinator.submit(17) as SubmissionResult.Blocked).reason,
        )
        // Cap lower than 16 is respected.
        val capped = newCoordinator(repo, FakeJournal(), cap = 2)
        assertEquals(
            SubmissionResult.Blocked.Reason.INVALID_PORTIONS,
            (capped.submit(3) as SubmissionResult.Blocked).reason,
        )
        assertEquals(0, repo.writeCalls.get())
        assertEquals(0, repo.deviceCalls.get())
    }

    @Test
    fun `write transport fault never replays and stays unresolved`() = runTest {
        val repo = FakeRepository().apply { sendFeedError = FeederException.Transport("timeout") }
        val journal = FakeJournal()
        val coordinator = newCoordinator(repo, journal)

        val result = coordinator.submit(3)

        assertTrue(result is SubmissionResult.Unresolved)
        assertEquals(1, repo.writeCalls.get())
        assertEquals(FeedState.UNKNOWN, (result as SubmissionResult.Unresolved).operation.state)
        assertFalse(result.operation.authExpiredDuringWrite)
        assertEquals(FeedState.UNKNOWN, coordinator.state.value.unresolvedOperation?.state)
        assertTrue(coordinator.state.value.blocksNewSubmissions)
        assertEquals(FeedState.UNKNOWN, journal.ops.single().state)
    }

    @Test
    fun `write auth expiry never replays and flags auth`() = runTest {
        val repo = FakeRepository().apply { sendFeedError = FeederException.AuthExpired() }
        val coordinator = newCoordinator(repo, FakeJournal())

        val result = coordinator.submit(1)

        assertTrue(result is SubmissionResult.Unresolved)
        assertEquals(1, repo.writeCalls.get())
        assertTrue((result as SubmissionResult.Unresolved).authExpired)
        assertEquals(0, repo.loginCalls.get()) // write path must not re-login
        assertEquals(FeedState.UNKNOWN, coordinator.state.value.unresolvedOperation?.state)
    }

    @Test
    fun `documented rejection is final REJECTED with a single write`() = runTest {
        val repo = FakeRepository().apply {
            sendFeedError = FeederException.DocumentedRejection(4101, "no")
        }
        val coordinator = newCoordinator(repo, FakeJournal())

        val result = coordinator.submit(2)

        assertTrue(result is SubmissionResult.Rejected)
        assertEquals(1, repo.writeCalls.get())
        assertEquals(FeedState.REJECTED, (result as SubmissionResult.Rejected).operation.state)
        assertFalse(coordinator.state.value.blocksNewSubmissions)
        assertEquals(FeedState.REJECTED, coordinator.state.value.lastResolved?.state)
        assertNull(coordinator.state.value.unresolvedOperation)
    }

    @Test
    fun `journal failure before write prevents send`() = runTest {
        val repo = FakeRepository()
        val journal = FakeJournal().apply { failNextUpserts = 1 }
        val coordinator = newCoordinator(repo, journal)

        val result = coordinator.submit(3)

        assertEquals(SubmissionResult.JournalWriteFailed, result)
        assertEquals(0, repo.writeCalls.get())
        assertNull(coordinator.state.value.unresolvedOperation)
    }

    @Test
    fun `journal failure after write presents unknown and stays unresolved on disk`() = runTest {
        val repo = FakeRepository()
        val journal = FakeJournal().apply { failOutcomeUpserts = true }
        val coordinator = newCoordinator(repo, journal)

        val result = coordinator.submit(3)

        // The write escaped; the presented state is UNKNOWN; the disk keeps DISPATCHING.
        assertTrue(result is SubmissionResult.Unresolved)
        assertEquals(1, repo.writeCalls.get())
        assertEquals(FeedState.UNKNOWN, (result as SubmissionResult.Unresolved).operation.state)
        assertTrue(coordinator.state.value.blocksNewSubmissions)
        assertEquals(FeedState.DISPATCHING, journal.ops.single().state)
        // Recovery maps it to UNKNOWN, still never resent.
        val second = newCoordinator(repo, journal)
        second.restore()
        assertEquals(FeedState.UNKNOWN, second.state.value.unresolvedOperation?.state)
        assertEquals(1, repo.writeCalls.get())
    }

    @Test
    fun `missing serial fails closed with no substitute`() = runTest {
        val repo = FakeRepository()
        val coordinator = newCoordinator(repo, FakeJournal(), serial = "GONE")

        val result = coordinator.submit(1)

        assertEquals(
            SubmissionResult.Blocked.Reason.SERIAL_MISSING,
            (result as SubmissionResult.Blocked).reason,
        )
        assertEquals(0, repo.writeCalls.get())
        assertEquals(1, repo.deviceCalls.get()) // discovery read happened
        assertEquals(0, repo.statusCalls.get())
    }

    @Test
    fun `no binding at all blocks before any http`() = runTest {
        val repo = FakeRepository()
        val coordinator = newCoordinator(repo, FakeJournal(), serial = null)

        val result = coordinator.submit(1)

        assertEquals(SubmissionResult.Blocked.Reason.NO_BINDING, (result as SubmissionResult.Blocked).reason)
        assertEquals(0, repo.deviceCalls.get())
        assertEquals(0, repo.writeCalls.get())
    }

    @Test
    fun `offline feeder blocks submission with no delayed send`() = runTest {
        val repo = FakeRepository().apply { statusResponse = DeviceStatus(online = false) }
        val coordinator = newCoordinator(repo, FakeJournal())

        assertEquals(
            SubmissionResult.Blocked.Reason.OFFLINE,
            (coordinator.submit(2) as SubmissionResult.Blocked).reason,
        )
        assertEquals(0, repo.writeCalls.get())

        // Feeder comes back: still nothing may be sent without a deliberate action.
        repo.statusResponse = DeviceStatus(online = true)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, repo.writeCalls.get())
        assertEquals(0, repo.historyCalls.get())
    }

    @Test
    fun `wrong model binding fails closed`() = runTest {
        val repo = FakeRepository()
        repo.devicesResponse = listOf(DeviceIdentity("SN1", "OTHERMODEL", null, null))
        val coordinator = newCoordinator(repo, FakeJournal())

        val result = coordinator.submit(1)

        assertEquals(SubmissionResult.Blocked.Reason.WRONG_MODEL, (result as SubmissionResult.Blocked).reason)
        assertEquals(0, repo.writeCalls.get())
        assertEquals(0, repo.statusCalls.get())
    }

    @Test
    fun `recreate after dispatch restores unknown and never resends`() = runTest {
        val repo = FakeRepository().apply { sendFeedError = FeederException.Transport("dead") }
        val journal = FakeJournal()
        val first = newCoordinator(repo, journal)
        first.submit(3)
        assertEquals(1, repo.writeCalls.get())
        assertEquals(FeedState.UNKNOWN, journal.ops.single().state)

        // New process: fresh coordinator over the same journal/repository.
        val second = newCoordinator(repo, journal)
        second.restore()
        assertEquals(FeedState.UNKNOWN, second.state.value.unresolvedOperation?.state)
        assertEquals(1, repo.writeCalls.get())

        val blocked = second.submit(2)
        assertEquals(
            SubmissionResult.Blocked.Reason.UNRESOLVED_OPERATION,
            (blocked as SubmissionResult.Blocked).reason,
        )
        assertEquals(1, repo.writeCalls.get()) // still never resent

        assertTrue(second.acknowledgeUnresolved())
        repo.sendFeedError = null // connectivity back; next action is deliberate
        val fresh = second.submit(2)
        assertTrue(fresh is SubmissionResult.Dispatched)
        assertEquals(2, repo.writeCalls.get()) // new deliberate operation only
    }

    @Test
    fun `restore maps dispatching to unknown`() = runTest {
        val journal = FakeJournal()
        journal.upsert(
            FeedOperation("op-x", "SN1", 3, "req-x", 42L, FeedState.DISPATCHING),
        )
        val coordinator = newCoordinator(FakeRepository(), journal)
        coordinator.restore()

        assertEquals(FeedState.UNKNOWN, coordinator.state.value.unresolvedOperation?.state)
        assertEquals(FeedState.UNKNOWN, journal.ops.single().state)
    }

    @Test
    fun `unreadable journal latches storage error and blocks all sending`() = runTest {
        val repo = FakeRepository()
        val journal = FakeJournal().apply { failAllReads = true }
        val coordinator = newCoordinator(repo, journal)

        coordinator.restore()

        assertTrue(coordinator.state.value.storageError)
        assertTrue(coordinator.state.value.blocksNewSubmissions)
        val result = coordinator.submit(1)
        assertEquals(
            SubmissionResult.Blocked.Reason.STORAGE_ERROR,
            (result as SubmissionResult.Blocked).reason,
        )
        // Blocked before any preflight: zero transport use, zero writes.
        assertEquals(0, repo.deviceCalls.get())
        assertEquals(0, repo.statusCalls.get())
        assertEquals(0, repo.writeCalls.get())
    }

    @Test
    fun `caller cancellation after the durable dispatch records unknown exactly once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepository().apply { writeGate = gate }
        val journal = FakeJournal()
        val coordinator = newCoordinator(repo, journal)

        val caller = launch { coordinator.submit(2) }
        runCurrent()
        // The request is durable and the single write is in flight.
        assertEquals(1, repo.writeCalls.get())
        assertEquals(FeedState.DISPATCHING, coordinator.state.value.unresolvedOperation?.state)

        // Card dismissal cancels the submitting caller mid-flight.
        caller.cancel()
        runCurrent()

        // Conservative UNKNOWN, visible and blocking, durably recorded.
        assertEquals(FeedState.UNKNOWN, coordinator.state.value.unresolvedOperation?.state)
        assertTrue(coordinator.state.value.blocksNewSubmissions)
        assertEquals(FeedState.UNKNOWN, journal.ops.single().state)

        // The transport completes late; the request is never repeated.
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, repo.writeCalls.get())
        assertEquals(1, journal.ops.size)

        // Check status and acknowledgement remain available for this outcome.
        val outcome = coordinator.checkStatus()
        assertEquals(StatusCheckOutcome.STILL_UNCONFIRMED, outcome)
        assertTrue(coordinator.acknowledgeUnresolved())
        assertFalse(coordinator.state.value.blocksNewSubmissions)
    }

    @Test
    fun `acknowledgement records resolution and never erases`() = runTest {
        val repo = FakeRepository().apply { sendFeedError = FeederException.Transport("x") }
        val journal = FakeJournal()
        val coordinator = newCoordinator(repo, journal)
        coordinator.submit(1)
        val opId = coordinator.state.value.unresolvedOperation?.id

        assertTrue(coordinator.acknowledgeUnresolved())
        assertFalse(coordinator.state.value.blocksNewSubmissions)
        val journaled = journal.ops.single { it.id == opId }
        assertNotNull(journaled.acknowledgedAtEpochMs)
        assertEquals(FeedState.UNKNOWN, journaled.state) // resolved by acknowledgement, still visible
        assertFalse(coordinator.acknowledgeUnresolved()) // acknowledging again is a no-op
    }

    @Test
    fun `acknowledgement during polling stays resolved after poll ticks`() = runTest {
        val repo = FakeRepository()
        val journal = FakeJournal()
        val coordinator = newCoordinator(repo, journal)
        repo.enqueueHistory(
            listOf(record(null, 1L, 1)), // submit baseline
            listOf(record(null, 2L, 1)), // would-be first poll tick
        )

        val result = coordinator.submit(2)
        assertTrue(result is SubmissionResult.Dispatched)
        val opId = (result as SubmissionResult.Dispatched).operation.id

        // The user acknowledges while polling is still scheduled (3s/10s/25s).
        assertTrue(coordinator.acknowledgeUnresolved())
        assertNull(coordinator.state.value.unresolvedOperation)
        assertNotNull(coordinator.state.value.lastResolved?.acknowledgedAtEpochMs)
        assertFalse(coordinator.state.value.blocksNewSubmissions)

        // Time passes through every remaining tick: polling was cancelled, so
        // no further history reads happen and nothing can resurrect the
        // acknowledged operation — not even history that would have confirmed it.
        repo.enqueueHistory(listOf(record(result.operation.requestId, 3L, 2)))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, repo.historyCalls.get()) // baseline only
        assertNull(coordinator.state.value.unresolvedOperation)
        assertEquals(opId, coordinator.state.value.lastResolved?.id)

        // The journal keeps the acknowledged entry visible, never rewritten.
        val journaled = journal.ops.single { it.id == opId }
        assertNotNull(journaled.acknowledgedAtEpochMs)
        assertEquals(FeedState.ACCEPTED_UNCONFIRMED, journaled.state)

        // Feeding again is a NEW deliberate operation, never a replay.
        val second = coordinator.submit(2)
        assertTrue(second is SubmissionResult.Dispatched)
        assertEquals(2, repo.writeCalls.get())
        assertEquals(2, repo.historyCalls.get())
        assertNotEquals(opId, (second as SubmissionResult.Dispatched).operation.id)
    }

    @Test
    fun `logout and rebind cannot bypass unresolved journal`() = runTest {
        val repo = FakeRepository()
        val journal = FakeJournal()
        var boundSerial: String? = "SN1"
        val coordinator = FeedCoordinator(
            repository = repo,
            journal = journal,
            settingsSource = { FeedSettings(boundSerial, 16) },
            scope = backgroundScope,
            pollDelaysMs = listOf(3_000L, 10_000L, 25_000L),
        )
        coordinator.restore()
        coordinator.submit(2)
        assertTrue(coordinator.state.value.blocksNewSubmissions)

        // Owner signs out and rebinds to a different feeder.
        boundSerial = "SN2"
        val deviceCallsBeforeAttempt = repo.deviceCalls.get()
        val attempted = coordinator.submit(1)
        assertEquals(
            SubmissionResult.Blocked.Reason.UNRESOLVED_OPERATION,
            (attempted as SubmissionResult.Blocked).reason,
        )
        // The rebind attempt must not even read devices.
        assertEquals(deviceCallsBeforeAttempt, repo.deviceCalls.get())
        assertNotNull(coordinator.unresolvedAfterRebind())
    }

    @Test
    fun `baseline history failure keeps outcome unconfirmed`() = runTest {
        val repo = FakeRepository()
        val coordinator = newCoordinator(repo, FakeJournal())
        repo.enqueueHistory(FeederException.Transport("history down")) // baseline read fails
        repo.enqueueHistory(FeederException.Transport("history down")) // poll 1 fails: polling stops
        repo.enqueueHistory(
            // Only reachable via explicit status check: matching time+amount but
            // NO correlation id → must stay unconfirmed.
            listOf(record(correlationId = null, time = 1_003_000L, amount = 3)),
        )
        repo.enqueueHistory(
            listOf(record(correlationId = null, time = 1_003_000L, amount = 3)),
        )

        val result = coordinator.submit(3)

        assertTrue(result is SubmissionResult.Dispatched)
        advanceTimeBy(3_001)
        runCurrent() // poll 1 read fails → polling stops
        assertEquals(FeedState.ACCEPTED_UNCONFIRMED, coordinator.state.value.unresolvedOperation?.state)
        assertTrue(coordinator.state.value.blocksNewSubmissions)

        // User taps "check status" twice: still no proof without correlation.
        assertEquals(StatusCheckOutcome.STILL_UNCONFIRMED, coordinator.checkStatus())
        assertEquals(StatusCheckOutcome.STILL_UNCONFIRMED, coordinator.checkStatus())
        assertEquals(FeedState.ACCEPTED_UNCONFIRMED, coordinator.state.value.unresolvedOperation?.state)
        assertEquals(1, repo.writeCalls.get())
    }

    @Test
    fun `correlated mismatch never tops up`() = runTest {
        val repo = FakeRepository()
        val coordinator = newCoordinator(repo, FakeJournal())
        val dispatched = coordinator.submit(3) as SubmissionResult.Dispatched
        repo.enqueueHistory(
            listOf(record(correlationId = dispatched.operation.requestId, time = 9_000L, amount = 5)),
        )
        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(FeedState.REPORTED_MISMATCH, coordinator.state.value.lastResolved?.state)
        assertEquals(1, repo.writeCalls.get()) // no automatic top-up
        assertNull(coordinator.state.value.unresolvedOperation)
    }

    @Test
    fun `status check is read-only and can resolve`() = runTest {
        val repo = FakeRepository()
        val coordinator = newCoordinator(repo, FakeJournal())
        val dispatched = coordinator.submit(3) as SubmissionResult.Dispatched
        repo.enqueueHistory(
            listOf(record(correlationId = dispatched.operation.requestId, time = 9_000L, amount = 3)),
        )

        val outcome = coordinator.checkStatus()

        assertEquals(StatusCheckOutcome.RESOLVED_SUCCESS, outcome)
        assertEquals(1, repo.writeCalls.get())
        assertEquals(FeedState.REPORTED_SUCCESS, coordinator.state.value.lastResolved?.state)
    }

    @Test
    fun `success polling stops and new feeds remain deliberate`() = runTest {
        val repo = FakeRepository()
        val coordinator = newCoordinator(repo, FakeJournal())
        val dispatched = coordinator.submit(3) as SubmissionResult.Dispatched
        repo.enqueueHistory(
            listOf(record(correlationId = dispatched.operation.requestId, time = 9_000L, amount = 3)),
        )
        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(FeedState.REPORTED_SUCCESS, coordinator.state.value.lastResolved?.state)
        assertNull(coordinator.state.value.unresolvedOperation)
        val pollsAfterSuccess = repo.historyCalls.get()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(pollsAfterSuccess, repo.historyCalls.get()) // no further polling
        assertEquals(1, repo.writeCalls.get()) // no automatic re-feed
    }
}
