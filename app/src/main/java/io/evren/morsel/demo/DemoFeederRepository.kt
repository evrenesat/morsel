package io.evren.morsel.demo

import io.evren.morsel.domain.DeviceIdentity
import io.evren.morsel.domain.DeviceStatus
import io.evren.morsel.domain.FeederException
import io.evren.morsel.domain.FeederRecord
import io.evren.morsel.domain.FeederRepository

/** Scripted demo scenarios, clearly surfaced as demo-only in the UI. */
enum class DemoScenario {
    /** Write accepted; a correlated matching work record appears: REPORTED_SUCCESS. */
    SUCCESS_CORRELATED,

    /** Write accepted; only uncorrelated records appear: stays ACCEPTED_UNCONFIRMED. */
    ACCEPTED_UNCONFIRMED,

    /** Write gets a documented rejection: REJECTED. */
    REJECTED,

    /** Write times out: UNKNOWN, never retried. */
    TIMEOUT_UNKNOWN,

    /** Write accepted; correlated record with a different amount: REPORTED_MISMATCH. */
    MISMATCH,

    /** Feeder shows offline: submission is blocked. */
    OFFLINE,
}

internal const val DEMO_SERIAL = "DEMO-PLAF108-0001"

/**
 * Demo repository for the clearly-labelled demo route: no credentials, no real
 * serial, and its own journal. It simulates exactly one PLAF108 so the full
 * coordinator flow can be exercised without any cloud account.
 */
class DemoFeederRepository(
    private val scenarioProvider: () -> DemoScenario,
    private val clock: () -> Long = System::currentTimeMillis,
) : FeederRepository {

    /** The demo dispatch the history simulator answers to. */
    internal data class DemoDispatch(
        val serial: String,
        val portions: Int,
        val requestId: String,
        val atEpochMs: Long,
    )

    @Volatile
    internal var lastDispatch: DemoDispatch? = null
        private set

    override suspend fun login(country: String, email: String, passwordDigest: String): String = "demo-token"

    override suspend fun devices(): List<DeviceIdentity> = listOf(
        DeviceIdentity(
            serial = DEMO_SERIAL,
            model = "PLAF108",
            productName = "Demo Smart Air Feeder",
            name = "Demo feeder",
        ),
    )

    override suspend fun status(serial: String): DeviceStatus {
        check(serial == DEMO_SERIAL)
        return DeviceStatus(online = scenarioProvider() != DemoScenario.OFFLINE)
    }

    override suspend fun sendFeed(serial: String, portions: Int, requestId: String) {
        check(serial == DEMO_SERIAL)
        when (scenarioProvider()) {
            DemoScenario.REJECTED ->
                throw FeederException.DocumentedRejection(4101, "demo: scripted rejection")
            DemoScenario.TIMEOUT_UNKNOWN ->
                throw FeederException.Transport("demo: simulated timeout")
            else -> lastDispatch = DemoDispatch(serial, portions, requestId, clock())
        }
    }

    override suspend fun feederHistory(
        serial: String,
        fromEpochMs: Long,
        toEpochMs: Long,
    ): List<FeederRecord> {
        check(serial == DEMO_SERIAL)
        val dispatch = lastDispatch ?: return oldUncorrelatedRecords()
        return when (scenarioProvider()) {
            DemoScenario.SUCCESS_CORRELATED -> oldUncorrelatedRecords() + FeederRecord(
                correlationId = dispatch.requestId,
                recordTimeEpochMs = dispatch.atEpochMs + DISPENSE_DELAY_MS,
                actualGrainNum = dispatch.portions,
            )
            DemoScenario.MISMATCH -> oldUncorrelatedRecords() + FeederRecord(
                correlationId = dispatch.requestId,
                recordTimeEpochMs = dispatch.atEpochMs + DISPENSE_DELAY_MS,
                actualGrainNum = dispatch.portions + 1,
            )
            else -> oldUncorrelatedRecords() + FeederRecord(
                correlationId = null,
                recordTimeEpochMs = dispatch.atEpochMs + DISPENSE_DELAY_MS,
                actualGrainNum = dispatch.portions,
            )
        }.sortedByDescending { it.recordTimeEpochMs }
    }

    private fun oldUncorrelatedRecords(): List<FeederRecord> = listOf(
        FeederRecord(correlationId = null, recordTimeEpochMs = clock() - 86_400_000L, actualGrainNum = 2),
        FeederRecord(correlationId = null, recordTimeEpochMs = clock() - 2 * 86_400_000L, actualGrainNum = 3),
    )

    private companion object {
        const val DISPENSE_DELAY_MS = 900L
    }
}
