package com.garag.nikoncopy.data

import org.json.JSONArray
import org.json.JSONObject

/** Kind of source device a profile represents. */
enum class DeviceKind { PTP, MSC }

/**
 * A single named device the user copies from. Owns its own destination,
 * directory/extension filters, cache record, and (file-based) success manifest.
 *
 * One DeviceProfile per physical device the user wants to manage:
 *   - PTP: Nikon Z f, Sony A7, etc. — opened via custom PTP client.
 *   - MSC: SD card reader, USB stick, anything Android mounts as removable
 *     storage. Source is a SAF tree URI saved in [sourceTreeUri].
 */
data class DeviceProfile(
    val id: String,
    val deviceKey: String,
    val deviceName: String,
    val kind: DeviceKind = DeviceKind.PTP,
    val destinationUri: String? = null,
    val sourceTreeUri: String? = null,
    val selectedDirectories: Set<String> = emptySet(),
    val selectedExtensions: Set<String> = emptySet(),
    val detectedDirectories: Set<String> = emptySet(),
    val detectedExtensions: Set<String> = emptySet(),
    /**
     * Map from detected directory path → most-recent file mtime (millis). Used
     * by the settings UI to sort the source-subdir checkbox list newest-first
     * so the user's latest shoot is at the top. Map keys are always a subset
     * of [detectedDirectories]; missing entries default to 0 (sort to bottom).
     */
    val detectedDirectoryTimes: Map<String, Long> = emptyMap(),
    val cacheStartedAt: Long = 0L,
    val cacheCompletedAt: Long = 0L,
    val cacheFiles: Long = 0L,
    val lastFixCompletedAt: Long = 0L,
    val updatedAt: Long = 0L,
    /**
     * Preserve the source's directory hierarchy under a per-device subdir in the
     * destination. **Irreversible** once any copy has run (see [structureLocked]):
     * mixing flat and nested layouts in the same destination would create
     * duplicates with no clean de-dup semantics, so we forbid the toggle entirely.
     * Defaults to false so existing destinations keep their flat layout.
     */
    val copyDirectoryStructure: Boolean = false,
) {
    /**
     * True when this profile refers to the same physical device as [deviceKey].
     *
     * Uses [com.garag.nikoncopy.mtp.NikonDirect.keysReferToSameDevice] so a
     * legacy `vid:pid:mfr:product` key matches a freshly-detected `v2:vid:pid:serial`
     * key (and vice-versa) by their vid:pid prefix, enabling silent profile
     * upgrades after USB permission is granted. Two v2 keys with different
     * serials are treated as distinct devices.
     */
    fun matches(deviceKey: String): Boolean =
        com.garag.nikoncopy.mtp.NikonDirect.keysReferToSameDevice(this.deviceKey, deviceKey)

    /** True when the most recent copy hasn't been followed by a fix-dates run. */
    val hasUnfixedBatch: Boolean
        get() = cacheCompletedAt > 0 && cacheCompletedAt > lastFixCompletedAt

    /**
     * True once the directory-structure flag is frozen — either because the user
     * already turned it on (no going back) or because a copy has already run with
     * the current setting (toggling now would create the mixed-layout mess we want
     * to avoid). The UI should disable the toggle whenever this is true.
     */
    val structureLocked: Boolean
        get() = copyDirectoryStructure || cacheStartedAt > 0 || cacheCompletedAt > 0
}

// Backward-compat alias for existing call sites that still reference the old name.
typealias CameraProfile = DeviceProfile

object CameraProfileCodec {
    fun encode(profiles: List<DeviceProfile>): String {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("deviceKey", p.deviceKey)
                put("deviceName", p.deviceName)
                put("kind", p.kind.name)
                put("destinationUri", p.destinationUri ?: JSONObject.NULL)
                put("sourceTreeUri", p.sourceTreeUri ?: JSONObject.NULL)
                put("selectedDirectories", p.selectedDirectories.toJsonArray())
                put("selectedExtensions", p.selectedExtensions.toJsonArray())
                put("detectedDirectories", p.detectedDirectories.toJsonArray())
                put("detectedExtensions", p.detectedExtensions.toJsonArray())
                put("detectedDirectoryTimes", JSONObject().apply {
                    p.detectedDirectoryTimes.forEach { (k, v) -> put(k, v) }
                })
                put("cacheStartedAt", p.cacheStartedAt)
                put("cacheCompletedAt", p.cacheCompletedAt)
                put("cacheFiles", p.cacheFiles)
                put("lastFixCompletedAt", p.lastFixCompletedAt)
                put("updatedAt", p.updatedAt)
                put("copyDirectoryStructure", p.copyDirectoryStructure)
            })
        }
        return arr.toString()
    }

    fun decode(raw: String?): List<DeviceProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").ifBlank { obj.optString("deviceKey") }
                    val deviceKey = obj.optString("deviceKey")
                    if (id.isBlank() || deviceKey.isBlank()) continue
                    val kind = runCatching { DeviceKind.valueOf(obj.optString("kind", "PTP")) }
                        .getOrDefault(DeviceKind.PTP)
                    add(
                        DeviceProfile(
                            id = id,
                            deviceKey = deviceKey,
                            deviceName = obj.optString("deviceName")
                                .ifBlank { if (kind == DeviceKind.MSC) "外接存储" else "PTP Camera" },
                            kind = kind,
                            destinationUri = obj.optStringOrNull("destinationUri"),
                            sourceTreeUri = obj.optStringOrNull("sourceTreeUri"),
                            selectedDirectories = obj.optStringSet("selectedDirectories"),
                            selectedExtensions = obj.optStringSet("selectedExtensions"),
                            detectedDirectories = obj.optStringSet("detectedDirectories"),
                            detectedExtensions = obj.optStringSet("detectedExtensions"),
                            detectedDirectoryTimes = obj.optStringLongMap("detectedDirectoryTimes"),
                            cacheStartedAt = obj.optLong("cacheStartedAt", 0L),
                            cacheCompletedAt = obj.optLong("cacheCompletedAt", 0L),
                            cacheFiles = obj.optLong("cacheFiles", 0L),
                            lastFixCompletedAt = obj.optLong("lastFixCompletedAt", 0L),
                            updatedAt = obj.optLong("updatedAt", 0L),
                            copyDirectoryStructure = obj.optBoolean("copyDirectoryStructure", false),
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun Set<String>.toJsonArray(): JSONArray {
        val arr = JSONArray()
        sorted().forEach { arr.put(it) }
        return arr
    }

    private fun JSONObject.optStringSet(key: String): Set<String> {
        val arr = optJSONArray(key) ?: return emptySet()
        return buildSet {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optStringLongMap(key: String): Map<String, Long> {
        val obj = optJSONObject(key) ?: return emptyMap()
        return buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                put(k, obj.optLong(k, 0L))
            }
        }
    }
}

fun extensionLabel(ext: String): String = when (ext.lowercase()) {
    "jpg", "jpeg" -> "JPEG"
    "nef" -> "Nikon RAW"
    "arw" -> "Sony RAW"
    "cr2", "cr3" -> "Canon RAW"
    "raf" -> "Fuji RAW"
    "rw2" -> "Panasonic RAW"
    "dng" -> "DNG RAW"
    "heif", "heic", "hif" -> "HEIF"
    "mp4", "mov" -> ext.uppercase()
    else -> ext.uppercase()
}
