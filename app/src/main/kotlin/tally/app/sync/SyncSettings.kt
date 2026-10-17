package tally.app.sync

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

private val Context.syncStore by preferencesDataStore(name = "sync")

/** Outcome of the last sync attempt, for the Sync screen. */
data class SyncStatus(
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    /** Null when the last attempt worked. */
    val lastError: String? = null,
    val lastSummary: String? = null,
)

/**
 * Server settings, this phone's device id, and where sync left off. The client secret sits
 * in app-private storage (backups are off for Tally), like the database itself.
 */
class SyncSettings(context: Context, scope: CoroutineScope) : SyncCheckpointStore {
    private val store = context.applicationContext.syncStore

    val server: StateFlow<ServerSettings> = store.data
        .map { it.server() }
        .stateIn(scope, SharingStarted.Eagerly, ServerSettings(DEFAULT_URL, "", ""))

    val status: StateFlow<SyncStatus> = store.data
        .map {
            SyncStatus(
                lastSuccessAt = it[LAST_SUCCESS_AT],
                lastAttemptAt = it[LAST_ATTEMPT_AT],
                lastError = it[LAST_ERROR],
                lastSummary = it[LAST_SUMMARY],
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, SyncStatus())

    suspend fun currentServer(): ServerSettings = store.data.first().server()

    /** A different server is a different history: start over (push everything, pull from 0). */
    suspend fun setServer(settings: ServerSettings) {
        store.edit {
            val old = it.server()
            it[SERVER_URL] = settings.url.trim()
            it[CLIENT_ID] = settings.clientId.trim()
            it[CLIENT_SECRET] = settings.clientSecret.trim()
            if (old.url.trimEnd('/') != settings.url.trim().trimEnd('/')) {
                it.remove(CURSOR)
                it.remove(WATERMARK)
                it.remove(LAST_SUCCESS_AT)
            }
        }
    }

    suspend fun deviceId(): String {
        store.data.first()[DEVICE_ID]?.let { return it }
        var id = ""
        store.edit { id = it[DEVICE_ID] ?: UUID.randomUUID().toString().also { new -> it[DEVICE_ID] = new } }
        return id
    }

    suspend fun recordSuccess(at: Long, summary: String) = store.edit {
        it[LAST_ATTEMPT_AT] = at
        it[LAST_SUCCESS_AT] = at
        it[LAST_SUMMARY] = summary
        it.remove(LAST_ERROR)
    }

    suspend fun recordFailure(at: Long, error: String) = store.edit {
        it[LAST_ATTEMPT_AT] = at
        it[LAST_ERROR] = error
    }

    override suspend fun load(): SyncCheckpoint {
        val prefs = store.data.first()
        return SyncCheckpoint(cursor = prefs[CURSOR] ?: 0, watermark = prefs[WATERMARK] ?: -1)
    }

    override suspend fun save(checkpoint: SyncCheckpoint) {
        store.edit {
            it[CURSOR] = checkpoint.cursor
            it[WATERMARK] = checkpoint.watermark
        }
    }

    private fun Preferences.server() = ServerSettings(
        url = this[SERVER_URL] ?: DEFAULT_URL,
        clientId = this[CLIENT_ID].orEmpty(),
        clientSecret = this[CLIENT_SECRET].orEmpty(),
    )

    companion object {
        const val DEFAULT_URL = "https://tally.ems-place.com"

        private val SERVER_URL = stringPreferencesKey("server_url")
        private val CLIENT_ID = stringPreferencesKey("access_client_id")
        private val CLIENT_SECRET = stringPreferencesKey("access_client_secret")
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val CURSOR = longPreferencesKey("cursor")
        private val WATERMARK = longPreferencesKey("watermark")
        private val LAST_SUCCESS_AT = longPreferencesKey("last_success_at")
        private val LAST_ATTEMPT_AT = longPreferencesKey("last_attempt_at")
        private val LAST_ERROR = stringPreferencesKey("last_error")
        private val LAST_SUMMARY = stringPreferencesKey("last_summary")
    }
}
