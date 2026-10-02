package io.evren.morsel

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.evren.morsel.data.CredentialVault
import io.evren.morsel.data.DataStoreFeedJournal
import io.evren.morsel.data.MorselSettings
import io.evren.morsel.data.SettingsStore
import io.evren.morsel.demo.DemoScenario
import io.evren.morsel.domain.FeedOperation
import io.evren.morsel.domain.FeedState
import io.evren.morsel.domain.JournalPersistenceException
import io.evren.morsel.domain.JournalReadException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented storage gates: the real DataStore stores and the AES-GCM
 * Keystore vault on a real device/emulator, in isolated directories.
 */
@RunWith(AndroidJUnit4::class)
class StorageInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val scopes = mutableListOf<CoroutineScope>()

    private fun newScope(): CoroutineScope = CoroutineScope(Job() + Dispatchers.IO).also {
        scopes.add(it)
    }

    /** A DataStore file may only be reopened once the old scope fully joined. */
    private fun stopScope(scope: CoroutineScope) {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        scopes.remove(scope)
    }

    @After
    fun tearDown() {
        scopes.forEach { runBlocking { it.coroutineContext.job.cancelAndJoin() } }
    }

    private fun isolatedDir(name: String): File = File(context.noBackupFilesDir, name).apply {
        deleteRecursively()
        mkdirs()
    }

    @Test
    fun journalPersistsAcrossScopeRestartInNoBackupStorage() {
        val dir = isolatedDir("it-journal")
        val scope1 = newScope()
        val journal1 = DataStoreFeedJournal(dir, scope1)
        runBlocking {
            journal1.upsert(
                FeedOperation("it-op", "SN", 2, "it-req", 7L, FeedState.DISPATCHING),
            )
        }
        stopScope(scope1)

        // Simulated process restart: a brand-new instance over the same file.
        val scope2 = newScope()
        val journal2 = DataStoreFeedJournal(dir, scope2)
        runBlocking {
            val all = journal2.all()
            assertEquals(1, all.size)
            assertEquals("it-op", all.single().id)
            assertEquals(FeedState.DISPATCHING, all.single().state)
            assertEquals("SN", all.single().serial)
        }
        // The journal really lives under noBackupFilesDir.
        assertTrue(File(dir, "morsel.journal.preferences_pb").exists())
        assertTrue(dir.absolutePath.startsWith(context.noBackupFilesDir.absolutePath))
        stopScope(scope2)
    }

    @Test
    fun corruptJournalFailsClosedOnDevice() {
        val dir = isolatedDir("it-journal-corrupt")
        val scope1 = newScope()
        runBlocking {
            DataStoreFeedJournal(dir, scope1).upsert(
                FeedOperation("it-c", "SN", 1, "it-req-c", 1L, FeedState.DISPATCHING),
            )
        }
        stopScope(scope1)

        // Corrupt the persisted file after a real pending operation existed.
        File(dir, "morsel.journal.preferences_pb").writeText("{corrupt")
        val scope2 = newScope()
        val journal = DataStoreFeedJournal(dir, scope2)
        runBlocking {
            assertTrue(
                "read must fail closed on device",
                runCatching { journal.all() }.exceptionOrNull() is JournalReadException,
            )
            assertTrue(
                "write must refuse instead of resetting",
                runCatching {
                    journal.upsert(
                        FeedOperation("it-c2", "SN", 1, "it-req-c2", 2L, FeedState.DISPATCHING),
                    )
                }.exceptionOrNull() is JournalPersistenceException,
            )
        }
        stopScope(scope2)
    }

    @Test
    fun settingsRoundTripWithRealStore() {
        val dir = isolatedDir("it-settings")
        val scope1 = newScope()
        val store1 = SettingsStore(dir, scope1)
        runBlocking {
            store1.setPortionCap(5)
            store1.setCatName("Mies")
            store1.setDemoScenario(DemoScenario.MISMATCH)
        }
        stopScope(scope1)

        val scope2 = newScope()
        val store2 = SettingsStore(dir, scope2)
        runBlocking {
            val settings = store2.settings.first()
            assertEquals(5, settings.portionCap)
            assertEquals("Mies", settings.catName)
            assertEquals(DemoScenario.MISMATCH.name, settings.demoScenario)
        }
        stopScope(scope2)
    }

    @Test
    fun vaultRoundTripAndCorruptionHandling() {
        val vault = CredentialVault(context)
        val scope = newScope()
        runBlocking {
            vault.save("cat@example.com", "digest-abc")
            vault.saveToken("token-123")
            val read = vault.read()
            assertEquals("cat@example.com", read?.email)
            assertEquals("digest-abc", read?.passwordDigest)
            assertEquals("token-123", read?.token)

            // The ciphertext file exists in no-backup storage, not plaintext.
            val file = File(context.noBackupFilesDir, "morsel.vault.bin")
            assertTrue(file.exists())
            val bytes = file.readBytes()
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("cat@example.com"))
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("digest-abc"))

            // Corrupt data reads as no credentials (sign-in flow), never a crash.
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            assertNull(vault.read())

            // Clear removes everything.
            vault.clear()
            assertNull(vault.read())
        }
        stopScope(scope)
    }
}
