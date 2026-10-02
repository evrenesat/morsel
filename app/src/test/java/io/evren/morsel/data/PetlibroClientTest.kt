package io.evren.morsel.data

import io.evren.morsel.domain.FeederException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PetlibroClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(token: String? = null, callTimeoutMs: Long = 5_000): PetlibroClient = PetlibroClient(
        baseUrl = server.url("/").toString().trimEnd('/'),
        http = PetlibroClient.newFeedHttpClient().newBuilder()
            .callTimeout(java.time.Duration.ofMillis(callTimeoutMs))
            .build(),
        tokenProvider = { token },
        timeZoneId = "Europe/Amsterdam",
    )

    private fun success(dataJson: String): MockResponse = MockResponse().setResponseCode(200).setBody("""{"code":0,"msg":"success","data":$dataJson}""")

    @Test
    fun `login posts documented payload and returns token`() = runTest {
        server.enqueue(success("""{"token":"tok-1"}"""))
        val token = client().login("US", "user@example.com", PasswordDigest.digest("pw"))
        assertEquals("tok-1", token)
        val recorded = server.takeRequest()
        assertEquals("/member/auth/login", recorded.path)
        assertNull(recorded.getHeader("token"))
        assertEquals("ANDROID", recorded.getHeader("source"))
        assertEquals("EN", recorded.getHeader("language"))
        assertEquals("Europe/Amsterdam", recorded.getHeader("timezone"))
        assertEquals("1.3.45", recorded.getHeader("version"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(JsonPrimitive(1), body["appId"])
        assertEquals("c35772530d1041699c87fe62348507a8", (body["appSn"] as JsonPrimitive).content)
        assertEquals("US", (body["country"] as JsonPrimitive).content)
        assertEquals("user@example.com", (body["email"] as JsonPrimitive).content)
        assertEquals(PasswordDigest.digest("pw"), (body["password"] as JsonPrimitive).content)
        assertEquals("", (body["phoneBrand"] as JsonPrimitive).content)
        assertEquals("", (body["phoneSystemVersion"] as JsonPrimitive).content)
        assertEquals("Europe/Amsterdam", (body["timezone"] as JsonPrimitive).content)
        assertTrue(body["thirdId"] is kotlinx.serialization.json.JsonNull)
        assertTrue(body["type"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `manual feeding accepts zero and null data shapes and posts exact payload`() = runTest {
        for (dataShape in listOf("0", "null")) {
            server.enqueue(success(dataShape))
        }
        client(token = "t").manualFeeding("SN1", 3, "abc123")
        val write = server.takeRequest()
        assertEquals("/device/device/manualFeeding", write.path)
        assertEquals("t", write.getHeader("token"))
        val body = Json.parseToJsonElement(write.body.readUtf8()).jsonObject
        assertEquals("SN1", (body["deviceSn"] as JsonPrimitive).content)
        assertEquals(3, (body["grainNum"] as JsonPrimitive).content.toInt())
        assertEquals("abc123", (body["requestId"] as JsonPrimitive).content)
        // Second response was the null-data shape; both must have been accepted.
        client(token = "t").manualFeeding("SN1", 3, "abc123")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `manual feeding rejects out of range portions without any request`() = runTest {
        assertFailsWith<IllegalArgumentException> { client().manualFeeding("SN1", 0, "r1") }
        assertFailsWith<IllegalArgumentException> { client().manualFeeding("SN1", 17, "r1") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `device list parses items and skips malformed entries`() = runTest {
        server.enqueue(
            success(
                """[{"deviceSn":"SN-PLAF","productIdentifier":"PLAF108","productName":"Air","name":"Feed"},
                    {"deviceSn":"missing-model"},
                    "garbage",
                    {"deviceSn":"","productIdentifier":"PLAF108"}]""",
            ),
        )
        val devices = client().deviceList()
        assertEquals(1, devices.size)
        assertEquals("SN-PLAF", devices[0].serial)
        assertEquals("PLAF108", devices[0].model)
        assertEquals("Air", devices[0].productName)
    }

    @Test
    fun `envelope data null and zero are preserved without crashing`() = runTest {
        server.enqueue(success("null"))
        assertTrue(client().deviceList().isEmpty())
        server.enqueue(success("0"))
        assertIs<FeederException.Transport>(runCatching { client().deviceList() }.exceptionOrNull())
    }

    @Test
    fun `realInfo parses online and defaults to offline when missing`() = runTest {
        server.enqueue(success("""{"online":true,"batteryState":"high"}"""))
        assertTrue(client().realInfo("SN").online)
        server.enqueue(success("""{"batteryState":"high"}"""))
        assertTrue(!client().realInfo("SN").online)
    }

    @Test
    fun `work records parse correlate sort dedupe and reject malformed`() = runTest {
        server.enqueue(
            success(
                """[{"day":"2026-10-01","workRecords":[
                        {"type":"GRAIN_OUTPUT_SUCCESS","recordTime":1000,"actualGrainNum":3},
                        {"type":"GRAIN_OUTPUT_SUCCESS","recordTime":5000,"actualGrainNum":1,"requestId":"req-1"},
                        {"type":"OTHER_EVENT","recordTime":9000,"actualGrainNum":9},
                        {"type":"GRAIN_OUTPUT_SUCCESS","recordTime":"bad","actualGrainNum":2},
                        {"type":"GRAIN_OUTPUT_SUCCESS","recordTime":3000},
                        {"recordTime":7000,"actualGrainNum":4},
                        {"type":"GRAIN_OUTPUT_SUCCESS","recordTime":1000,"actualGrainNum":3}
                     ]}]""",
            ),
        )
        val records = client().workRecords("SN", 0, 10_000)
        // Sorted newest first; duplicates removed; malformed/non-matching skipped;
        // record without amount is kept but must not carry a fabricated amount.
        assertEquals(listOf(5000L, 3000L, 1000L), records.map { it.recordTimeEpochMs })
        assertEquals("req-1", records[0].correlationId)
        assertEquals(1, records[0].actualGrainNum)
        assertNull(records[1].correlationId)
        assertNull(records[1].actualGrainNum)
    }

    @Test
    fun `auth expired 1009 maps to AuthExpired`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"code":1009,"msg":"not yet login"}"""),
        )
        assertIs<FeederException.AuthExpired>(runCatching { client().deviceList() }.exceptionOrNull())
    }

    @Test
    fun `unknown api error code maps to ApiError`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":42,"msg":"nope"}"""))
        val e = runCatching { client().deviceList() }.exceptionOrNull()
        assertIs<FeederException.ApiError>(e)
        assertEquals(42, e.code)
    }

    @Test
    fun `http 500 maps to Transport with no retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertIs<FeederException.Transport>(runCatching { client().deviceList() }.exceptionOrNull())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `malformed JSON maps to Transport`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not json</html>"))
        assertIs<FeederException.Transport>(runCatching { client().deviceList() }.exceptionOrNull())
    }

    @Test
    fun `redirect is never followed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"))
        assertIs<FeederException.Transport>(runCatching { client().deviceList() }.exceptionOrNull())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `call timeout maps to Transport with exactly one request`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"code":0,"data":[]}""").setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS),
        )
        val slow = PetlibroClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            http = OkHttpClient.Builder().callTimeout(java.time.Duration.ofMillis(200)).build(),
            tokenProvider = { null },
            timeZoneId = "UTC",
        )
        assertIs<FeederException.Transport>(runCatching { slow.deviceList() }.exceptionOrNull())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `work records with non-array data shape are rejected`() = runTest {
        server.enqueue(success("""{"unexpected":"shape"}"""))
        assertIs<FeederException.Transport>(runCatching { client().workRecords("SN", 0, 1) }.exceptionOrNull())
    }

    @Test
    fun `post body is json content type`() = runTest {
        server.enqueue(success("""{"token":"t"}"""))
        client().login("US", "e@x.com", "d")
        assertTrue(
            server.takeRequest().getHeader("Content-Type").orEmpty().startsWith("application/json"),
        )
    }
}
