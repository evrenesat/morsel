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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
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
 * changes never delete it.
 */
class DataStoreFeedJournal(
    context: Context,
    scope: CoroutineScope,
) : FeedJournal {

    private val json = Json { ignoreUnknownKeys = true }

    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        File(context.noBackupFilesDir, "morsel.journal_pb")
    }

    override suspend fun all(): List<FeedOperation> {
        val raw = store.data.first()[KEY_OPERATIONS] ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(JournalEntry.serializer()), raw)
        }.getOrDefault(emptyList()).map { it.toOperation() }
    }

    override suspend fun upsert(operation: FeedOperation) {
        try {
            store.edit { prefs ->
                val raw = prefs[KEY_OPERATIONS]
                val current = raw?.let {
                    runCatching {
                        json.decodeFromString(ListSerializer(JournalEntry.serializer()), it)
                    }.getOrDefault(emptyList())
                } ?: emptyList()
                val updated = current
                    .filterNot { it.id == operation.id }
                    .let { trimmed(it + operation.toEntry()) }
                prefs[KEY_OPERATIONS] =
                    json.encodeToString(ListSerializer(JournalEntry.serializer()), updated)
            }
        } catch (e: Exception) {
            throw JournalPersistenceException(e)
        }
    }

    /** Bounded history: never drop unresolved entries; drop oldest resolved first. */
    private fun trimmed(entries: List<JournalEntry>): List<JournalEntry> {
        if (entries.size <= MAX_ENTRIES) return entries
        val unresolved = entries.filter { FeedState.valueOf(it.state).unresolvedState }
        val resolved = entries.filterNot { FeedState.valueOf(it.state).unresolvedState }
        return unresolved + resolved.takeLast(MAX_ENTRIES - unresolved.size)
    }

    companion object {
        private val KEY_OPERATIONS = stringPreferencesKey("operations")
        private const val MAX_ENTRIES = 25
    }
}
