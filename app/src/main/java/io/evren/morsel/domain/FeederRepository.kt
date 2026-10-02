package io.evren.morsel.domain

/** A feeder visible in the account's device list. */
data class DeviceIdentity(
    val serial: String,
    val model: String,
    val productName: String?,
    val name: String?,
)

/** Online state of the bound feeder; unknown parses conservatively to offline. */
data class DeviceStatus(val online: Boolean)

/**
 * One GRAIN_OUTPUT_SUCCESS feeder record.
 *
 * [correlationId] is only non-null when the record itself carries a
 * request/operation identifier. Observed upstream evidence contains no such
 * field, so most records will have null here and can never confirm an
 * operation on their own (see docs/api-contract.md).
 */
data class FeederRecord(
    val correlationId: String?,
    val recordTimeEpochMs: Long,
    val actualGrainNum: Int?,
)

/** Settings the coordinator needs for one submission decision. */
data class FeedSettings(
    val boundSerial: String?,
    val portionCap: Int,
)

/** Source of settings for the coordinator; implemented by the settings store. */
fun interface FeedSettingsSource {
    suspend fun snapshot(): FeedSettings
}

/** Thrown by the repository layer. Morsel never retries writes on these. */
sealed class FeederException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 1009 from a read or write; refresh is allowed for reads only. */
    class AuthExpired : FeederException("authentication expired")

    /** Documented, explicit API rejection (code 0 and 1009 are the only codes
     *  observed upstream, so the production client never produces this today;
     *  it exists so an evidenced rejection can be mapped to REJECTED). */
    class DocumentedRejection(val code: Int, detail: String?) : FeederException("documented rejection $code: ${detail ?: "-"}")

    /** Any other API error code; conservative UNKNOWN downstream. */
    class ApiError(val code: Int, message: String?) : FeederException("api error $code")

    /** HTTP status failure, timeout, I/O failure, malformed body, redirect attempt. */
    class Transport(message: String, cause: Throwable? = null) : FeederException(message, cause)
}

/** The five cloud operations Morsel uses. Reads may re-login once on 1009; the
 *  write must never be retried by any implementation of this interface. */
interface FeederRepository {
    suspend fun login(country: String, email: String, passwordDigest: String): String

    suspend fun devices(): List<DeviceIdentity>

    suspend fun status(serial: String): DeviceStatus

    /** Single deliberate write. Throws on any failure; implementations must not retry. */
    suspend fun sendFeed(serial: String, portions: Int, requestId: String)

    suspend fun feederHistory(serial: String, fromEpochMs: Long, toEpochMs: Long): List<FeederRecord>
}
