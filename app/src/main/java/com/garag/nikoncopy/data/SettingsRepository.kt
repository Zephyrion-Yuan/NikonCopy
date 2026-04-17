package com.garag.nikoncopy.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "nikoncopy_settings")

private object Keys {
    val DESTINATION_URI: Preferences.Key<String> = stringPreferencesKey("destination_uri")
    val SOURCE_URI: Preferences.Key<String> = stringPreferencesKey("source_uri")
    val LAST_COPY_STARTED_AT: Preferences.Key<Long> = longPreferencesKey("last_copy_started_at")
    val LAST_COPY_COMPLETED_AT: Preferences.Key<Long> = longPreferencesKey("last_copy_completed_at")
    val LAST_COPY_FILES: Preferences.Key<Long> = longPreferencesKey("last_copy_files")
}

/**
 * Pure display data. The "incremental cutoff" is no longer derived from this — see
 * [com.garag.nikoncopy.data.ManifestStore] for the source of truth on what was
 * already copied successfully.
 */
data class CacheRecord(
    val lastCopyStartedAt: Long,
    val lastCopyCompletedAt: Long,
    val lastCopyFiles: Long,
)

class SettingsRepository(private val context: Context) {

    val destinationUri: Flow<String?> = context.dataStore.data.map { it[Keys.DESTINATION_URI] }
    val sourceUri: Flow<String?> = context.dataStore.data.map { it[Keys.SOURCE_URI] }

    val cacheRecord: Flow<CacheRecord> = context.dataStore.data.map { prefs ->
        CacheRecord(
            lastCopyStartedAt = prefs[Keys.LAST_COPY_STARTED_AT] ?: 0L,
            lastCopyCompletedAt = prefs[Keys.LAST_COPY_COMPLETED_AT] ?: 0L,
            lastCopyFiles = prefs[Keys.LAST_COPY_FILES] ?: 0L,
        )
    }

    suspend fun setDestinationUri(uri: String) {
        context.dataStore.edit { it[Keys.DESTINATION_URI] = uri }
    }

    suspend fun setSourceUri(uri: String) {
        context.dataStore.edit { it[Keys.SOURCE_URI] = uri }
    }

    suspend fun clearSourceUri() {
        context.dataStore.edit { it.remove(Keys.SOURCE_URI) }
    }

    suspend fun recordCopyStarted(epochMillis: Long) {
        context.dataStore.edit { it[Keys.LAST_COPY_STARTED_AT] = epochMillis }
    }

    suspend fun recordCopyCompleted(epochMillis: Long, filesCopied: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_COPY_COMPLETED_AT] = epochMillis
            prefs[Keys.LAST_COPY_FILES] = filesCopied
        }
    }

    suspend fun clearCache() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.LAST_COPY_STARTED_AT)
            prefs.remove(Keys.LAST_COPY_COMPLETED_AT)
            prefs.remove(Keys.LAST_COPY_FILES)
        }
    }
}
