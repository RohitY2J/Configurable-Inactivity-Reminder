package com.example.inactivityreminder.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Extension property that creates a single DataStore<Preferences> instance scoped to the app.
 * The file is stored at: data/data/<package>/files/datastore/settings.preferences_pb
 * Using a top-level extension ensures only one DataStore instance exists per process
 * (multiple instances for the same file would cause corruption).
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * SettingsRepository — Single source of truth for all user-configurable preferences.
 *
 * Both the UI (Compose) and the foreground service observe the same Flows from this class.
 * This avoids state duplication between layers. DataStore is used instead of SharedPreferences
 * because it provides:
 *   - Coroutine-based async API (no blocking disk I/O on main thread)
 *   - Flow-based observation (reactive updates without polling)
 *   - Type safety via typed keys
 *
 * @param context Application or Activity context used to access the DataStore instance.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        /** DataStore key for the user-configured inactivity interval (in minutes). */
        private val KEY_INTERVAL = intPreferencesKey("inactivity_interval_minutes")

        /** DataStore key for whether monitoring is currently enabled (service running). */
        private val KEY_MONITORING = booleanPreferencesKey("is_monitoring_enabled")

        /** Default interval used when the user hasn't configured one yet. */
        const val DEFAULT_INTERVAL_MINUTES = 30
    }

    /**
     * Emits the current inactivity interval in minutes.
     * The UI observes this via collectAsState() and the service reads it via .first()
     * each time it checks for inactivity.
     * Defaults to 30 minutes if no value has been persisted.
     */
    val inactivityIntervalMinutes: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_INTERVAL] ?: DEFAULT_INTERVAL_MINUTES
    }

    /**
     * Emits whether the monitoring service should be running.
     * The UI uses this to show the correct start/stop state on recomposition.
     * Defaults to false (monitoring off) on fresh install.
     */
    val isMonitoringEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_MONITORING] ?: false
    }

    /**
     * Persists a new inactivity interval.
     * Clamped to 1–60 minutes (1 min allowed for testing; production would use 10–60).
     * This is a suspend function because DataStore writes are asynchronous.
     */
    suspend fun setInactivityInterval(minutes: Int) {
        context.dataStore.edit { it[KEY_INTERVAL] = minutes.coerceIn(1, 60) }
    }

    /**
     * Persists whether monitoring is enabled.
     * Called by the UI when the user taps Start/Stop, ensuring the state survives
     * process death and is available on next app launch.
     */
    suspend fun setMonitoringEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_MONITORING] = enabled }
    }
}
