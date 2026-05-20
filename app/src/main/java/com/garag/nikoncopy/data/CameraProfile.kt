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
    val cacheStartedAt: Long = 0L,
    val cacheCompletedAt: Long = 0L,
    val cacheFiles: Long = 0L,
    val lastFixCompletedAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    fun matches(deviceKey: String): Boolean = this.deviceKey == deviceKey

    /** True when the most recent copy hasn't been followed by a fix-dates run. */
    val hasUnfixedBatch: Boolean
        get() = cacheCompletedAt > 0 && cacheCompletedAt > lastFixCompletedAt
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
                put("cacheStartedAt", p.cacheStartedAt)
                put("cacheCompletedAt", p.cacheCompletedAt)
                put("cacheFiles", p.cacheFiles)
                put("lastFixCompletedAt", p.lastFixCompletedAt)
                put("updatedAt", p.updatedAt)
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
                            cacheStartedAt = obj.optLong("cacheStartedAt", 0L),
                            cacheCompletedAt = obj.optLong("cacheCompletedAt", 0L),
                            cacheFiles = obj.optLong("cacheFiles", 0L),
                            lastFixCompletedAt = obj.optLong("lastFixCompletedAt", 0L),
                            updatedAt = obj.optLong("updatedAt", 0L),
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
