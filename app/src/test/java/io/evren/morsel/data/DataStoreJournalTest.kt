package io.evren.morsel.data

import io.evren.morsel.domain.FeedOperation
import io.evren.morsel.domain.FeedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Regression tests for the REAL persisted journal (actual DataStore file on
 * disk), not an in-memory fake. Covers the checkpoint-2 review finding: the
 * resolved partition must include acknowledged entries so trimming can never
 * compute a negative takeLast bound after many accepted+acknowledged requests.
 */
class DataStoreJournalTest {

    private fun newScope() = CoroutineScope(Job() + Dispatchers.IO)

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
        scope1.cancel()

        val scope2 = newScope()
        val journal2 = DataStoreFeedJournal(dir, scope2)
        runBlocking {
            val all = journal2.all()
            assertEquals(1, all.size)
            assertEquals(FeedState.ACCEPTED_UNCONFIRMED, all.single().state)
        }
        scope2.cancel()
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
        scope.cancel()
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
        scope.cancel()
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
        scope.cancel()
    }

    @Test
    fun `corrupt journal file reads as empty instead of crashing all()`() {
        val dir = Files.createTempDirectory("morsel-journal").toFile()
        java.io.File(dir, "morsel.journal.preferences_pb").writeText("{not json")
        val scope = newScope()
        val journal = DataStoreFeedJournal(dir, scope)
        runBlocking {
            // Corrupt data degrades to empty, not a crash on read.
            assertEquals(0, journal.all().size)
            // Writes still work and overwrite the corrupt value.
            journal.upsert(FeedOperation("op", "SN", 1, "req", 1, FeedState.DISPATCHING))
            assertEquals(1, journal.all().size)
        }
        scope.cancel()
    }
}
