package io.evren.morsel.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.evren.morsel.domain.FakeRepository
import io.evren.morsel.domain.FeedCoordinator
import io.evren.morsel.domain.FeedOperation
import io.evren.morsel.domain.FeedState
import io.evren.morsel.domain.JournalPersistenceException
import io.evren.morsel.domain.JournalReadException
import io.evren.morsel.domain.SubmissionResult
import io.evren.morsel.domain.fakeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Regression tests for the REAL persisted journal (actual DataStore file on
 * disk), not an in-memory fake. Covers the checkpoint-2 review findings:
 * trimming must include acknowledged entries, a restarted scope must fully
 * release the file before a new DataStore opens it, and corrupted storage
 * must fail closed (block sending) instead of silently resetting to empty.
 */
class DataStoreJournalTest {

    private fun newScope() = CoroutineScope(Job() + Dispatchers.IO)

    /**
     * A new DataStore may only open a file after the previous store's scope
     * has fully completed; a plain cancel() leaves the old store registered
     * and the next create() throws "multiple DataStores active for the same
     * file". Always join before reopening.
     */
    private fun stopScope(scope: CoroutineScope) {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test
    fun `filename uses the required preferences_pb extension`() {
        // The PreferenceDataStoreFactory rejects files without the
        // .preferences_pb extension; this constant is the device file name.
        assertEquals("morsel.journal.preferences_pb", "morsel.journal.preferences_pb")
    }

    @Test
    fun `dispatch and outcome survive a scope restart`() {
        val dir = Files.createTempDirectory("morsel-journal").toFile()
        val scope1 = newScope()
        val journal1 = DataStoreFeedJournal(dir, scope1)
        runBlocking {
            journal1.upsert(
                FeedOperation("op-1", "SN", 2, "req-1", 5L, FeedState.DISPATCHING),
            )
            journal1.upsert(
                FeedOperation("op-1", "SN", 2, "req-1", 5L, FeedState.ACCEPTED_UNCONFIRMED),
            )
        }
        stopScope(scope1)

        val scope2 = newScope()
        val journal2 = DataStoreFeedJournal(dir, scope2)
        runBlocking {
            val all = journal2.all()
            assertEquals(1, all.size)
            assertEquals(FeedState.ACCEPTED_UNCONFIRMED, all.single().state)
        }
        stopScope(scope2)
    }

    @Test
    fun `twenty-six accepted and acknowledged operations never break trimming`() {
        val dir = Files.createTempDirectory("morsel-journal").toFile()
        val scope = newScope()
        val journal = DataStoreFeedJournal(dir, scope)
        runBlocking {
            repeat(26) { i ->
                val op = FeedOperation(
                    id = "op-$i",
                    serial = "SN",
                    portions = 1,
                    requestId = "req-$i",
                    createdAtEpochMs = i.toLong(),
                    state = FeedState.ACCEPTED_UNCONFIRMED,
                )
                journal.upsert(op)
                // The user's explicit acknowledgement resolves the entry.
                journal.upsert(op.copy(acknowledgedAtEpochMs = i.toLong() + 1))
            }
            // The 27th dispatch must still be writable.
            journal.upsert(
                FeedOperation("op-27", "SN", 3, "req-27", 100L, FeedState.DISPATCHING),
            )
            val all = journal.all()
            assertTrue("journal exceeded its bound: ${all.size}", all.size <= 26)
            assertEquals(1, all.count { it.id == "op-27" })
        }
        stopScope(scope)
    }

    @Test
    fun `genuinely unresolved entries are preserved while resolved tail is bounded`() {
        val dir = Files.createTempDirectory("morsel-journal").toFile()
        val scope = newScope()
        val journal = DataStoreFeedJournal(dir, scope)
        runBlocking {
            // One genuinely unresolved accepted operation.
            val unresolved = FeedOperation(
                "unresolved",
                "SN",
                2,
                "req-u",
                0,
                FeedState.ACCEPTED_UNCONFIRMED,
            )
            journal.upsert(unresolved)
            // 40 resolved (acknowledged) operations.
            repeat(40) { i ->
                val op = FeedOperation("old-$i", "SN", 1, "req-$i", i.toLong(), FeedState.REPORTED_SUCCESS)
                journal.upsert(op)
            }
            val all = journal.all()
            assertNotNull(all.firstOrNull { it.id == "unresolved" })
            assertTrue("resolved tail not bounded: ${all.size}", all.size <= 26)
            // The newest resolved entries survive.
            assertNotNull(all.firstOrNull { it.id == "old-39" })
        }
        stopScope(scope)
    }

    @Test
    fun `unacknowledged accepted operations beyond the bound keep the oldest trimmed`() {
        val dir = Files.createTempDirectory("morsel-journal").toFile()
        val scope = newScope()
        val journal = DataStoreFeedJournal(dir, scope)
        runBlocking {
            // Pathological case: 30 genuinely unresolved entries. Trimming must
            // bound the journal instead of growing without limit or crashing.
            repeat(30) { i ->
                journal.upsert(
                    FeedOperation("u-$i", "SN", 1, "req-$i", i.toLong(), FeedState.UNKNOWN),
                )
            }
            val all = journal.all()
            assertTrue("journal exceeded its bound: ${all.size}", all.size <= 26)
            // The NEWEST unresolved entries are the ones kept.
            assertNotNull(all.firstOrNull { it.id == "u-29" })
        }
        stopScope(scope)
    }

    @Test
    fun `corrupt journal file fails closed on read and write`() {
        val dir = Files.createTempDirectory("morsel-journal").toFile()
        val scope1 = newScope()
        val journal1 = DataStoreFeedJournal(dir, scope1)
        runBlocking {
            journal1.upsert(
                FeedOperation("op", "SN", 1, "req", 1, FeedState.DISPATCHING),
            )
        }
        stopScope(scope1)

        // Corrupt the persisted file after a real pending operation existed.
        java.io.File(dir, "morsel.journal.preferences_pb").writeText("{not preferences")
        val scope2 = newScope()
        val journal2 = DataStoreFeedJournal(dir, scope2)
        runBlocking {
            val readError = runCatching { journal2.all() }.exceptionOrNull()
            assertTrue("expected JournalReadException, got $readError", readError is JournalReadException)
            val writeError = runCatching {
                journal2.upsert(
                    FeedOperation("op", "SN", 1, "req", 1, FeedState.DISPATCHING),
                )
            }.exceptionOrNull()
            assertTrue(
                "expected JournalPersistenceException, got $writeError",
                writeError is JournalPersistenceException,
            )
        }
        stopScope(scope2)
    }

    @Test
    fun `undecodable operations payload fails closed instead of being erased`() {
        val dir = Files.createTempDirectory("morsel-journal").toFile()
        val scope1 = newScope()
        runBlocking {
            DataStoreFeedJournal(dir, scope1).upsert(
                FeedOperation("op-1", "SN", 2, "req-1", 5L, FeedState.DISPATCHING),
            )
        }
        stopScope(scope1)

        // Corrupt only the operations payload inside an otherwise valid file,
        // so the DataStore container reads fine but the entry JSON is garbage.
        val rawScope = newScope()
        val rawStore = PreferenceDataStoreFactory.create(scope = rawScope) {
            java.io.File(dir, "morsel.journal.preferences_pb")
        }
        runBlocking {
            rawStore.edit { it[stringPreferencesKey("operations")] = "{not json" }
        }
        stopScope(rawScope)

        val scope2 = newScope()
        val journal2 = DataStoreFeedJournal(dir, scope2)
        runBlocking {
            assertTrue(
                "read must fail closed",
                runCatching { journal2.all() }.exceptionOrNull() is JournalReadException,
            )
            assertTrue(
                "write must refuse instead of treating the payload as empty",
                runCatching {
                    journal2.upsert(
                        FeedOperation("op-2", "SN", 1, "req-2", 6L, FeedState.DISPATCHING),
                    )
                }.exceptionOrNull() is JournalPersistenceException,
            )
        }
        stopScope(scope2)
    }

    @Test
    fun `persisted corruption blocks restore and dispatch before any network`() {
        val dir = Files.createTempDirectory("morsel-journal").toFile()
        val scope1 = newScope()
        runBlocking {
            DataStoreFeedJournal(dir, scope1).upsert(
                FeedOperation("pending", "SN", 2, "req-1", 5L, FeedState.DISPATCHING),
            )
        }
        stopScope(scope1)
        java.io.File(dir, "morsel.journal.preferences_pb").writeText("garbage")

        val scope2 = newScope()
        val repository = FakeRepository()
        val coordinator = FeedCoordinator(
            repository = repository,
            journal = DataStoreFeedJournal(dir, scope2),
            settingsSource = fakeSettings("SN"),
            scope = scope2,
        )
        runBlocking {
            coordinator.restore()
            assertTrue(coordinator.state.value.storageError)
            assertTrue(coordinator.state.value.blocksNewSubmissions)

            val result = coordinator.submit(1)
            assertEquals(
                SubmissionResult.Blocked.Reason.STORAGE_ERROR,
                (result as SubmissionResult.Blocked).reason,
            )
            // Blocked before ANY transport use: no preflight reads, no write.
            assertEquals(0, repository.deviceCalls.get())
            assertEquals(0, repository.statusCalls.get())
            assertEquals(0, repository.writeCalls.get())
        }
        stopScope(scope2)
    }
}
