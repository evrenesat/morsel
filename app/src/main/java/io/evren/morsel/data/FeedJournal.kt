package io.evren.morsel.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.evren.morsel.domain.FeedJournal
import io.evren.morsel.domain.FeedOperation
import io.evren.morsel.domain.FeedState
import io.evren.morsel.domain.JournalPersistenceException
import io.evren.morsel.domain.JournalReadException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Kotlinx-serialization mirror of [FeedOperation] for journal persistence. */
@Serializable
internal data class JournalEntry(
    val id: String,
    val serial: String,
    val portions: Int,
    val requestId: String,
    val createdAtEpochMs: Long,
    val state: String,
    val baselineKnown: Boolean,
    val authExpiredDuringWrite: Boolean,
    val acknowledgedAtEpochMs: Long?,
)

private fun FeedOperation.toEntry() = JournalEntry(
    id = id,
    serial = serial,
    portions = portions,
    requestId = requestId,
    createdAtEpochMs = createdAtEpochMs,
    state = state.name,
    baselineKnown = baselineKnown,
    authExpiredDuringWrite = authExpiredDuringWrite,
    acknowledgedAtEpochMs = acknowledgedAtEpochMs,
)

private fun JournalEntry.toOperation(): FeedOperation = FeedOperation(
    id = id,
    serial = serial,
    portions = portions,
    requestId = requestId,
    createdAtEpochMs = createdAtEpochMs,
    state = FeedState.valueOf(state),
    baselineKnown = baselineKnown,
    authExpiredDuringWrite = authExpiredDuringWrite,
    acknowledgedAtEpochMs = acknowledgedAtEpochMs,
)

/**
 * DataStore-backed journal living in noBackupFilesDir. A dispatch and its
 * outcome are each persisted in one atomic edit; logout, rebinding and settings
 * changes never delete it. Fail-closed: an unreadable or undecodable journal
 * throws on read and refuses writes — it is never silently reset to empty.
 */
class DataStoreFeedJournal private constructor(
    fileProvider: () -> File,
    scope: CoroutineScope,
) : FeedJournal {

    private val json = Json { ignoreUnknownKeys = true }

    // No ReplaceFileCorruptionHandler: a corrupt file must surface as a read
    // error that blocks sending, never be silently reset to empty state.
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = fileProvider,
    )

    /** Device path: the journal lives in no-backup storage. */
    constructor(context: Context, scope: CoroutineScope) : this(
        { File(context.noBackupFilesDir, JOURNAL_FILE) },
        scope,
    )

    /** Directory-injected path for JVM tests of the real persistence code. */
    internal constructor(directory: File, scope: CoroutineScope) : this(
        { File(directory, JOURNAL_FILE) },
        scope,
    )

    private companion object {
        private const val JOURNAL_FILE = "morsel.journal.preferences_pb"
        private val KEY_OPERATIONS = stringPreferencesKey("operations")
        private const val MAX_ENTRIES = 25
    }

    override suspend fun all(): List<FeedOperation> {
        val raw = try {
            store.data.first()[KEY_OPERATIONS]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unknown prior operation state must block sending; it must never
            // be replaced with an empty journal.
            throw JournalReadException(e)
        }
        raw ?: return emptyList()
        return try {
            decode(raw).map { it.toOperation() }
        } catch (e: SerializationException) {
            throw JournalReadException(e)
        }
    }

    override suspend fun upsert(operation: FeedOperation) {
        try {
            store.edit { prefs ->
                val raw = prefs[KEY_OPERATIONS]
                // An undecodable prior payload fails the write instead of being
                // treated as empty, which would silently erase history.
                val current = raw?.let { decode(it) } ?: emptyList()
                val updated = current
                    .filterNot { it.id == operation.id }
                    .let { trimmed(it + operation.toEntry()) }
                prefs[KEY_OPERATIONS] =
                    json.encodeToString(ListSerializer(JournalEntry.serializer()), updated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw JournalPersistenceException(e)
        }
    }

    private fun decode(raw: String): List<JournalEntry> = json.decodeFromString(ListSerializer(JournalEntry.serializer()), raw)

    /**
     * Bounded history: genuinely unresolved entries (unresolved state AND not
     * acknowledged) are always kept; the resolved tail is bounded. Acknowledged
     * entries count as resolved — an unbounded unresolved partition would
     * eventually break the takeLast bound below.
     */
    private fun trimmed(entries: List<JournalEntry>): List<JournalEntry> {
        if (entries.size <= MAX_ENTRIES) return entries
        val (unresolved, resolved) = entries.partition { it.genuinelyUnresolved() }
        val keepResolved = if (unresolved.size >= MAX_ENTRIES) {
            emptyList()
        } else {
            resolved.takeLast(MAX_ENTRIES - unresolved.size)
        }
        // Never grow beyond the bound; unresolved entries are bounded by
        // dropping the OLDEST beyond the cap only if they alone exceed it.
        val boundedUnresolved =
            if (unresolved.size > MAX_ENTRIES) unresolved.takeLast(MAX_ENTRIES) else unresolved
        return boundedUnresolved + keepResolved
    }

    private fun JournalEntry.genuinelyUnresolved(): Boolean = FeedState.valueOf(state).unresolvedState && acknowledgedAtEpochMs == null
}
