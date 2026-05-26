package com.garag.nikoncopy.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "DestinationIndex"
private const val INDEX_FILE_NAME = ".nikoncopy_index.json"
private const val INDEX_MIME = "application/json"

/**
 * One device's binding to a destination subdirectory.
 *
 * The [subdir] is the **destination-side** folder name (not a user-facing label —
 * the user-facing label lives on [DeviceProfile.deviceName] and can be edited
 * freely without disturbing the existing file layout). [subdir] is allocated
 * at first copy and frozen forever after; if the user wants a different
 * destination layout they need to delete the index entry by hand or use the
 * "delete profile and recreate" path with a clean destination.
 */
data class DestinationBinding(
    val deviceKey: String,
    val subdir: String,
    val firstCopyAt: Long,
    val lastCopyAt: Long,
)

/**
 * Persistent map from `deviceKey → subdir` stored as `.nikoncopy_index.json`
 * in the **destination tree's root**. Decouples device-identity from
 * profile-identity:
 *
 *   - User deletes a profile and re-creates it → new profile gets the same
 *     [DeviceBinding] back via deviceKey lookup, so files keep landing in the
 *     same subdir (and the existing-name skip logic naturally resumes the
 *     incremental state from the filesystem itself).
 *   - Two cameras of the same model → distinct deviceKeys (post iSerial
 *     upgrade) → distinct subdirs, allocated automatically with `(2)`, `(3)`
 *     suffixes when the user-supplied deviceName collides.
 *   - Multiple SD cards in the same destination → same story; UUIDs make
 *     deviceKeys unique even when the card descriptions are generic
 *     ("SDXC Card").
 *
 * Format is plain JSON so a power user can inspect / edit / rename subdirs
 * manually without us shipping a UI for it. Format is also forward-compatible
 * (extra keys are ignored on read).
 */
class DestinationIndex(
    private val resolver: ContentResolver,
    private val destinationTreeUri: Uri,
) {

    /**
     * Look up the subdirectory bound to [deviceKey], allocating + persisting a
     * new one if absent.
     *
     * Subdir allocation: starts from [preferredName] (typically the profile's
     * [DeviceProfile.deviceName]), sanitised for filesystem safety. If that name
     * already maps to a *different* deviceKey, append ` (2)`, ` (3)`, … until
     * a free slot is found. The new binding is written back to the index file
     * before returning.
     *
     * Concurrency: a process-local mutex serialises read-modify-write cycles.
     * The index file itself is small enough (a handful of devices typically)
     * that we don't worry about partial writes — we overwrite the whole file
     * each time.
     */
    suspend fun resolveOrAllocate(
        deviceKey: String,
        preferredName: String,
        now: Long = System.currentTimeMillis(),
    ): DestinationBinding = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readUnlocked().toMutableMap()
            val existing = all[deviceKey]
            if (existing != null) {
                // Touch lastCopyAt opportunistically — the index doubles as a "when
                // did this device last sync" log, useful for the user inspecting it.
                val updated = existing.copy(lastCopyAt = now)
                all[deviceKey] = updated
                writeUnlocked(all)
                return@withContext updated
            }
            val taken = all.values.map { it.subdir }.toSet()
            val subdir = allocateUnique(sanitizeSubdir(preferredName), taken)
            val binding = DestinationBinding(
                deviceKey = deviceKey,
                subdir = subdir,
                firstCopyAt = now,
                lastCopyAt = now,
            )
            all[deviceKey] = binding
            writeUnlocked(all)
            binding
        }
    }

    /** Read-only lookup. Returns null if no binding exists. */
    suspend fun find(deviceKey: String): DestinationBinding? = withContext(Dispatchers.IO) {
        mutex.withLock { readUnlocked()[deviceKey] }
    }

    /** Update [lastCopyAt] for an existing binding. No-op if missing. */
    suspend fun touch(deviceKey: String, at: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readUnlocked().toMutableMap()
            val existing = all[deviceKey] ?: return@withLock
            all[deviceKey] = existing.copy(lastCopyAt = at)
            writeUnlocked(all)
        }
    }

    // ---------- internal: read/write the JSON blob ----------

    private fun readUnlocked(): Map<String, DestinationBinding> {
        val docUri = findIndexDocUri() ?: return emptyMap()
        val raw = runCatching {
            resolver.openInputStream(docUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return emptyMap()
        return parse(raw)
    }

    private fun writeUnlocked(all: Map<String, DestinationBinding>) {
        val payload = encode(all)
        val docUri = findIndexDocUri() ?: createIndexDoc() ?: run {
            Log.w(TAG, "could not create index file in destination — binding lost on next launch")
            return
        }
        try {
            resolver.openOutputStream(docUri, "wt")?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        } catch (t: Throwable) {
            Log.w(TAG, "write failed: ${t.message}")
        }
    }

    private fun findIndexDocUri(): Uri? {
        val rootId = try { DocumentsContract.getTreeDocumentId(destinationTreeUri) } catch (_: Throwable) { return null }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(destinationTreeUri, rootId)
        return try {
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    if (c.getString(nameCol) == INDEX_FILE_NAME) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(destinationTreeUri, c.getString(idCol))
                    }
                }
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "findIndexDocUri failed: ${t.message}")
            null
        }
    }

    private fun createIndexDoc(): Uri? {
        val rootId = try { DocumentsContract.getTreeDocumentId(destinationTreeUri) } catch (_: Throwable) { return null }
        val parent = DocumentsContract.buildDocumentUriUsingTree(destinationTreeUri, rootId)
        return try {
            DocumentsContract.createDocument(resolver, parent, INDEX_MIME, INDEX_FILE_NAME)
        } catch (t: Throwable) {
            Log.w(TAG, "createDocument failed: ${t.message}")
            null
        }
    }

    companion object {
        // Cross-instance serialisation: separate DestinationIndex instances may be
        // constructed per-engine-call but all touch the same physical file. The
        // tree URI alone isn't a great mutex key (we'd want stronger invariants
        // for multi-process safety) but it's sufficient for the in-app case where
        // the foreground service is the only writer.
        private val mutex = Mutex()

        /** Strip characters that are unsafe across the FAT/exFAT family used by SD cards. */
        fun sanitizeSubdir(name: String): String {
            val cleaned = name.trim()
                .replace(Regex("""[\\/:*?"<>|]"""), "_")
                .replace(Regex("""\s+"""), " ")
                .trim('.', ' ')
                .take(80)
            return cleaned.ifBlank { "device" }
        }

        /** Append ` (2)`, ` (3)`… until [base] doesn't collide with [taken]. */
        fun allocateUnique(base: String, taken: Set<String>): String {
            if (base !in taken) return base
            var i = 2
            while (true) {
                val candidate = "$base ($i)"
                if (candidate !in taken) return candidate
                i++
            }
        }

        internal fun encode(all: Map<String, DestinationBinding>): String {
            val devices = JSONObject()
            all.toSortedMap().forEach { (key, b) ->
                devices.put(key, JSONObject().apply {
                    put("subdir", b.subdir)
                    put("firstCopyAt", b.firstCopyAt)
                    put("lastCopyAt", b.lastCopyAt)
                })
            }
            // Top-level object so we can grow the schema (e.g. add user_notes) without
            // breaking existing parsers.
            return JSONObject().apply {
                put("version", 1)
                put("devices", devices)
            }.toString(2)
        }

        internal fun parse(raw: String): Map<String, DestinationBinding> {
            if (raw.isBlank()) return emptyMap()
            return runCatching {
                val root = JSONObject(raw)
                val devices = root.optJSONObject("devices") ?: return@runCatching emptyMap()
                buildMap {
                    val keys = devices.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val obj = devices.optJSONObject(key) ?: continue
                        val subdir = obj.optString("subdir").takeIf { it.isNotBlank() } ?: continue
                        put(
                            key,
                            DestinationBinding(
                                deviceKey = key,
                                subdir = subdir,
                                firstCopyAt = obj.optLong("firstCopyAt", 0L),
                                lastCopyAt = obj.optLong("lastCopyAt", 0L),
                            ),
                        )
                    }
                }
            }.getOrElse {
                Log.w(TAG, "parse failed: ${it.message}")
                emptyMap()
            }
        }
    }
}
