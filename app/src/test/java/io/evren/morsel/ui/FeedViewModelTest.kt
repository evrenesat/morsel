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
        reconnected.selectPlus()
        reconnected.feed()
        runCurrent()
        assertEquals("no resend after reconnect", 1, real.submits)
    }
}
