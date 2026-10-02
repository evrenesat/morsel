package io.evren.morsel

import android.app.Application
import io.evren.morsel.data.AuthManager
import io.evren.morsel.data.CredentialVault
import io.evren.morsel.data.DataStoreFeedJournal
import io.evren.morsel.data.MorselSettingsStore
import io.evren.morsel.data.PetlibroClient
import io.evren.morsel.data.PetlibroFeederRepository
import io.evren.morsel.data.SettingsStore
import io.evren.morsel.demo.DEMO_SERIAL
import io.evren.morsel.demo.DemoFeederRepository
import io.evren.morsel.demo.DemoJournal
import io.evren.morsel.domain.FeedCoordinator
import io.evren.morsel.domain.FeedSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Contract the UI uses from the dependency graph; fakes implement it in tests. */
interface MorselGraph {
    val settingsStore: io.evren.morsel.data.MorselSettingsStore
    val auth: io.evren.morsel.data.AuthManager
    val realRepository: io.evren.morsel.domain.FeederRepository
    val realCoordinator: io.evren.morsel.domain.FeedingCoordinator
    val demoCoordinator: io.evren.morsel.domain.FeedingCoordinator

    /** The real persistent journal; lets tests seed a pre-dispatch operation. */
    val realJournal: io.evren.morsel.domain.FeedJournal
}

/**
 * Application-scoped dependency graph. The real and demo worlds are fully
 * separated: different repositories, different journals (disk vs in-memory),
 * different serials. Nothing is shared except the local preferences that hold
 * which mode is active.
 */
class AppGraph(context: MorselApplication, scope: CoroutineScope) : MorselGraph {

    override val settingsStore: MorselSettingsStore = SettingsStore(context, scope)

    private val credentialStore = CredentialVault(context)

    private val client: PetlibroClient = PetlibroClient(
        baseUrl = PetlibroClient.BASE_URL_US,
        http = PetlibroClient.newFeedHttpClient(),
        tokenProvider = { auth.currentToken() },
        timeZoneId = ZoneId.systemDefault().id,
    )

    override val auth: AuthManager = AuthManager(credentialStore) { country, email, digest ->
        client.login(country, email, digest)
    }

    override val realRepository: io.evren.morsel.domain.FeederRepository = PetlibroFeederRepository(client, auth)

    override val realJournal: io.evren.morsel.domain.FeedJournal = DataStoreFeedJournal(context, scope)

    private val demoRepository = DemoFeederRepository(
        scenarioProvider = { demoScenario },
    )

    /** Kept in sync from the settings flow; read by the demo repository. */
    @Volatile
    var demoScenario: io.evren.morsel.demo.DemoScenario =
        io.evren.morsel.demo.DemoScenario.SUCCESS_CORRELATED
        private set

    override val realCoordinator: io.evren.morsel.domain.FeedingCoordinator = FeedCoordinator(
        repository = realRepository,
        journal = realJournal,
        settingsSource = { settingsStore.snapshot() },
        scope = scope,
    )

    override val demoCoordinator: io.evren.morsel.domain.FeedingCoordinator = FeedCoordinator(
        repository = demoRepository,
        journal = DemoJournal(),
        settingsSource = {
            // Demo has its own serial; the cap is a local preference and is
            // safe to share. Credentials and the real journal are never touched.
            FeedSettings(boundSerial = DEMO_SERIAL, portionCap = settingsStore.snapshot().portionCap)
        },
        scope = scope,
        pollDelaysMs = listOf(600L, 1_500L, 3_000L),
    )

    init {
        scope.launch {
            auth.warmCache()
            settingsStore.settings.collect {
                demoScenario = io.evren.morsel.demo.DemoScenario.valueOf(it.demoScenario)
            }
        }
        scope.launch { realCoordinator.restore() }
        scope.launch { demoCoordinator.restore() }
    }
}

class MorselApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val graph: AppGraph by lazy { AppGraph(this, appScope) }
}
