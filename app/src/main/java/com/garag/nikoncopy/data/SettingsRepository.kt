package com.garag.nikoncopy.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "nikoncopy_settings")

private object Keys {
    val DEVICE_PROFILES: Preferences.Key<String> = stringPreferencesKey("camera_profiles_json")
    val ACTIVE_PROFILE_ID: Preferences.Key<String> = stringPreferencesKey("active_profile_id")

    // --- legacy global keys, kept only for one-shot migration into a default profile ---
    val LEGACY_DESTINATION_URI: Preferences.Key<String> = stringPreferencesKey("destination_uri")
    val LEGACY_LAST_COPY_STARTED_AT: Preferences.Key<Long> = longPreferencesKey("last_copy_started_at")
    val LEGACY_LAST_COPY_COMPLETED_AT: Preferences.Key<Long> = longPreferencesKey("last_copy_completed_at")
    val LEGACY_LAST_COPY_FILES: Preferences.Key<Long> = longPreferencesKey("last_copy_files")
    val LEGACY_LAST_FIX_COMPLETED_AT: Preferences.Key<Long> = longPreferencesKey("last_fix_completed_at")
    val LEGACY_MIGRATED: Preferences.Key<Long> = longPreferencesKey("legacy_migrated_at")
}

/**
 * Pure display data, derived from the active [DeviceProfile]. The "incremental
 * cutoff" is no longer derived from this — see [ManifestStore] for the source of
 * truth on what was already copied successfully.
 */
data class CacheRecord(
    val lastCopyStartedAt: Long,
    val lastCopyCompletedAt: Long,
    val lastCopyFiles: Long,
    val lastFixCompletedAt: Long,
) {
    /** True when the most recent copy hasn't been followed by a fix-dates run. */
    val hasUnfixedBatch: Boolean
        get() = lastCopyCompletedAt > 0 && lastCopyCompletedAt > lastFixCompletedAt

    companion object {
        val Empty = CacheRecord(0L, 0L, 0L, 0L)
        fun of(profile: DeviceProfile?): CacheRecord = if (profile == null) Empty else CacheRecord(
            lastCopyStartedAt = profile.cacheStartedAt,
            lastCopyCompletedAt = profile.cacheCompletedAt,
            lastCopyFiles = profile.cacheFiles,
            lastFixCompletedAt = profile.lastFixCompletedAt,
        )
    }
}

class SettingsRepository(private val context: Context) {

    val deviceProfiles: Flow<List<DeviceProfile>> = context.dataStore.data.map {
        CameraProfileCodec.decode(it[Keys.DEVICE_PROFILES])
    }

    /** Backward-compat alias used by older call sites. */
    val cameraProfiles: Flow<List<DeviceProfile>> get() = deviceProfiles

    val activeProfileId: Flow<String?> = context.dataStore.data.map {
        it[Keys.ACTIVE_PROFILE_ID]?.takeIf(String::isNotBlank)
    }

    /** The currently selected device profile, resolved from id. May be null. */
    val activeProfile: Flow<DeviceProfile?> =
        deviceProfiles.combine(activeProfileId) { profiles, id ->
            if (id == null) profiles.firstOrNull()
            else profiles.firstOrNull { it.id == id } ?: profiles.firstOrNull()
        }

    /** Destination URI, derived from active profile so existing UI keeps working. */
    val destinationUri: Flow<String?> = activeProfile.map { it?.destinationUri }

    /** Cache record, derived from active profile. */
    val cacheRecord: Flow<CacheRecord> = activeProfile.map { CacheRecord.of(it) }

    suspend fun setActiveProfile(profileId: String?) {
        context.dataStore.edit { prefs ->
            if (profileId == null) prefs.remove(Keys.ACTIVE_PROFILE_ID)
            else prefs[Keys.ACTIVE_PROFILE_ID] = profileId
        }
    }

    suspend fun cameraProfilesSnapshot(): List<DeviceProfile> {
        return CameraProfileCodec.decode(context.dataStore.data.first()[Keys.DEVICE_PROFILES])
    }

    suspend fun activeProfileSnapshot(): DeviceProfile? {
        val data = context.dataStore.data.first()
        val profiles = CameraProfileCodec.decode(data[Keys.DEVICE_PROFILES])
        val id = data[Keys.ACTIVE_PROFILE_ID]?.takeIf(String::isNotBlank)
        return if (id != null) profiles.firstOrNull { it.id == id } ?: profiles.firstOrNull()
        else profiles.firstOrNull()
    }

    suspend fun upsertCameraProfile(profile: DeviceProfile) = upsertDeviceProfile(profile)

    suspend fun upsertDeviceProfile(profile: DeviceProfile) {
        context.dataStore.edit { prefs ->
            val current = CameraProfileCodec.decode(prefs[Keys.DEVICE_PROFILES])
            val next = current
                .filterNot { it.id == profile.id || it.deviceKey == profile.deviceKey }
                .plus(profile)
                .sortedBy { it.deviceName.lowercase() }
            prefs[Keys.DEVICE_PROFILES] = CameraProfileCodec.encode(next)
            // First profile becomes active automatically so the UI has something to show.
            if (prefs[Keys.ACTIVE_PROFILE_ID].isNullOrBlank()) {
                prefs[Keys.ACTIVE_PROFILE_ID] = profile.id
            }
        }
    }

    suspend fun updateCameraProfile(profileId: String, transform: (DeviceProfile) -> DeviceProfile) =
        updateDeviceProfile(profileId, transform)

    suspend fun updateDeviceProfile(profileId: String, transform: (DeviceProfile) -> DeviceProfile) {
        context.dataStore.edit { prefs ->
            val current = CameraProfileCodec.decode(prefs[Keys.DEVICE_PROFILES])
            val next = current.map { if (it.id == profileId) transform(it) else it }
            prefs[Keys.DEVICE_PROFILES] = CameraProfileCodec.encode(next)
        }
    }

    suspend fun deleteCameraProfile(profileId: String) = deleteDeviceProfile(profileId)

    suspend fun deleteDeviceProfile(profileId: String) {
        context.dataStore.edit { prefs ->
            val remaining = CameraProfileCodec.decode(prefs[Keys.DEVICE_PROFILES])
                .filterNot { it.id == profileId }
            prefs[Keys.DEVICE_PROFILES] = CameraProfileCodec.encode(remaining)
            // If we just removed the active one, fall back to first remaining (or clear).
            if (prefs[Keys.ACTIVE_PROFILE_ID] == profileId) {
                val next = remaining.firstOrNull()?.id
                if (next != null) prefs[Keys.ACTIVE_PROFILE_ID] = next
                else prefs.remove(Keys.ACTIVE_PROFILE_ID)
            }
        }
    }

    /** Set the destination on the currently active profile. */
    suspend fun setDestinationUri(uri: String) {
        val active = activeProfileSnapshot()
        if (active != null) {
            updateDeviceProfile(active.id) { it.copy(destinationUri = uri, updatedAt = System.currentTimeMillis()) }
        } else {
            // No profile yet — stash on the legacy key so migration picks it up if/when
            // a profile is added.
            context.dataStore.edit { it[Keys.LEGACY_DESTINATION_URI] = uri }
        }
    }

    suspend fun recordCopyStarted(profileId: String, epochMillis: Long) {
        updateDeviceProfile(profileId) { it.copy(cacheStartedAt = epochMillis) }
    }

    suspend fun recordCopyCompleted(profileId: String, epochMillis: Long, filesCopied: Long) {
        updateDeviceProfile(profileId) {
            it.copy(cacheCompletedAt = epochMillis, cacheFiles = filesCopied)
        }
    }

    suspend fun recordFixCompleted(profileId: String, epochMillis: Long) {
        updateDeviceProfile(profileId) { it.copy(lastFixCompletedAt = epochMillis) }
    }

    /** Reset cache fields on the given profile. */
    suspend fun clearCache(profileId: String?) {
        val target = profileId ?: activeProfileSnapshot()?.id ?: return
        updateDeviceProfile(target) {
            it.copy(
                cacheStartedAt = 0L,
                cacheCompletedAt = 0L,
                cacheFiles = 0L,
                lastFixCompletedAt = 0L,
            )
        }
    }

    /**
     * One-shot migration: if there's a legacy global destination URI / cache and no
     * device profile to attach them to, create a placeholder default profile so the
     * user doesn't lose their pre-multi-device state. Idempotent.
     */
    suspend fun migrateLegacyGlobalsIfNeeded() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.LEGACY_MIGRATED] != null && prefs[Keys.LEGACY_MIGRATED]!! > 0L) return@edit
            val legacyDest = prefs[Keys.LEGACY_DESTINATION_URI]?.takeIf(String::isNotBlank)
            val legacyStarted = prefs[Keys.LEGACY_LAST_COPY_STARTED_AT] ?: 0L
            val legacyCompleted = prefs[Keys.LEGACY_LAST_COPY_COMPLETED_AT] ?: 0L
            val legacyFiles = prefs[Keys.LEGACY_LAST_COPY_FILES] ?: 0L
            val legacyFix = prefs[Keys.LEGACY_LAST_FIX_COMPLETED_AT] ?: 0L
            val existingProfiles = CameraProfileCodec.decode(prefs[Keys.DEVICE_PROFILES])

            // Mark migration as done either way so we don't re-check on every launch.
            prefs[Keys.LEGACY_MIGRATED] = System.currentTimeMillis()

            if (legacyDest == null && legacyStarted == 0L && legacyCompleted == 0L && legacyFix == 0L) return@edit
            if (existingProfiles.isNotEmpty()) {
                // Apply legacy state to the first existing profile if it has no own data yet.
                val first = existingProfiles.first()
                val mergedDest = first.destinationUri ?: legacyDest
                val mergedStarted = if (first.cacheStartedAt == 0L) legacyStarted else first.cacheStartedAt
                val mergedCompleted = if (first.cacheCompletedAt == 0L) legacyCompleted else first.cacheCompletedAt
                val mergedFiles = if (first.cacheFiles == 0L) legacyFiles else first.cacheFiles
                val mergedFix = if (first.lastFixCompletedAt == 0L) legacyFix else first.lastFixCompletedAt
                val updated = first.copy(
                    destinationUri = mergedDest,
                    cacheStartedAt = mergedStarted,
                    cacheCompletedAt = mergedCompleted,
                    cacheFiles = mergedFiles,
                    lastFixCompletedAt = mergedFix,
                    updatedAt = System.currentTimeMillis(),
                )
                val next = listOf(updated) + existingProfiles.drop(1)
                prefs[Keys.DEVICE_PROFILES] = CameraProfileCodec.encode(next)
                return@edit
            }
            // No profile yet — create a placeholder so user keeps their destination.
            val now = System.currentTimeMillis()
            val placeholder = DeviceProfile(
                id = "default",
                deviceKey = "legacy:default",
                deviceName = "默认设备",
                kind = DeviceKind.PTP,
                destinationUri = legacyDest,
                cacheStartedAt = legacyStarted,
                cacheCompletedAt = legacyCompleted,
                cacheFiles = legacyFiles,
                lastFixCompletedAt = legacyFix,
                updatedAt = now,
            )
            prefs[Keys.DEVICE_PROFILES] = CameraProfileCodec.encode(listOf(placeholder))
            if (prefs[Keys.ACTIVE_PROFILE_ID].isNullOrBlank()) {
                prefs[Keys.ACTIVE_PROFILE_ID] = placeholder.id
            }
        }
    }
}
