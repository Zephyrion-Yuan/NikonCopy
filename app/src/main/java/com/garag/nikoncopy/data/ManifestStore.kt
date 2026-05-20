package com.garag.nikoncopy.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Append-only file that persists the names of files we have successfully written to
 * the destination across sessions. Backed by a plain UTF-8 text file, one name per
 * line, in the app's internal storage. We keep it as a flat file (rather than
 * DataStore) so a 100k-name manifest is still O(N) to load and O(1) to append.
 */
class ManifestStore(context: Context, profileId: String = LEGACY_GLOBAL_ID) {
    /**
     * Sanitize profile id for use as a filename component. Keeps alphanumerics,
     * underscores, hyphens, and dots; everything else becomes underscore.
     */
    private val safeId: String = profileId
        .take(80)
        .replace("[^A-Za-z0-9._-]".toRegex(), "_")
    private val suffix = if (safeId == LEGACY_GLOBAL_ID) "" else "_$safeId"
    private val file = File(context.filesDir, "copied_manifest$suffix.txt")
    private val batchFile = File(context.filesDir, "last_batch$suffix.tsv")
    private val mutex = Mutex()
    private val batchMutex = Mutex()

    suspend fun loadAll(): Set<String> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptySet<String>()
        runCatching {
            file.useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
            }
        }.getOrElse {
            Log.w(TAG, "manifest read failed", it)
            emptySet()
        }
    }

    /** Open an append-session — flush+close at the end via [Session.close]. */
    suspend fun openAppendSession(): Session = withContext(Dispatchers.IO) {
        mutex.lock() // released by Session.close
        runCatching {
            file.parentFile?.mkdirs()
            Session(this@ManifestStore, FileOutputStream(file, /* append = */ true))
        }.onFailure { mutex.unlock() }.getOrThrow()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { file.delete() }
        batchMutex.withLock { batchFile.delete() }
    }

    /**
     * Open a BatchSession — truncates the last-batch file and starts fresh so
     * this session's entries replace any prior batch. Each entry is TSV:
     *   name\tisMediaStore\turi
     */
    suspend fun openBatchSession(): BatchSession = withContext(Dispatchers.IO) {
        batchMutex.lock()
        runCatching {
            batchFile.parentFile?.mkdirs()
            BatchSession(this@ManifestStore, FileOutputStream(batchFile, /* append = */ false))
        }.onFailure { batchMutex.unlock() }.getOrThrow()
    }

    data class BatchEntry(val name: String, val isMediaStore: Boolean, val uri: Uri)

    /** Read the last-batch manifest. Returns empty if none. */
    suspend fun loadLastBatch(): List<BatchEntry> = withContext(Dispatchers.IO) {
        if (!batchFile.exists()) return@withContext emptyList()
        runCatching {
            batchFile.useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.split('\t', limit = 3)
                    if (parts.size != 3) return@mapNotNull null
                    BatchEntry(
                        name = parts[0],
                        isMediaStore = parts[1] == "1",
                        uri = Uri.parse(parts[2]),
                    )
                }.toList()
            }
        }.getOrElse {
            Log.w(TAG, "batch read failed", it)
            emptyList()
        }
    }

    class BatchSession internal constructor(
        private val store: ManifestStore,
        fos: FileOutputStream,
    ) : AutoCloseable {
        private val writer = fos.bufferedWriter()
        private var closed = false

        fun add(name: String, isMediaStore: Boolean, uri: Uri) {
            if (closed) return
            writer.write("$name\t${if (isMediaStore) "1" else "0"}\t$uri")
            writer.newLine()
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                writer.flush()
                writer.close()
            } catch (_: Throwable) {}
            finally { store.batchMutex.unlock() }
        }
    }

    class Session internal constructor(
        private val store: ManifestStore,
        fos: FileOutputStream,
    ) : AutoCloseable {
        private val writer = fos.bufferedWriter()
        private var closed = false

        fun add(name: String) {
            if (closed) return
            writer.write(name)
            writer.newLine()
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                writer.flush()
                writer.close()
            } catch (_: Throwable) {
                // best effort
            } finally {
                store.mutex.unlock()
            }
        }
    }

    companion object {
        private const val TAG = "ManifestStore"
        /** Sentinel id used by the pre-multi-device global manifest (file name unchanged). */
        const val LEGACY_GLOBAL_ID = "__global__"
    }
}
