package tally.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.dataStore

    /**
     * Debug toggle: record notifications from every package, not just watched ones.
     * Use it for the first day to confirm the real bank and wallet package names.
     * A [StateFlow] so the listener can read it synchronously.
     */
    val logAllPackages: StateFlow<Boolean> = store.data
        .map { it[LOG_ALL_PACKAGES] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun setLogAllPackages(enabled: Boolean) {
        store.edit { it[LOG_ALL_PACKAGES] = enabled }
    }

    private companion object {
        val LOG_ALL_PACKAGES = booleanPreferencesKey("log_all_packages")
    }
}
