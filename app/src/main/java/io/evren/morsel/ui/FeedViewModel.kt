package io.evren.morsel.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.evren.morsel.MorselGraph
import io.evren.morsel.data.MorselSettings
import io.evren.morsel.data.PasswordDigest
import io.evren.morsel.demo.DemoScenario
import io.evren.morsel.domain.CoordinatorState
import io.evren.morsel.domain.DeviceIdentity
import io.evren.morsel.domain.FeedCoordinator
import io.evren.morsel.domain.FeedOperation
import io.evren.morsel.domain.FeedState
import io.evren.morsel.domain.FeederException
import io.evren.morsel.domain.FeedingCoordinator
import io.evren.morsel.domain.StatusCheckOutcome
import io.evren.morsel.domain.SubmissionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which screen the floating card shows. */
enum class CardScreen { SETUP, FEED, SETTINGS }

/** Setup flow state. */
sealed class SetupState {
    data object Welcome : SetupState()

    data object SigningIn : SetupState()

    data object Discovering : SetupState()

    /** Exactly one PLAF108 found; binding it is one explicit confirmation. */
    data class FoundOne(val device: DeviceIdentity) : SetupState()

    /** Multiple PLAF108 feeders: the user must pick a serial explicitly. */
    data class ChooseDevice(val devices: List<DeviceIdentity>) : SetupState()

    /** No PLAF108 in the account: helpful message, no guessing. */
    data object NoneFound : SetupState()

    data class Failed(val reason: SetupFailure) : SetupState()
}

enum class SetupFailure { AUTH, NETWORK, UNEXPECTED }

data class FeedUiState(
    val settings: MorselSettings = MorselSettings(),
    val screen: CardScreen = CardScreen.FEED,
    val setup: SetupState = SetupState.Welcome,
    val coordinator: CoordinatorState = CoordinatorState(),
    val selection: Int = 1,
    val demoMode: Boolean = false,
    /** Success observed in this session (never from a restored journal). */
    val successThisSession: Boolean = false,
) {
    val cap: Int get() = settings.portionCap

    val canChangeSelection: Boolean
        get() = !coordinator.dispatching && !coordinator.blocksNewSubmissions

    val feedEnabled: Boolean
        get() = canChangeSelection && !successThisSession && !demoOffline

    val unresolved: FeedOperation? get() = coordinator.unresolvedOperation

    val lastResolved: FeedOperation? get() = coordinator.lastResolved

    /** Demo OFFLINE scenario keeps the feed button disabled with a hint. */
    val demoOffline: Boolean
        get() = demoMode && settings.demoScenario == DemoScenario.OFFLINE.name
}

class FeedViewModel(private val graph: MorselGraph) : ViewModel() {

    private val realCoordinator: FeedingCoordinator = graph.realCoordinator
    private val demoCoordinator: FeedingCoordinator = graph.demoCoordinator

    private val screen = MutableStateFlow(CardScreen.FEED)
    private val setup = MutableStateFlow<SetupState>(SetupState.Welcome)
    private val selection = MutableStateFlow(1)
    private val successThisSession = MutableStateFlow(false)

    // Latest source values, maintained by collectors so action decisions never
    // depend on the (render-only) uiState pipeline having caught up.
    @Volatile private var latestSettings = MorselSettings()

    @Volatile private var latestReal = CoordinatorState()

    @Volatile private var latestDemo = CoordinatorState()

    /** Set once a dispatch has been made this session; success afterwards latches Done. */
    @Volatile
    private var dispatchMadeThisSession = false

    val uiState: StateFlow<FeedUiState> = combine(
        graph.settingsStore.settings,
        screen,
        setup,
        selection,
        successThisSession,
        realCoordinator.state,
        demoCoordinator.state,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val settings = values[0] as MorselSettings
        val isDemo = settings.demoMode
        FeedUiState(
            settings = settings,
            screen = values[1] as CardScreen,
            setup = values[2] as SetupState,
            coordinator = (if (isDemo) values[6] else values[5]) as CoordinatorState,
            selection = values[3] as Int,
            demoMode = isDemo,
            successThisSession = values[4] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FeedUiState())

    init {
        viewModelScope.launch {
            graph.settingsStore.settings.collect { latestSettings = it }
        }
        viewModelScope.launch {
            realCoordinator.state.collect { latestReal = it }
        }
        viewModelScope.launch {
            demoCoordinator.state.collect { latestDemo = it }
        }
        viewModelScope.launch {
            val initial = graph.settingsStore.settings.first()
            screen.value = if (initial.demoMode || initial.onboardingComplete) {
                CardScreen.FEED
            } else {
                CardScreen.SETUP
            }
        }
        // Latch in-session success: a REPORTED_SUCCESS reached after a deliberate
        // dispatch in this session shows Done and does not re-arm the Feed button.
        // A success merely restored from the journal belongs to a previous session.
        viewModelScope.launch {
            combine(
                realCoordinator.state,
                demoCoordinator.state,
                successThisSession,
            ) { real, demo, latched ->
                val active = if (uiState.value.demoMode) demo else real
                Triple(active.lastResolved?.state, active.unresolvedOperation, latched)
            }.collect { (lastState, unresolved, latched) ->
                if (!latched && dispatchMadeThisSession && lastState == FeedState.REPORTED_SUCCESS && unresolved == null) {
                    successThisSession.value = true
                }
            }
        }
    }

    fun selectPlus() = changeSelection(+1)

    fun selectMinus() = changeSelection(-1)

    /** Pure local selection changes never touch the coordinator or network. */
    private fun changeSelection(delta: Int) {
        val coordinator = if (latestSettings.demoMode) latestDemo else latestReal
        if (coordinator.dispatching || coordinator.blocksNewSubmissions) return
        val max = minOf(latestSettings.portionCap, FeedState.ABSOLUTE_MAX_PORTIONS)
        selection.value = (selection.value + delta).coerceIn(FeedState.MIN_PORTIONS, max)
    }

    /** The deliberate action. Duplicate taps are ignored by the coordinator. */
    fun feed() {
        val onboarded = latestSettings.onboardingComplete || latestSettings.demoMode
        val coordinator = if (latestSettings.demoMode) latestDemo else latestReal
        val demoOffline = latestSettings.demoMode &&
            latestSettings.demoScenario == DemoScenario.OFFLINE.name
        val allowed = onboarded && !coordinator.dispatching && !coordinator.blocksNewSubmissions &&
            !successThisSession.value && !demoOffline && screen.value == CardScreen.FEED
        if (!allowed) return
        val portions = selection.value
        dispatchMadeThisSession = true
        viewModelScope.launch {
            val result = (if (latestSettings.demoMode) demoCoordinator else realCoordinator).submit(portions)
            if (result is SubmissionResult.Blocked && result.reason == SubmissionResult.Blocked.Reason.UNRESOLVED_OPERATION) {
                // Keep the guard honest: nothing queued, nothing sent.
                Unit
            }
        }
    }

    fun checkStatus() {
        val coordinator = if (uiState.value.demoMode) demoCoordinator else realCoordinator
        viewModelScope.launch {
            val outcome = coordinator.checkStatus()
            if (outcome == StatusCheckOutcome.RESOLVED_SUCCESS) {
                successThisSession.value = true
            }
        }
    }

    /** Explicit "I checked the feeder": records resolution, never erases. */
    fun acknowledgeUnresolved() {
        val coordinator = if (uiState.value.demoMode) demoCoordinator else realCoordinator
        viewModelScope.launch { coordinator.acknowledgeUnresolved() }
    }

    /** Done: closes the card. The caller finishes the activity. */
    fun doneDismissed() {
        // Nothing to persist; resolved entries stay in the journal.
    }

    // --- Setup ---

    fun startDemo() {
        viewModelScope.launch {
            graph.settingsStore.setDemoMode(true)
            graph.settingsStore.setOnboardingComplete(true)
            screen.value = CardScreen.FEED
        }
    }

    fun exitDemo() {
        viewModelScope.launch {
            graph.settingsStore.setDemoMode(false)
            screen.value = CardScreen.SETUP
        }
    }

    fun setDemoScenario(scenario: DemoScenario) {
        viewModelScope.launch { graph.settingsStore.setDemoScenario(scenario) }
    }

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isEmpty()) return
        setup.value = SetupState.SigningIn
        viewModelScope.launch {
            try {
                val digest = PasswordDigest.digest(password)
                val token = graph.realRepository.login("US", email.trim(), digest)
                graph.auth.storeCredentials(email.trim(), digest, token)
                discover()
            } catch (e: FeederException.AuthExpired) {
                setup.value = SetupState.Failed(SetupFailure.AUTH)
            } catch (e: FeederException.ApiError) {
                setup.value = SetupState.Failed(SetupFailure.AUTH)
            } catch (e: FeederException) {
                setup.value = SetupState.Failed(SetupFailure.NETWORK)
            } catch (e: Exception) {
                setup.value = SetupState.Failed(SetupFailure.UNEXPECTED)
            }
        }
    }

    fun discover() {
        setup.value = SetupState.Discovering
        viewModelScope.launch {
            try {
                val plaf = graph.realRepository.devices()
                    .filter { it.model == "PLAF108" }
                when {
                    plaf.isEmpty() -> setup.value = SetupState.NoneFound
                    plaf.size == 1 -> setup.value = SetupState.FoundOne(plaf.single())
                    else -> setup.value = SetupState.ChooseDevice(plaf)
                }
            } catch (e: FeederException.AuthExpired) {
                setup.value = SetupState.Failed(SetupFailure.AUTH)
            } catch (e: FeederException) {
                setup.value = SetupState.Failed(SetupFailure.NETWORK)
            } catch (e: Exception) {
                setup.value = SetupState.Failed(SetupFailure.UNEXPECTED)
            }
        }
    }

    /** Bind exactly the chosen serial. Never substitutes another device. */
    fun bindDevice(serial: String) {
        setup.value = SetupState.Discovering
        viewModelScope.launch {
            graph.settingsStore.setBoundSerial(serial)
            graph.settingsStore.setOnboardingComplete(true)
            setup.value = SetupState.Welcome
            screen.value = CardScreen.FEED
        }
    }

    // --- Settings ---

    fun openSettings() {
        screen.value = CardScreen.SETTINGS
    }

    fun closeSettings() {
        screen.value = if (uiState.value.settings.onboardingComplete || uiState.value.settings.demoMode) {
            CardScreen.FEED
        } else {
            CardScreen.SETUP
        }
    }

    fun setCatName(name: String?) {
        viewModelScope.launch { graph.settingsStore.setCatName(name) }
    }

    fun setPortionCap(cap: Int) {
        viewModelScope.launch {
            graph.settingsStore.setPortionCap(cap)
            // Keep the selection within the new cap.
            selection.value = selection.value.coerceAtMost(cap.coerceAtLeast(FeedState.MIN_PORTIONS))
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { graph.settingsStore.setHapticsEnabled(enabled) }
    }

    fun setReduceMotion(enabled: Boolean) {
        viewModelScope.launch { graph.settingsStore.setReduceMotion(enabled) }
    }

    fun signOut() {
        viewModelScope.launch {
            graph.auth.signOut()
            graph.settingsStore.setBoundSerial(null)
            graph.settingsStore.setOnboardingComplete(false)
            screen.value = CardScreen.SETUP
            setup.value = SetupState.Welcome
        }
    }

    class Factory(private val graph: MorselGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FeedViewModel(graph) as T
    }
}
