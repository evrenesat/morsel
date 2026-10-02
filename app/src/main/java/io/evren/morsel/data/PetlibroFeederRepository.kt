package io.evren.morsel.data

import io.evren.morsel.domain.DeviceIdentity
import io.evren.morsel.domain.DeviceStatus
import io.evren.morsel.domain.FeederException
import io.evren.morsel.domain.FeederRecord
import io.evren.morsel.domain.FeederRepository

/**
 * Production repository. Reads get exactly one 1009-driven re-login and one
 * retry; the write path ([sendFeed]) is strictly single-shot — failures
 * propagate to the coordinator which maps them to UNKNOWN, never to a replay.
 */
class PetlibroFeederRepository(
    private val client: PetlibroClient,
    private val auth: AuthManager,
) : FeederRepository {

    override suspend fun login(country: String, email: String, passwordDigest: String): String = client.login(country, email, passwordDigest)

    override suspend fun devices(): List<DeviceIdentity> = readOnceRefreshing { client.deviceList() }

    override suspend fun status(serial: String): DeviceStatus = readOnceRefreshing { client.realInfo(serial) }

    override suspend fun sendFeed(serial: String, portions: Int, requestId: String) {
        // No retry, no refresh, no second attempt. Ever.
        client.manualFeeding(serial, portions, requestId)
    }

    override suspend fun feederHistory(
        serial: String,
        fromEpochMs: Long,
        toEpochMs: Long,
    ): List<FeederRecord> = readOnceRefreshing { client.workRecords(serial, fromEpochMs, toEpochMs) }

    private suspend fun <T> readOnceRefreshing(block: suspend () -> T): T = try {
        block()
    } catch (e: FeederException.AuthExpired) {
        try {
            auth.refresh()
        } catch (credentials: AuthManager.CredentialsMissing) {
            throw FeederException.AuthExpired()
        }
        block()
    }
}
