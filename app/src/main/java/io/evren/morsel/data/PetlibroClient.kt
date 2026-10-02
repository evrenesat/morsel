package io.evren.morsel.data

import io.evren.morsel.domain.DeviceIdentity
import io.evren.morsel.domain.DeviceStatus
import io.evren.morsel.domain.FeedState
import io.evren.morsel.domain.FeederException
import io.evren.morsel.domain.FeederRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The Petlibro cloud HTTP client. Behavior is deliberately minimal and strict:
 *
 * - built on a dedicated OkHttp instance with retries, redirects and
 *   authenticators disabled and finite timeouts (see [newFeedHttpClient]);
 * - one call per invocation; callers decide what (never) to retry;
 * - envelope codes 0 / 1009 / other per docs/api-contract.md; `data` may be
 *   0, null, an object or an array and is validated where it is consumed;
 * - no response bodies are logged.
 */
class PetlibroClient(
    baseUrl: String,
    private val http: OkHttpClient,
    private val tokenProvider: () -> String?,
    private val timeZoneId: String,
    private val language: String = "EN",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun login(country: String, email: String, passwordDigest: String): String = withContext(ioDispatcher) {
        val body = buildJsonObject {
            put("appId", 1)
            put("appSn", APP_SN)
            put("country", country)
            put("email", email)
            put("password", passwordDigest)
            put("phoneBrand", "")
            put("phoneSystemVersion", "")
            put("timezone", timeZoneId)
            put("thirdId", JsonNull)
            put("type", JsonNull)
        }.toString()
        val data = call(LOGIN_PATH, body)
        val token = (data as? JsonObject)?.get("token")?.jsonPrimitive?.content
        if (token.isNullOrBlank()) {
            throw FeederException.Transport("login response without token")
        }
        token
    }

    suspend fun deviceList(): List<DeviceIdentity> = withContext(ioDispatcher) {
        when (val data = call(DEVICE_LIST_PATH, "{}")) {
            is JsonNull -> emptyList()
            is JsonArray -> data.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val serial = obj.stringField("deviceSn") ?: return@mapNotNull null
                val model = obj.stringField("productIdentifier") ?: return@mapNotNull null
                DeviceIdentity(
                    serial = serial,
                    model = model,
                    productName = obj.stringField("productName"),
                    name = obj.stringField("name"),
                )
            }
            else -> throw FeederException.Transport("device list data shape unexpected")
        }
    }

    suspend fun realInfo(serial: String): DeviceStatus = withContext(ioDispatcher) {
        val data = call(REAL_INFO_PATH, "{\"id\":${jsonString(serial)},\"deviceSn\":${jsonString(serial)}}")
        val online = (data as? JsonObject)?.get("online")
        DeviceStatus(online = (online as? JsonPrimitive)?.content == "true")
    }

    /** THE write. Success is envelope code 0 with any data shape; nothing here retries. */
    suspend fun manualFeeding(serial: String, portions: Int, requestId: String) {
        require(portions in FeedState.MIN_PORTIONS..FeedState.ABSOLUTE_MAX_PORTIONS) {
            "portions out of range: $portions"
        }
        require(requestId.all { it.isLetterOrDigit() } && requestId.isNotEmpty()) {
            "requestId must be alphanumerics without hyphens"
        }
        withContext(ioDispatcher) {
            call(
                MANUAL_FEEDING_PATH,
                "{\"deviceSn\":${jsonString(serial)},\"grainNum\":$portions,\"requestId\":${jsonString(requestId)}}",
            )
        }
        Unit
    }

    suspend fun workRecords(
        serial: String,
        fromEpochMs: Long,
        toEpochMs: Long,
    ): List<FeederRecord> = withContext(ioDispatcher) {
        val body =
            "{\"deviceSn\":${jsonString(serial)},\"startTime\":$fromEpochMs,\"endTime\":$toEpochMs,\"size\":25,\"type\":[\"GRAIN_OUTPUT_SUCCESS\"]}"
        when (val data = call(WORK_RECORD_PATH, body)) {
            is JsonNull -> emptyList()
            is JsonArray ->
                data
                    .flatMap { entry ->
                        val day = entry as? JsonObject
                        day?.get("workRecords") as? JsonArray ?: emptyList()
                    }
                    .mapNotNull { record ->
                        val obj = record as? JsonObject ?: return@mapNotNull null
                        if (obj.stringField("type") != SUCCESS_TYPE) return@mapNotNull null
                        val time = (obj["recordTime"] as? JsonPrimitive)?.content?.toLongOrNull()
                            ?: return@mapNotNull null
                        if (time < 0) return@mapNotNull null
                        val amount = (obj["actualGrainNum"] as? JsonPrimitive)?.content?.toIntOrNull()
                        val correlationId = obj.stringField("requestId")
                        FeederRecord(
                            correlationId = correlationId,
                            recordTimeEpochMs = time,
                            actualGrainNum = amount,
                        )
                    }
                    .sortedByDescending { it.recordTimeEpochMs }
                    .distinctBy { "${it.recordTimeEpochMs}:${it.actualGrainNum}:${it.correlationId ?: "-"}" }
            else -> throw FeederException.Transport("work record data shape unexpected")
        }
    }

    /** Single HTTP round trip; throws [FeederException] subclasses on any failure. */
    private fun call(path: String, body: String): JsonElement {
        val request = Request.Builder()
            .url(base + path)
            .header("source", "ANDROID")
            .header("language", language)
            .header("timezone", timeZoneId)
            .header("version", "1.3.45")
            .header("Content-Type", "application/json")
            .apply { tokenProvider()?.let { header("token", it) } }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw FeederException.Transport("network failure", e)
        }
        response.use { resp ->
            // followRedirects(false): a redirect arrives as a response and is refused.
            if (resp.isRedirect) {
                throw FeederException.Transport("redirect refused (http ${resp.code})")
            }
            if (!resp.isSuccessful) {
                throw FeederException.Transport("http ${resp.code}")
            }
            val text = try {
                resp.body?.string()
            } catch (e: IOException) {
                throw FeederException.Transport("body read failure", e)
            } ?: throw FeederException.Transport("empty body")
            val root = try {
                json.parseToJsonElement(text)
            } catch (e: Exception) {
                throw FeederException.Transport("malformed JSON", e)
            }
            val obj = root as? JsonObject ?: throw FeederException.Transport("envelope not an object")
            val code = (obj["code"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: throw FeederException.Transport("envelope without code")
            if (code == CODE_AUTH_EXPIRED) throw FeederException.AuthExpired()
            if (code != CODE_OK) {
                throw FeederException.ApiError(code, (obj["msg"] as? JsonPrimitive)?.content)
            }
            return obj["data"] ?: JsonNull
        }
    }

    private fun JsonObject.stringField(name: String): String? {
        val value = this[name] as? JsonPrimitive ?: return null
        if (value !is JsonNull && value.content.isNotBlank()) return value.content
        return null
    }

    private fun jsonString(value: String): String = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))

    companion object {
        const val BASE_URL_US = "https://api.us.petlibro.com"
        private const val APP_SN = "c35772530d1041699c87fe62348507a8"
        private const val LOGIN_PATH = "/member/auth/login"
        private const val DEVICE_LIST_PATH = "/device/device/list"
        private const val REAL_INFO_PATH = "/device/device/realInfo"
        private const val MANUAL_FEEDING_PATH = "/device/device/manualFeeding"
        private const val WORK_RECORD_PATH = "/device/workRecord/list"
        private const val SUCCESS_TYPE = "GRAIN_OUTPUT_SUCCESS"
        private const val CODE_OK = 0
        private const val CODE_AUTH_EXPIRED = 1009
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * The dedicated WRITE-SAFE client: no connection-failure retry, no
         * redirects, no authenticator, finite timeouts. Reads and writes share
         * it, but no layer retries a write through it.
         */
        fun newFeedHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .callTimeout(java.time.Duration.ofSeconds(20))
            .build()
    }
}
