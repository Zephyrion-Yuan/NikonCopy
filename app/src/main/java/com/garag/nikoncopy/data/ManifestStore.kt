package com.garag.nikoncopy.data

import android.content.Context
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
class ManifestStore(context: Context) {
    private val file = File(context.filesDir, "copied_manifest.txt")
    private val mutex = Mutex()

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
    }
}
