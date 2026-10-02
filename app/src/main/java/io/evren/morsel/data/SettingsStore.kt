package io.evren.morsel.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.evren.morsel.demo.DemoScenario
import io.evren.morsel.domain.FeedSettings
import io.evren.morsel.domain.FeedSettingsSource
import io.evren.morsel.domain.FeedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

data class MorselSettings(
    val boundSerial: String? = null,
    val catName: String? = null,
    val portionCap: Int = FeedState.ABSOLUTE_MAX_PORTIONS,
    val hapticsEnabled: Boolean = true,
    val reduceMotion: Boolean = false,
    val demoMode: Boolean = false,
    val demoScenario: String = DemoScenario.SUCCESS_CORRELATED.name,
    val onboardingComplete: Boolean = false,
)

/** Contract for settings the UI and graph program against. */
interface MorselSettingsStore : io.evren.morsel.domain.FeedSettingsSource {
    val settings: Flow<MorselSettings>

    suspend fun setBoundSerial(serial: String?)

    suspend fun setCatName(name: String?)

    suspend fun setPortionCap(cap: Int)

    suspend fun setHapticsEnabled(enabled: Boolean)

    suspend fun setReduceMotion(enabled: Boolean)

    suspend fun setDemoMode(enabled: Boolean)

    suspend fun setDemoScenario(scenario: DemoScenario)

    suspend fun setOnboardingComplete(done: Boolean)
}

/**
 * Local preferences in no-backup storage. Bound serial, cat name, lower-only
 * portion cap, haptics, reduce motion, and the clearly-labelled demo settings.
 * Credentials never live here; they live encrypted in CredentialVault, and the
 * journal never lives here either.
 */
class SettingsStore(
    context: Context,
    scope: CoroutineScope,
) : MorselSettingsStore {

    private val store: DataStore<androidx.datastore.preferences.core.Preferences> =
        PreferenceDataStoreFactory.create(
            scope = scope,
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ) {
            File(context.noBackupFilesDir, "morsel.settings.preferences_pb")
        }

    override val settings: Flow<MorselSettings> = store.data.map { prefs ->
        MorselSettings(
            boundSerial = prefs[KEY_SERIAL],
            catName = prefs[KEY_CAT_NAME],
            portionCap = (prefs[KEY_CAP] ?: FeedState.ABSOLUTE_MAX_PORTIONS)
                .coerceIn(FeedState.MIN_PORTIONS, FeedState.ABSOLUTE_MAX_PORTIONS),
            hapticsEnabled = prefs[KEY_HAPTICS] ?: true,
            reduceMotion = prefs[KEY_REDUCE_MOTION] ?: false,
            demoMode = prefs[KEY_DEMO_MODE] ?: false,
            demoScenario = prefs[KEY_DEMO_SCENARIO] ?: DemoScenario.SUCCESS_CORRELATED.name,
            onboardingComplete = prefs[KEY_ONBOARDED] ?: false,
        )
    }

    override suspend fun snapshot(): FeedSettings {
        val s = settings.first()
        return FeedSettings(boundSerial = s.boundSerial, portionCap = s.portionCap)
    }

    override suspend fun setBoundSerial(serial: String?) {
        store.edit {
            if (serial == null) it.remove(KEY_SERIAL) else it[KEY_SERIAL] = serial
        }
    }

    override suspend fun setCatName(name: String?) {
        store.edit {
            if (name.isNullOrBlank()) it.remove(KEY_CAT_NAME) else it[KEY_CAT_NAME] = name.trim()
        }
    }

    /** The cap can only lower the hardware maximum, never exceed it. */
    override suspend fun setPortionCap(cap: Int) {
        store.edit {
            it[KEY_CAP] = cap.coerceIn(FeedState.MIN_PORTIONS, FeedState.ABSOLUTE_MAX_PORTIONS)
        }
    }

    override suspend fun setHapticsEnabled(enabled: Boolean) {
        store.edit { it[KEY_HAPTICS] = enabled }
    }

    override suspend fun setReduceMotion(enabled: Boolean) {
        store.edit { it[KEY_REDUCE_MOTION] = enabled }
    }

    override suspend fun setDemoMode(enabled: Boolean) {
        store.edit { it[KEY_DEMO_MODE] = enabled }
    }

    override suspend fun setDemoScenario(scenario: DemoScenario) {
        store.edit { it[KEY_DEMO_SCENARIO] = scenario.name }
    }

    override suspend fun setOnboardingComplete(done: Boolean) {
        store.edit { it[KEY_ONBOARDED] = done }
    }

    private companion object {
        val KEY_SERIAL = stringPreferencesKey("bound_serial")
        val KEY_CAT_NAME = stringPreferencesKey("cat_name")
        val KEY_CAP = intPreferencesKey("portion_cap")
        val KEY_HAPTICS = booleanPreferencesKey("haptics_enabled")
        val KEY_REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val KEY_DEMO_MODE = booleanPreferencesKey("demo_mode")
        val KEY_DEMO_SCENARIO = stringPreferencesKey("demo_scenario")
        val KEY_ONBOARDED = booleanPreferencesKey("onboarding_complete")
    }
}
