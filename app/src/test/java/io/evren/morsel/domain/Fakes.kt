package io.evren.morsel.domain

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

/** Scriptable fake; counts every call so tests can assert exact HTTP traffic. */
internal class FakeRepository : FeederRepository {
    var devicesResponse: List<DeviceIdentity> =
        listOf(DeviceIdentity(serial = "SN1", model = "PLAF108", productName = null, name = null))
    var statusResponse = DeviceStatus(online = true)
    var sendFeedError: FeederException? = null

    /** When set, sendFeed suspends until completed: models an in-flight write. */
    var writeGate: CompletableDeferred<Unit>? = null
    private val historyResponses = ArrayDeque<Any>()
    private val fallbackHistory = mutableListOf<FeederRecord>()

    val loginCalls = AtomicInteger()
    val deviceCalls = AtomicInteger()
    val statusCalls = AtomicInteger()
    val writeCalls = AtomicInteger()
    val historyCalls = AtomicInteger()
    val written = mutableListOf<Triple<String, Int, String>>()

    /** Queue of responses (or Throwables) consumed by feederHistory in order. */
    fun enqueueHistory(vararg responses: Any) {
        responses.forEach { historyResponses.addLast(it) }
    }

    fun setFallbackHistory(records: List<FeederRecord>) {
        fallbackHistory.clear()
        fallbackHistory.addAll(records)
    }

    override suspend fun login(country: String, email: String, passwordDigest: String): String {
        loginCalls.incrementAndGet()
        return "fake-token"
    }

    override suspend fun devices(): List<DeviceIdentity> {
        deviceCalls.incrementAndGet()
        return devicesResponse
    }

    override suspend fun status(serial: String): DeviceStatus {
        statusCalls.incrementAndGet()
        return statusResponse
    }

    override suspend fun sendFeed(serial: String, portions: Int, requestId: String) {
        writeCalls.incrementAndGet()
        written.add(Triple(serial, portions, requestId))
        sendFeedError?.let { throw it }
        writeGate?.await()
    }

    override suspend fun feederHistory(
        serial: String,
        fromEpochMs: Long,
        toEpochMs: Long,
    ): List<FeederRecord> {
        historyCalls.incrementAndGet()
        return when (val next = historyResponses.removeFirstOrNull()) {
            is Throwable -> throw next
            is List<*> -> next.filterIsInstance<FeederRecord>()
            null -> fallbackHistory.toList()
            else -> error("bad queued history $next")
        }
    }
}

/** In-memory journal with injectable persistence failures. */
internal class FakeJournal : FeedJournal {
    val ops = mutableListOf<FeedOperation>()
    var failNextUpserts = 0
    var failAllUpserts = false

    /** Fail only outcome/ack upserts (state != DISPATCHING), after a dispatch persisted. */
    var failOutcomeUpserts = false

    override suspend fun all(): List<FeedOperation> = ops.toList()

    override suspend fun upsert(operation: FeedOperation) {
        val fail = failAllUpserts ||
            failNextUpserts > 0 ||
            (failOutcomeUpserts && operation.state != FeedState.DISPATCHING)
        if (fail) {
            if (!failAllUpserts && failNextUpserts > 0) failNextUpserts--
            throw JournalPersistenceException()
        }
        ops.removeAll { it.id == operation.id }
        ops.add(operation)
    }

    fun unresolved(): FeedOperation? = ops.lastOrNull { it.unresolved }
}

internal fun fakeSettings(serial: String? = "SN1", cap: Int = 16) = FeedSettingsSource {
    FeedSettings(boundSerial = serial, portionCap = cap)
}

internal fun record(correlationId: String?, time: Long, amount: Int?) = FeederRecord(correlationId = correlationId, recordTimeEpochMs = time, actualGrainNum = amount)
