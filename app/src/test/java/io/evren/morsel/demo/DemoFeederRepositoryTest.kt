package io.evren.morsel.demo

import io.evren.morsel.domain.FeederException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoFeederRepositoryTest {

    private var scenario = DemoScenario.SUCCESS_CORRELATED
    private var now = 5_000_000L
    private val repo = DemoFeederRepository(scenarioProvider = { scenario }, clock = { now })

    @Test
    fun `demo serves exactly one PLAF108 demo feeder`() = runTest {
        val devices = repo.devices()
        assertEquals(1, devices.size)
        assertEquals(DEMO_SERIAL, devices[0].serial)
        assertEquals("PLAF108", devices[0].model)
        assertTrue(devices[0].productName.orEmpty().contains("Demo"))
    }

    @Test
    fun `offline scenario reports offline`() = runTest {
        scenario = DemoScenario.OFFLINE
        assertFalse(repo.status(DEMO_SERIAL).online)
    }

    @Test
    fun `rejected scenario throws documented rejection`() = runTest {
        scenario = DemoScenario.REJECTED
        try {
            repo.sendFeed(DEMO_SERIAL, 1, "r1")
            error("expected rejection")
        } catch (e: FeederException.DocumentedRejection) {
            assertEquals(4101, e.code)
        }
    }

    @Test
    fun `timeout scenario throws transport failure`() = runTest {
        scenario = DemoScenario.TIMEOUT_UNKNOWN
        try {
            repo.sendFeed(DEMO_SERIAL, 1, "r1")
            error("expected transport failure")
        } catch (e: FeederException.Transport) {
            Unit
        }
    }

    @Test
    fun `success scenario correlates history to the dispatch`() = runTest {
        scenario = DemoScenario.SUCCESS_CORRELATED
        repo.sendFeed(DEMO_SERIAL, 3, "req-demo")
        now += 1_000
        val history = repo.feederHistory(DEMO_SERIAL, 0, now)
        val correlated = history.filter { it.correlationId == "req-demo" }
        assertEquals(1, correlated.size)
        assertEquals(3, correlated[0].actualGrainNum)
    }

    @Test
    fun `mismatch scenario correlates with a different amount`() = runTest {
        scenario = DemoScenario.MISMATCH
        repo.sendFeed(DEMO_SERIAL, 3, "req-demo")
        now += 1_000
        val correlated = repo.feederHistory(DEMO_SERIAL, 0, now).filter { it.correlationId == "req-demo" }
        assertEquals(4, correlated.single().actualGrainNum)
    }

    @Test
    fun `accepted-unconfirmed scenario has no correlated record`() = runTest {
        scenario = DemoScenario.ACCEPTED_UNCONFIRMED
        repo.sendFeed(DEMO_SERIAL, 2, "req-demo")
        now += 1_000
        val history = repo.feederHistory(DEMO_SERIAL, 0, now)
        assertTrue(history.none { it.correlationId == "req-demo" })
        assertTrue(history.isNotEmpty())
    }
}
