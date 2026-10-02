package io.evren.morsel.ui

import io.evren.morsel.MorselGraph
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
