package io.evren.morsel.ui

import androidx.lifecycle.viewModelScope
import io.evren.morsel.MorselGraph
import io.evren.morsel.R
import io.evren.morsel.data.MorselSettings
import io.evren.morsel.data.MorselSettingsStore
import io.evren.morsel.data.StoredCredentials
import io.evren.morsel.demo.DemoScenario
import io.evren.morsel.domain.CoordinatorState
import io.evren.morsel.domain.DeviceIdentity
import io.evren.morsel.domain.DeviceStatus
import io.evren.morsel.domain.FeedOperation
import io.evren.morsel.domain.FeedSettings
import io.evren.morsel.domain.FeedState
import io.evren.morsel.domain.FeederRecord
import io.evren.morsel.domain.FeederRepository
import io.evren.morsel.domain.FeedingCoordinator
import io.evren.morsel.domain.StatusCheckOutcome
import io.evren.morsel.domain.SubmissionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var real: RecordingCoordinator
    private lateinit var demo: RecordingCoordinator
    private lateinit var settingsFlow: MutableStateFlow<MorselSettings>
    private lateinit var viewModel: FeedViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        real = RecordingCoordinator()
        demo = RecordingCoordinator()
        settingsFlow = MutableStateFlow(
            MorselSettings(
                onboardingComplete = true,
                boundSerial = "SN",
            ),
        )
        viewModel = FeedViewModel(FakeGraph(settingsFlow, real, demo))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `fresh session starts at zero with feed disabled`() = runTest(dispatcher) {
        runCurrent()
        assertEquals(0, viewModel.uiState.value.selection)
        assertFalse(viewModel.uiState.value.feedEnabled)
        viewModel.selectMinus()
        runCurrent()
        assertEquals(0, viewModel.uiState.value.selection) // empty cup floor
        viewModel.feed()
        runCurrent()
        assertEquals(0, real.submits)
        assertEquals(0, demo.submits)
    }

    @Test
    fun `plus and minus never touch the coordinator`() = runTest(dispatcher) {
        runCurrent()
        viewModel.selectPlus()
        viewModel.selectPlus()
        viewModel.selectMinus()
        runCurrent()
        assertEquals(0, real.submits)
        assertEquals(0, demo.submits)
        assertEquals(1, viewModel.uiState.value.selection)
    }

    @Test
    fun `selection clamps to zero and the cap`() = runTest(dispatcher) {
        runCurrent()
        repeat(20) { viewModel.selectPlus() }
        runCurrent()
        assertEquals(16, viewModel.uiState.value.selection)
        repeat(30) { viewModel.selectMinus() }
        runCurrent()
        assertEquals(0, viewModel.uiState.value.selection)
    }

    @Test
    fun `lower cap is respected by plus`() = runTest(dispatcher) {
        runCurrent()
        settingsFlow.value = settingsFlow.value.copy(portionCap = 3)
        runCurrent()
        repeat(10) { viewModel.selectPlus() }
        runCurrent()
        assertEquals(3, viewModel.uiState.value.selection)
    }

    @Test
    fun `feed dispatches once with the current selection and blocks re-feed`() = runTest(dispatcher) {
        runCurrent()
        viewModel.selectPlus()
        viewModel.selectPlus()
        viewModel.selectPlus()
        viewModel.feed()
        runCurrent()
        assertEquals(1, real.submits)
        assertEquals(3, viewModel.uiState.value.unresolved?.portions)
        assertFalse(viewModel.uiState.value.feedEnabled) // unresolved op blocks re-feed
    }

    @Test
    fun `feed is disabled before onboarding`() = runTest(dispatcher) {
        runCurrent()
        settingsFlow.value = settingsFlow.value.copy(onboardingComplete = false)
        runCurrent()
        viewModel.feed()
        runCurrent()
        assertEquals(0, real.submits)
    }

    @Test
    fun `check status and acknowledgement route to the coordinator`() = runTest(dispatcher) {
        runCurrent()
        viewModel.checkStatus()
        viewModel.acknowledgeUnresolved()
        runCurrent()
        assertEquals(1, real.statusChecks)
        assertEquals(1, real.acknowledgements)
    }

    @Test
    fun `demo mode routes actions to the demo coordinator`() = runTest(dispatcher) {
        runCurrent()
        settingsFlow.value = settingsFlow.value.copy(demoMode = true)
        runCurrent()
        viewModel.selectPlus()
        viewModel.feed()
        runCurrent()
        assertEquals(1, demo.submits)
        assertEquals(0, real.submits)
    }

    @Test
    fun `offline demo scenario disables the feed button`() = runTest(dispatcher) {
        runCurrent()
        settingsFlow.value = settingsFlow.value.copy(
            demoMode = true,
            demoScenario = DemoScenario.OFFLINE.name,
        )
        runCurrent()
        assertFalse(viewModel.uiState.value.feedEnabled)
        viewModel.feed()
        runCurrent()
        assertEquals(0, demo.submits)
    }

    @Test
    fun `blocked offline attempt shows a nothing-was-sent notice`() = runTest(dispatcher) {
        runCurrent()
        real.nextSubmitResult = SubmissionResult.Blocked(SubmissionResult.Blocked.Reason.OFFLINE)
        viewModel.selectPlus()
        viewModel.feed()
        runCurrent()
        assertEquals(1, real.submits)
        assertEquals(R.string.blocked_offline, viewModel.uiState.value.noticeRes)
        assertNull(viewModel.uiState.value.unresolved) // nothing recorded as sent
    }

    @Test
    fun `missing serial attempt shows its notice`() = runTest(dispatcher) {
        runCurrent()
        real.nextSubmitResult = SubmissionResult.Blocked(SubmissionResult.Blocked.Reason.SERIAL_MISSING)
        viewModel.selectPlus()
        viewModel.feed()
        runCurrent()
        assertEquals(R.string.blocked_serial_missing, viewModel.uiState.value.noticeRes)
        assertNull(viewModel.uiState.value.unresolved)
    }

    @Test
    fun `preflight failure attempt shows its notice`() = runTest(dispatcher) {
        runCurrent()
        real.nextSubmitResult = SubmissionResult.Blocked(SubmissionResult.Blocked.Reason.PREFLIGHT_FAILED)
        viewModel.selectPlus()
        viewModel.feed()
        runCurrent()
        assertEquals(R.string.blocked_preflight, viewModel.uiState.value.noticeRes)
        assertNull(viewModel.uiState.value.unresolved)
    }

    @Test
    fun `journal write failure shows a notice and records nothing`() = runTest(dispatcher) {
        runCurrent()
        real.nextSubmitResult = SubmissionResult.JournalWriteFailed
        viewModel.selectPlus()
        viewModel.feed()
        runCurrent()
        assertEquals(R.string.blocked_journal_write, viewModel.uiState.value.noticeRes)
        assertNull(viewModel.uiState.value.unresolved)
    }

    @Test
    fun `a fresh deliberate attempt clears stale feedback`() = runTest(dispatcher) {
        runCurrent()
        real.nextSubmitResult = SubmissionResult.Blocked(SubmissionResult.Blocked.Reason.OFFLINE)
        viewModel.selectPlus()
        viewModel.feed()
        runCurrent()
        assertEquals(R.string.blocked_offline, viewModel.uiState.value.noticeRes)
        real.nextSubmitResult = null
        viewModel.feed()
        runCurrent()
        assertNull(viewModel.uiState.value.noticeRes)
        assertEquals(FeedState.ACCEPTED_UNCONFIRMED, viewModel.uiState.value.unresolved?.state)
    }

    @Test
    fun `read failure keeps the unresolved operation and explains itself`() = runTest(dispatcher) {
        runCurrent()
        real.state.value = CoordinatorState(
            unresolvedOperation = FeedOperation("op-r", "SN", 1, "req-r", 1, FeedState.ACCEPTED_UNCONFIRMED),
        )
        real.nextStatusOutcome = StatusCheckOutcome.READ_FAILED
        viewModel.checkStatus()
        runCurrent()
        assertEquals(R.string.status_read_failed, viewModel.uiState.value.noticeRes)
        // Uncertainty preserved; no feed request was made.
        assertNotNull(viewModel.uiState.value.unresolved)
        assertEquals(1, real.statusChecks)
        assertEquals(0, real.submits)
    }

    @Test
    fun `cancelled dispatch reconnects as unknown with no resend`() = runTest(dispatcher) {
        runCurrent()
        real.submitGate = CompletableDeferred()
        viewModel.selectPlus()
        viewModel.feed()
        runCurrent()
        assertEquals(1, real.submits)

        // Card dismissal cancels the submitting caller mid-flight.
        viewModel.viewModelScope.coroutineContext[Job]!!.cancel()
        runCurrent()

        // A new ViewModel reconnected to the SAME application coordinator sees
        // the conservative UNKNOWN and stays blocked from re-sending.
        val reconnected = FeedViewModel(FakeGraph(settingsFlow, real, demo))
        runCurrent()
        assertEquals(FeedState.UNKNOWN, reconnected.uiState.value.unresolved?.state)
        assertTrue(reconnected.uiState.value.coordinator.blocksNewSubmissions)
        assertFalse(reconnected.uiState.value.feedEnabled)

        reconnected.acknowledgeUnresolved()
        runCurrent()
        assertEquals(1, real.acknowledgements)
        // A recorded acknowledgement retires the active operation: the Feed
        // controls return without weakening the never-replayed guarantee.
        assertNull(reconnected.uiState.value.unresolved)
        // The reconnected card starts at zero selection (deliberate re-arm);
        // picking a portion re-enables feeding. The dead caller's write gate
        // died with it; a new deliberate attempt runs to completion.
        real.submitGate = null
        reconnected.selectPlus()
        runCurrent()
        assertTrue(reconnected.uiState.value.feedEnabled)
        reconnected.feed()
        runCurrent()
        assertEquals(2, real.submits) // NEW deliberate operation, not a resend
        assertEquals("op2", reconnected.uiState.value.unresolved?.id)
    }

    @Test
    fun `acknowledgement returns the same session to deliberate feed state`() = runTest(dispatcher) {
        runCurrent()
        viewModel.selectPlus()
        viewModel.selectPlus()
        viewModel.feed()
        runCurrent()
        assertEquals(FeedState.ACCEPTED_UNCONFIRMED, viewModel.uiState.value.unresolved?.state)
        assertFalse(viewModel.uiState.value.feedEnabled)

        viewModel.acknowledgeUnresolved()
        runCurrent()
        assertNull(viewModel.uiState.value.unresolved)
        assertTrue(viewModel.uiState.value.feedEnabled)
        assertEquals(1, real.acknowledgements)
        assertEquals(1, real.submits) // nothing was resent by acknowledging
    }

    @Test
    fun `stale success never latches for a new attempt that sends nothing`() = runTest(dispatcher) {
        runCurrent()
        // A previous operation resolved as success (e.g. restored/journal or
        // an earlier attempt): still the last resolved entry.
        real.state.value = CoordinatorState(
            lastResolved = FeedOperation("op-old", "SN", 3, "req-old", 1, FeedState.REPORTED_SUCCESS),
        )
        runCurrent()

        // A new deliberate attempt whose preflight holds, then fails offline.
        real.submitGate = CompletableDeferred()
        real.nextSubmitResult = SubmissionResult.Blocked(SubmissionResult.Blocked.Reason.OFFLINE)
        viewModel.selectPlus()
        viewModel.feed()
        runCurrent()
        // The coordinator's dispatching emission while preflight suspends:
        // the old success is still last resolved and nothing is unresolved.
        real.state.value = CoordinatorState(
            dispatching = true,
            lastResolved = FeedOperation("op-old", "SN", 3, "req-old", 1, FeedState.REPORTED_SUCCESS),
        )
        runCurrent()

        real.submitGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, real.submits)
        assertEquals(R.string.blocked_offline, viewModel.uiState.value.noticeRes)
        assertFalse("stale success must not latch", viewModel.uiState.value.successThisSession)
        assertFalse(viewModel.uiState.value.feedEnabled) // offline blocks
    }

    @Test
    fun `acknowledging the current accepted attempt never latches stale success`() = runTest(dispatcher) {
        runCurrent()
        real.state.value = CoordinatorState(
            lastResolved = FeedOperation("op-old", "SN", 3, "req-old", 1, FeedState.REPORTED_SUCCESS),
        )
        runCurrent()
        viewModel.selectPlus()
        viewModel.feed()
        runCurrent()
        assertEquals("op1", viewModel.uiState.value.unresolved?.id)

        viewModel.acknowledgeUnresolved()
        runCurrent()
        assertNull(viewModel.uiState.value.unresolved)
        assertFalse("acknowledged current attempt is not a success", viewModel.uiState.value.successThisSession)
        assertTrue(viewModel.uiState.value.feedEnabled)
    }

    @Test
    fun `rapidly resolved current success still latches done`() = runTest(dispatcher) {
        runCurrent()
        viewModel.selectPlus()
        viewModel.feed()
        runCurrent()
        // The poll resolves the current operation to success.
        real.state.value = CoordinatorState(
            lastResolved = FeedOperation("op1", "SN", 1, "req", 1, FeedState.REPORTED_SUCCESS),
        )
        runCurrent()
        assertTrue(viewModel.uiState.value.successThisSession)
        assertFalse(viewModel.uiState.value.feedEnabled) // Done stays latched
    }

    @Test
    fun `success resolved before the result lands still latches done`() = runTest(dispatcher) {
        runCurrent()
        val op = FeedOperation("op1", "SN", 1, "req", 1, FeedState.ACCEPTED_UNCONFIRMED)
        real.nextSubmitResult = SubmissionResult.Dispatched(op)
        real.submitGate = CompletableDeferred()
        viewModel.selectPlus()
        viewModel.feed()
        runCurrent()
        // The poll resolves while the submit result is still in flight; the
        // identity is not assigned yet, so nothing latches from stale state.
        real.state.value = CoordinatorState(
            lastResolved = op.copy(state = FeedState.REPORTED_SUCCESS),
        )
        runCurrent()
        assertFalse(viewModel.uiState.value.successThisSession)

        // The result lands; assigning the identity observes the latest state
        // and latches the Done panel for the genuinely current operation.
        real.submitGate!!.complete(Unit)
        runCurrent()
        assertTrue(viewModel.uiState.value.successThisSession)
    }
}
