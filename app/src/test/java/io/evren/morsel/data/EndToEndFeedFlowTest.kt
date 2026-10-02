package io.evren.morsel.data

import io.evren.morsel.domain.FeedCoordinator
import io.evren.morsel.domain.FeedSettings
import io.evren.morsel.domain.FeedState
import io.evren.morsel.domain.SubmissionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Full production path: coordinator → PetlibroFeederRepository → PetlibroClient
 * → MockWebServer. Proves the read/write retry separation and single-write
 * guarantee against the real HTTP code, not a stand-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EndToEndFeedFlowTest {

    private lateinit var server: MockWebServer

    /** Scripted responses per path, consumed in order. */
    private val script = mutableMapOf<String, ArrayDeque<MockResponse>>()
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val queue = script[request.path ?: ""]
                    ?: return MockResponse().setResponseCode(200)
                        .setBody("""{"code":0,"msg":"success","data":0}""")
                return queue.removeFirstOrNull()
                    ?: MockResponse().setResponseCode(200)
                        .setBody("""{"code":0,"msg":"success","data":0}""")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(path: String, response: MockResponse) {
        script.getOrPut(path) { ArrayDeque() }.addLast(response)
    }

    private fun envelope(code: Int, dataJson: String = "0"): MockResponse = MockResponse().setResponseCode(200).setBody("""{"code":$code,"msg":"m","data":$dataJson}""")

    private fun countRequests(path: String) = requests.count { it.path == path }

    private suspend fun TestScope.buildCoordinator(): Pair<FeedCoordinator, AuthManager> {
        lateinit var auth: AuthManager
        val client = PetlibroClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            http = PetlibroClient.newFeedHttpClient(),
            tokenProvider = { auth.currentToken() },
            timeZoneId = "Europe/Amsterdam",
        )
        auth = AuthManager(InMemoryCredentialStore()) { country, email, digest ->
            client.login(country, email, digest)
        }
        auth.storeCredentials("user@example.com", PasswordDigest.digest("pw"), "tok-1")
        val repository = PetlibroFeederRepository(client, auth)
        val coordinator = FeedCoordinator(
            repository = repository,
            journal = InMemoryJournal(),
            settingsSource = { FeedSettings("SN-PLAF", 16) },
            scope = backgroundScope,
            clock = { 1_000_000L + testScheduler.currentTime },
        )
        return coordinator to auth
    }

    @Test
    fun `read 1009 refreshes once and retries once - write 1009 never retries`() = runTest {
        enqueue("/device/device/list", envelope(1009, "null"))
        enqueue("/member/auth/login", envelope(0, """{"token":"tok-2"}"""))
        enqueue(
            "/device/device/list",
            MockResponse().setResponseCode(200)
                .setBody("""{"code":0,"msg":"m","data":[{"deviceSn":"SN-PLAF","productIdentifier":"PLAF108"}]}"""),
        )
        enqueue("/device/device/realInfo", envelope(0, """{"online":true}"""))
        enqueue("/device/workRecord/list", envelope(0, "[]"))
        // THE write gets an auth expiry from the server.
        enqueue("/device/device/manualFeeding", envelope(1009, "null"))

        val (coordinator, auth) = buildCoordinator()

        val result = coordinator.submit(2)

        assertTrue(result is SubmissionResult.Unresolved)
        assertTrue((result as SubmissionResult.Unresolved).authExpired)
        assertEquals(FeedState.UNKNOWN, result.operation.state)
        // Reads: list hit 1009 once, one login, one list retry.
        assertEquals(2, countRequests("/device/device/list"))
        assertEquals(1, countRequests("/member/auth/login"))
        assertEquals("tok-2", auth.currentToken())
        // Write: exactly one attempt, and NO re-login/replay afterwards.
        assertEquals(1, countRequests("/device/device/manualFeeding"))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, countRequests("/device/device/manualFeeding"))
        assertEquals(1, countRequests("/member/auth/login"))
    }

    @Test
    fun `full accepted flow over real client polls and confirms`() = runTest {
        enqueue(
            "/device/device/list",
            MockResponse().setResponseCode(200)
                .setBody("""{"code":0,"msg":"m","data":[{"deviceSn":"SN-PLAF","productIdentifier":"PLAF108"}]}"""),
        )
        enqueue("/device/device/realInfo", envelope(0, """{"online":true}"""))
        enqueue("/device/workRecord/list", envelope(0, "[]")) // baseline
        enqueue("/device/device/manualFeeding", envelope(0, "0")) // accepted, data=0

        val (coordinator, _) = buildCoordinator()
        val result = coordinator.submit(3)
        assertTrue(result is SubmissionResult.Dispatched)
        val requestId = (result as SubmissionResult.Dispatched).operation.requestId
        assertEquals(1, countRequests("/device/device/manualFeeding"))
        assertEquals(32, requestId.length) // uuid without hyphens

        // The write request body must carry the frozen values.
        val write = requests.last { it.path == "/device/device/manualFeeding" }
        val body = write.body.readUtf8()
        assertTrue(body.contains("\"grainNum\":3"))
        assertTrue(body.contains("\"requestId\":\"$requestId\""))
        assertTrue(body.contains("\"deviceSn\":\"SN-PLAF\""))

        // Feeder eventually reports a correlated record with the matching amount.
        enqueue(
            "/device/workRecord/list",
            MockResponse().setResponseCode(200)
                .setBody(
                    """{"code":0,"msg":"m","data":[{"day":"d","workRecords":[
                        {"type":"GRAIN_OUTPUT_SUCCESS","recordTime":1009000,"actualGrainNum":3,
                         "requestId":"$requestId"}]}]}""",
                ),
        )
        advanceTimeBy(3_100)
        runCurrent()
        // The poll performs a real HTTP round trip on Dispatchers.IO; wait in
        // real time for the state to settle (virtual time cannot await sockets).
        val deadline = System.currentTimeMillis() + 10_000
        while (coordinator.state.value.lastResolved?.state != FeedState.REPORTED_SUCCESS) {
            check(System.currentTimeMillis() < deadline) { "poll never confirmed the operation" }
            Thread.sleep(50)
            runCurrent() // drain continuations queued on the virtual scheduler
        }
    }

    @Test
    fun `server 500 on write yields unknown with a single attempt`() = runTest {
        enqueue(
            "/device/device/list",
            MockResponse().setResponseCode(200)
                .setBody("""{"code":0,"msg":"m","data":[{"deviceSn":"SN-PLAF","productIdentifier":"PLAF108"}]}"""),
        )
        enqueue("/device/device/realInfo", envelope(0, """{"online":true}"""))
        enqueue("/device/workRecord/list", envelope(0, "[]"))
        enqueue("/device/device/manualFeeding", MockResponse().setResponseCode(500).setBody("boom"))

        val (coordinator, _) = buildCoordinator()
        val result = coordinator.submit(1)

        assertTrue(result is SubmissionResult.Unresolved)
        assertEquals(FeedState.UNKNOWN, (result as SubmissionResult.Unresolved).operation.state)
        assertEquals(1, countRequests("/device/device/manualFeeding"))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, countRequests("/device/device/manualFeeding"))
    }

    @Test
    fun `redirect on write is refused not followed`() = runTest {
        enqueue(
            "/device/device/list",
            MockResponse().setResponseCode(200)
                .setBody("""{"code":0,"msg":"m","data":[{"deviceSn":"SN-PLAF","productIdentifier":"PLAF108"}]}"""),
        )
        enqueue("/device/device/realInfo", envelope(0, """{"online":true}"""))
        enqueue("/device/workRecord/list", envelope(0, "[]"))
        enqueue(
            "/device/device/manualFeeding",
            MockResponse().setResponseCode(302).setHeader("Location", server.url("/device/other").toString()),
        )

        val (coordinator, _) = buildCoordinator()
        val result = coordinator.submit(1)

        assertTrue(result is SubmissionResult.Unresolved)
        assertEquals(1, countRequests("/device/device/manualFeeding"))
        assertEquals(0, countRequests("/device/other"))
    }

    @Test
    fun `read transport failure on preflight blocks the send`() = runTest {
        enqueue("/device/device/list", MockResponse().setResponseCode(500).setBody("x"))

        val (coordinator, _) = buildCoordinator()
        val result = coordinator.submit(1)

        assertEquals(SubmissionResult.Blocked.Reason.PREFLIGHT_FAILED, (result as SubmissionResult.Blocked).reason)
        assertEquals(0, countRequests("/device/device/manualFeeding"))
    }
}

/** Simple in-memory credential store for JVM tests. */
internal class InMemoryCredentialStore : CredentialStore {
    var savedEmail: String? = null
    var savedDigest: String? = null
    var savedToken: String? = null
    var cleared = false

    override suspend fun save(email: String, passwordDigest: String) {
        savedEmail = email
        savedDigest = passwordDigest
    }

    override suspend fun saveToken(token: String?) {
        savedToken = token
    }

    override suspend fun read(): StoredCredentials? {
        if (cleared || savedEmail == null || savedDigest == null) return null
        return StoredCredentials(savedEmail!!, savedDigest!!, savedToken)
    }

    override suspend fun clear() {
        cleared = true
        savedEmail = null
        savedDigest = null
        savedToken = null
    }
}

/** In-memory FeedJournal used by full-stack coordinator tests. */
internal class InMemoryJournal : io.evren.morsel.domain.FeedJournal {
    val ops = mutableListOf<io.evren.morsel.domain.FeedOperation>()

    override suspend fun all(): List<io.evren.morsel.domain.FeedOperation> = ops.toList()

    override suspend fun upsert(operation: io.evren.morsel.domain.FeedOperation) {
        ops.removeAll { it.id == operation.id }
        ops.add(operation)
    }
}
