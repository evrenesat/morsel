package io.evren.morsel.ui

import io.evren.morsel.MorselGraph
import io.evren.morsel.data.AuthManager
import io.evren.morsel.data.CredentialStore
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Test fakes for the ViewModel. No Android dependencies. */
internal class RecordingCoordinator : FeedingCoordinator {
    var submits = 0
    var statusChecks = 0
    var acknowledgements = 0
    override val state = MutableStateFlow(CoordinatorState())

    override suspend fun submit(portions: Int): SubmissionResult {
        submits++
        val op = FeedOperation("op$submits", "SN", portions, "req", 1, FeedState.ACCEPTED_UNCONFIRMED)
        state.value = CoordinatorState(unresolvedOperation = op)
        return SubmissionResult.Dispatched(op)
    }

    override suspend fun restore() = Unit

    override suspend fun checkStatus(): StatusCheckOutcome {
        statusChecks++
        return StatusCheckOutcome.NO_OPERATION
    }

    override suspend fun acknowledgeUnresolved(): Boolean {
        acknowledgements++
        return true
    }

    override suspend fun unresolvedAfterRebind(): FeedOperation? = state.value.unresolvedOperation
}

internal class FakeSettingsStore(flow: MutableStateFlow<MorselSettings>) : MorselSettingsStore {
    private val backing = flow
    override val settings: Flow<MorselSettings> get() = backing

    override suspend fun snapshot(): FeedSettings = FeedSettings(backing.value.boundSerial, backing.value.portionCap)

    override suspend fun setBoundSerial(serial: String?) {
        backing.value = backing.value.copy(boundSerial = serial)
    }
    override suspend fun setCatName(name: String?) {
        backing.value = backing.value.copy(catName = name)
    }
    override suspend fun setPortionCap(cap: Int) {
        backing.value = backing.value.copy(portionCap = cap)
    }
    override suspend fun setHapticsEnabled(enabled: Boolean) {
        backing.value = backing.value.copy(hapticsEnabled = enabled)
    }
    override suspend fun setReduceMotion(enabled: Boolean) {
        backing.value = backing.value.copy(reduceMotion = enabled)
    }
    override suspend fun setDemoMode(enabled: Boolean) {
        backing.value = backing.value.copy(demoMode = enabled)
    }
    override suspend fun setDemoScenario(scenario: DemoScenario) {
        backing.value = backing.value.copy(demoScenario = scenario.name)
    }
    override suspend fun setOnboardingComplete(done: Boolean) {
        backing.value = backing.value.copy(onboardingComplete = done)
    }
}

internal class NoRepository : FeederRepository {
    override suspend fun login(country: String, email: String, passwordDigest: String) = "t"
    override suspend fun devices(): List<DeviceIdentity> = emptyList()
    override suspend fun status(serial: String) = DeviceStatus(true)
    override suspend fun sendFeed(serial: String, portions: Int, requestId: String) = Unit
    override suspend fun feederHistory(serial: String, fromEpochMs: Long, toEpochMs: Long): List<FeederRecord> = emptyList()
}

internal class FakeGraph(
    settings: MutableStateFlow<MorselSettings>,
    realCoordinator: FeedingCoordinator,
    demoCoordinator: FeedingCoordinator,
) : MorselGraph {
    override val settingsStore = FakeSettingsStore(settings)
    override val auth = io.evren.morsel.data.AuthManager(
        object : io.evren.morsel.data.CredentialStore {
            override suspend fun save(email: String, passwordDigest: String) = Unit
            override suspend fun saveToken(token: String?) = Unit
            override suspend fun read(): StoredCredentials? = null
            override suspend fun clear() = Unit
        },
    ) { _, _, _ -> "t" }
    override val realRepository = NoRepository()
    override val realCoordinator = realCoordinator
    override val demoCoordinator = demoCoordinator
}
