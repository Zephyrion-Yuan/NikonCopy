package com.garag.nikoncopy.copy

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.garag.nikoncopy.data.ManifestStore
import com.garag.nikoncopy.mtp.MtpFile
import com.garag.nikoncopy.mtp.PtpClient
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "CopyEngine"

private val EXIF_EXTS = setOf("nef", "arw", "dng", "jpg", "jpeg", "heif", "heic", "hif")
private val MEDIASTORE_ROOTS = setOf("Pictures", "DCIM", "Movies")

class CopyEngine(
    private val context: Context,
    private val manifest: ManifestStore,
) {

    /**
     * Direct-mode copy flow: source is a [PtpClient] holding the Nikon's USB
     * interface 0 directly and bypasses `com.android.mtp` entirely.
     *
     * Caller is responsible for opening the PtpClient (USB perm, claim) and closing
     * it after the flow completes. We do NOT close it here so the caller can decide
     * lifetime (e.g. keep open across multiple back-to-back copies).
     */
    fun copyFlowDirect(
        ptp: PtpClient,
        destinationTreeUri: Uri,
        mode: CopyMode,
        filter: CopyFilter = CopyFilter(),
    ): Flow<CopyState> = channelFlow {
        send(CopyState.Scanning(0))
        val resolver = context.contentResolver

        val allManifest = manifest.loadAll()
        val orphansCleaned = cleanupOrphanPending(resolver, destinationTreeUri, allManifest)
        val initialExisting = readDestinationNames(resolver, destinationTreeUri) - orphansCleaned
        val diskRecovered = recoverDiskPendingOrphans(resolver, destinationTreeUri, initialExisting)
        val existingNames = initialExisting + diskRecovered
        val manifestNames = if (mode == CopyMode.INCREMENTAL) allManifest else emptySet()
        Log.i(TAG, "[direct] mode=$mode existing=${existingNames.size} orphansCleaned=${orphansCleaned.size} diskRecovered=${diskRecovered.size} manifest=${manifestNames.size}")

        val candidates = try {
            ptp.listFiles()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "[direct] PTP listFiles failed", t)
            send(CopyState.Failed("PTP 列表失败: ${t.message ?: t.javaClass.simpleName}"))
            return@channelFlow
        }.filter { filter.accepts(it.directoryPath, it.extension) }

        val (toCopy, toSkip) = candidates.partition { f ->
            when (mode) {
                CopyMode.ALL -> f.name !in existingNames
                CopyMode.INCREMENTAL -> f.name !in existingNames && f.name !in manifestNames
            }
        }
        send(CopyState.Scanning(candidates.size))
        Log.i(TAG, "[direct] candidates=${candidates.size} toCopy=${toCopy.size} toSkip=${toSkip.size}")

        if (toCopy.isEmpty()) {
            send(CopyState.Done(
                filesCopied = 0,
                filesSkipped = toSkip.size,
                totalBytes = 0L,
                elapsedMillis = 0L,
            ))
            return@channelFlow
        }

        val grandTotalBytes = toCopy.sumOf { it.size.coerceAtLeast(0L) }
        val startNanos = System.nanoTime()

        val perFileBytes = AtomicLong(0)
        val priorTotalBytes = AtomicLong(0)
        val currentIndex = AtomicInteger(0)
        val currentName = AtomicReference("(scanning)")
        val pendingPostProcess = AtomicInteger(0)

        val emitterJob = launch {
            var lastEmitNanos = System.nanoTime()
            var lastEmitBytes = 0L
            try {
                while (isActive) {
                    delay(200)
                    val now = System.nanoTime()
                    val total = priorTotalBytes.get() + perFileBytes.get()
                    val rate = run {
                        val deltaSec = (now - lastEmitNanos) / 1_000_000_000.0
                        if (deltaSec > 0) ((total - lastEmitBytes) / deltaSec).toLong() else 0L
                    }
                    lastEmitNanos = now
                    lastEmitBytes = total
                    send(CopyState.Running(
                        currentIndex = currentIndex.get(),
                        totalFiles = toCopy.size,
                        currentName = currentName.get(),
                        bytesCopied = total,
                        totalBytes = grandTotalBytes,
                        bytesPerSecond = rate,
                        phase = CopyState.Phase.COPYING,
                        pendingPostProcess = 0,
                    ))
                }
            } catch (_: CancellationException) {}
        }

        val session = manifest.openAppendSession()
        val batch = manifest.openBatchSession()
        val destDir = DocumentsContract.buildDocumentUriUsingTree(
            destinationTreeUri,
            DocumentsContract.getTreeDocumentId(destinationTreeUri),
        )

        val mediaStoreRelPath = mediaStoreRelativePath(destinationTreeUri)
        Log.i(TAG, "[direct] destination MediaStore relPath=$mediaStoreRelPath")

        var filesCopied = 0
        var consecutiveErrors = 0
        val failedFiles = mutableListOf<MtpFile>()
        var usbDead = false

        /**
         * Attempt to copy one file. Returns copiedBytes on success, -1 on failure.
         * Handles destination URI creation + cleanup on error.
         */
        suspend fun attemptCopy(mtpFile: MtpFile, attemptLabel: String): Long {
            val mime = guessMime(mtpFile.name)
            val (newDoc, isMediaStore) = try {
                createDestinationUri(resolver, destDir, mediaStoreRelPath, mtpFile.name, mime)
            } catch (t: Throwable) {
                Log.e(TAG, "[direct] $attemptLabel createDocument failed for ${mtpFile.name}: ${t.message}")
                return -1
            }

            val fileStartNanos = System.nanoTime()
            val copiedBytes = try {
                withContext(Dispatchers.IO) {
                    copyOneViaPtp(ptp, mtpFile, newDoc) { bytes -> perFileBytes.set(bytes) }
                }
            } catch (ce: CancellationException) {
                // Cancelled mid-transfer — delete the partial row so it doesn't
                // orphan the slot (preventing re-copy on next run).
                runCatching {
                    if (isMediaStore) resolver.delete(newDoc, null, null)
                    else DocumentsContract.deleteDocument(resolver, newDoc)
                }.onSuccess {
                    Log.i(TAG, "[direct] $attemptLabel cancelled mid-copy, cleaned partial ${mtpFile.name}")
                }
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "[direct] $attemptLabel PTP copy failed for ${mtpFile.name}: ${t.message}")
                runCatching {
                    if (isMediaStore) resolver.delete(newDoc, null, null)
                    else DocumentsContract.deleteDocument(resolver, newDoc)
                }
                return -1
            }
            val fileMs = (System.nanoTime() - fileStartNanos) / 1_000_000L
            val fileMBs = if (fileMs > 0) copiedBytes / 1024.0 / 1024.0 / (fileMs / 1000.0) else 0.0
            Log.i(TAG, "[direct] $attemptLabel ${mtpFile.name} ${copiedBytes}B in ${fileMs}ms = %.1f MB/s ${if (isMediaStore) "[mediastore]" else "[saf]"}".format(fileMBs))

            priorTotalBytes.addAndGet(copiedBytes)
            perFileBytes.set(0)

            session.add(mtpFile.name)
            batch.add(mtpFile.name, isMediaStore, newDoc)
            // Note: do NOT publish (IS_PENDING=1 stays). fix-dates will
            // publish with DATE_TAKEN in one update to avoid Xiaomi Gallery
            // caching a null DATE_TAKEN at first-index time.
            return copiedBytes
        }

        try {
            // -------- First pass: one attempt per file --------
            for ((idx, mtpFile) in toCopy.withIndex()) {
                currentIndex.set(idx + 1)
                currentName.set(mtpFile.name)
                perFileBytes.set(0)

                val bytes = attemptCopy(mtpFile, "try1")
                if (bytes < 0) {
                    failedFiles += mtpFile
                    consecutiveErrors++
                    if (consecutiveErrors >= 4) {
                        Log.e(TAG, "[direct] 4 consecutive failures — USB likely dead, stopping main pass")
                        usbDead = true
                        // Remaining un-attempted files also need retry later
                        val remainingStart = idx + 1
                        if (remainingStart < toCopy.size) {
                            failedFiles += toCopy.subList(remainingStart, toCopy.size)
                        }
                        break
                    }
                    continue
                }
                consecutiveErrors = 0
                filesCopied++
            }

            // -------- End-of-batch retry pass: up to 3 more attempts per failed file --------
            if (failedFiles.isNotEmpty()) {
                Log.i(TAG, "[direct] end-of-batch retry: ${failedFiles.size} files")
                val stillFailed = mutableListOf<MtpFile>()
                for (mtpFile in failedFiles) {
                    currentName.set(mtpFile.name)
                    perFileBytes.set(0)
                    var recovered = false
                    for (attempt in 2..4) {
                        val bytes = attemptCopy(mtpFile, "try$attempt")
                        if (bytes >= 0) {
                            recovered = true
                            filesCopied++
                            break
                        }
                        // brief pause between attempts
                        kotlinx.coroutines.delay(300)
                    }
                    if (!recovered) stillFailed += mtpFile
                }
                failedFiles.clear()
                failedFiles += stillFailed
                Log.i(TAG, "[direct] after retry: ${failedFiles.size} still failed")
            }
        } finally {
            session.close()
            batch.close()
            emitterJob.cancelAndJoin()
        }

        val elapsed = (System.nanoTime() - startNanos) / 1_000_000L
        val totalBytes = priorTotalBytes.get()
        val avgMBs = if (elapsed > 0) totalBytes / 1024.0 / 1024.0 / (elapsed / 1000.0) else 0.0
        Log.i(TAG, "[direct] Done: copied=$filesCopied failed=${failedFiles.size} skipped=${toSkip.size} $totalBytes B in ${elapsed}ms = %.1f MB/s avg".format(avgMBs))

        if (filesCopied == 0 && usbDead) {
            send(CopyState.Failed("USB 连接断开，未能拷贝任何文件"))
        } else {
            send(CopyState.Done(
                filesCopied = filesCopied,
                filesSkipped = toSkip.size,
                totalBytes = totalBytes,
                elapsedMillis = elapsed,
            ))
        }
    }.flowOn(Dispatchers.IO)

    private fun copyOneViaPtp(
        ptp: PtpClient,
        file: MtpFile,
        dstUri: Uri,
        onProgress: (Long) -> Unit,
    ): Long {
        val pfd = context.contentResolver.openFileDescriptor(dstUri, "w")
            ?: throw IOException("openDst null: ${file.name}")
        return pfd.use { p ->
            FileOutputStream(p.fileDescriptor).use { fos ->
                ptp.copyObjectTo(file.handle, file.size, fos, onProgress)
                fos.flush()
            }
            file.size  // PTP container length matches; could read .size from copyObjectTo's dataLen
        }
    }

    // ----------------------------------------------------------------- SAF copy

    /** A single source file discovered while walking a SAF tree. */
    private data class SafFile(
        val docUri: Uri,
        val name: String,
        val size: Long,
        val lastModified: Long,
        val parentPath: String,
        val extension: String,
    )

    /**
     * MSC / SAF-source copy flow. Source is a SAF tree URI (e.g. mounted SD card,
     * USB stick, or any directory the user can pick via OPEN_DOCUMENT_TREE).
     * Same destination + manifest + EXIF/DATE_TAKEN enrichment as [copyFlowDirect];
     * only the source enumeration & byte-pump differ.
     *
     * Walking strategy mirrors [com.garag.nikoncopy.data.MediaProbe]: skip
     * Android system / hidden / trash dirs, prefer convention dirs, but here we
     * walk the *full* tree because the user has explicitly opted into copying
     * everything that the [filter] accepts. The CopyFilter is the actual
     * "what to import" gate; the skip-dirs list just keeps us out of garbage.
     */
    fun copyFlowSaf(
        sourceTreeUri: Uri,
        destinationTreeUri: Uri,
        mode: CopyMode,
        filter: CopyFilter = CopyFilter(),
    ): Flow<CopyState> = channelFlow {
        send(CopyState.Scanning(0))
        val resolver = context.contentResolver

        val allManifest = manifest.loadAll()
        val orphansCleaned = cleanupOrphanPending(resolver, destinationTreeUri, allManifest)
        val initialExisting = readDestinationNames(resolver, destinationTreeUri) - orphansCleaned
        val diskRecovered = recoverDiskPendingOrphans(resolver, destinationTreeUri, initialExisting)
        val existingNames = initialExisting + diskRecovered
        val manifestNames = if (mode == CopyMode.INCREMENTAL) allManifest else emptySet()
        Log.i(TAG, "[saf] mode=$mode existing=${existingNames.size} orphansCleaned=${orphansCleaned.size} diskRecovered=${diskRecovered.size} manifest=${manifestNames.size}")

        val candidates = try {
            walkSafTree(resolver, sourceTreeUri, filter)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "[saf] walk failed", t)
            send(CopyState.Failed("SAF 列表失败: ${t.message ?: t.javaClass.simpleName}"))
            return@channelFlow
        }

        val (toCopy, toSkip) = candidates.partition { f ->
            when (mode) {
                CopyMode.ALL -> f.name !in existingNames
                CopyMode.INCREMENTAL -> f.name !in existingNames && f.name !in manifestNames
            }
        }
        send(CopyState.Scanning(candidates.size))
        Log.i(TAG, "[saf] candidates=${candidates.size} toCopy=${toCopy.size} toSkip=${toSkip.size}")

        if (toCopy.isEmpty()) {
            send(CopyState.Done(
                filesCopied = 0,
                filesSkipped = toSkip.size,
                totalBytes = 0L,
                elapsedMillis = 0L,
            ))
            return@channelFlow
        }

        val grandTotalBytes = toCopy.sumOf { it.size.coerceAtLeast(0L) }
        val startNanos = System.nanoTime()

        val perFileBytes = AtomicLong(0)
        val priorTotalBytes = AtomicLong(0)
        val currentIndex = AtomicInteger(0)
        val currentName = AtomicReference("(scanning)")

        val emitterJob = launch {
            var lastEmitNanos = System.nanoTime()
            var lastEmitBytes = 0L
            try {
                while (isActive) {
                    delay(200)
                    val now = System.nanoTime()
                    val total = priorTotalBytes.get() + perFileBytes.get()
                    val rate = run {
                        val deltaSec = (now - lastEmitNanos) / 1_000_000_000.0
                        if (deltaSec > 0) ((total - lastEmitBytes) / deltaSec).toLong() else 0L
                    }
                    lastEmitNanos = now
                    lastEmitBytes = total
                    send(CopyState.Running(
                        currentIndex = currentIndex.get(),
                        totalFiles = toCopy.size,
                        currentName = currentName.get(),
                        bytesCopied = total,
                        totalBytes = grandTotalBytes,
                        bytesPerSecond = rate,
                        phase = CopyState.Phase.COPYING,
                        pendingPostProcess = 0,
                    ))
                }
            } catch (_: CancellationException) {}
        }

        val session = manifest.openAppendSession()
        val batch = manifest.openBatchSession()
        val destDir = DocumentsContract.buildDocumentUriUsingTree(
            destinationTreeUri,
            DocumentsContract.getTreeDocumentId(destinationTreeUri),
        )
        val mediaStoreRelPath = mediaStoreRelativePath(destinationTreeUri)
        Log.i(TAG, "[saf] destination MediaStore relPath=$mediaStoreRelPath")

        var filesCopied = 0
        val failedFiles = mutableListOf<SafFile>()

        try {
            for ((idx, srcFile) in toCopy.withIndex()) {
                currentIndex.set(idx + 1)
                currentName.set(srcFile.name)
                perFileBytes.set(0)

                val mime = guessMime(srcFile.name)
                val (newDoc, isMediaStore) = try {
                    createDestinationUri(resolver, destDir, mediaStoreRelPath, srcFile.name, mime)
                } catch (t: Throwable) {
                    Log.e(TAG, "[saf] createDocument failed for ${srcFile.name}: ${t.message}")
                    failedFiles += srcFile
                    continue
                }

                val fileStartNanos = System.nanoTime()
                val copied = try {
                    withContext(Dispatchers.IO) {
                        copyOneViaSaf(resolver, srcFile, newDoc) { bytes -> perFileBytes.set(bytes) }
                    }
                } catch (ce: CancellationException) {
                    runCatching {
                        if (isMediaStore) resolver.delete(newDoc, null, null)
                        else DocumentsContract.deleteDocument(resolver, newDoc)
                    }
                    throw ce
                } catch (t: Throwable) {
                    Log.e(TAG, "[saf] copy failed for ${srcFile.name}: ${t.message}")
                    runCatching {
                        if (isMediaStore) resolver.delete(newDoc, null, null)
                        else DocumentsContract.deleteDocument(resolver, newDoc)
                    }
                    failedFiles += srcFile
                    continue
                }
                val fileMs = (System.nanoTime() - fileStartNanos) / 1_000_000L
                val fileMBs = if (fileMs > 0) copied / 1024.0 / 1024.0 / (fileMs / 1000.0) else 0.0
                Log.i(TAG, "[saf] ${srcFile.name} ${copied}B in ${fileMs}ms = %.1f MB/s ${if (isMediaStore) "[mediastore]" else "[saf-dst]"}".format(fileMBs))

                priorTotalBytes.addAndGet(copied)
                perFileBytes.set(0)

                session.add(srcFile.name)
                batch.add(srcFile.name, isMediaStore, newDoc)
                filesCopied++
            }
        } finally {
            session.close()
            batch.close()
            emitterJob.cancelAndJoin()
        }

        val elapsed = (System.nanoTime() - startNanos) / 1_000_000L
        val totalBytes = priorTotalBytes.get()
        val avgMBs = if (elapsed > 0) totalBytes / 1024.0 / 1024.0 / (elapsed / 1000.0) else 0.0
        Log.i(TAG, "[saf] Done: copied=$filesCopied failed=${failedFiles.size} skipped=${toSkip.size} $totalBytes B in ${elapsed}ms = %.1f MB/s avg".format(avgMBs))

        send(CopyState.Done(
            filesCopied = filesCopied,
            filesSkipped = toSkip.size,
            totalBytes = totalBytes,
            elapsedMillis = elapsed,
        ))
    }.flowOn(Dispatchers.IO)

    /**
     * Recursively walk a SAF tree, returning every file accepted by [filter].
     * Skips Android system / hidden / trash directories at every level so we
     * don't waste time enumerating thousands of irrelevant entries on a USB SSD.
     */
    private fun walkSafTree(
        resolver: ContentResolver,
        treeUri: Uri,
        filter: CopyFilter,
    ): List<SafFile> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val out = ArrayList<SafFile>()
        val queue: ArrayDeque<Pair<String, String>> = ArrayDeque()
        queue.addLast(rootDocId to "")
        while (queue.isNotEmpty()) {
            val (docId, path) = queue.removeFirst()
            for (entry in listSafEntries(resolver, treeUri, docId)) {
                if (entry.isDir) {
                    if (entry.name.startsWith(".")) continue
                    if (entry.name in SAF_SKIP_DIRS) continue
                    val nextPath = if (path.isEmpty()) entry.name else "$path/${entry.name}"
                    queue.addLast(entry.documentId to nextPath)
                } else {
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    if (ext.isBlank()) continue
                    if (!filter.accepts(path, ext)) continue
                    out += SafFile(
                        docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId),
                        name = entry.name,
                        size = entry.size,
                        lastModified = entry.lastModified,
                        parentPath = path,
                        extension = ext,
                    )
                }
            }
        }
        return out
    }

    private data class SafEntry(
        val documentId: String,
        val name: String,
        val size: Long,
        val lastModified: Long,
        val isDir: Boolean,
    )

    private fun listSafEntries(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocId: String,
    ): List<SafEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<SafEntry>()
        try {
            resolver.query(childrenUri, proj, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val mtimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (c.moveToNext()) {
                    val mime = c.getString(mimeCol)
                    val name = c.getString(nameCol) ?: continue
                    out += SafEntry(
                        documentId = c.getString(idCol),
                        name = name,
                        size = if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else 0L,
                        lastModified = if (mtimeCol >= 0 && !c.isNull(mtimeCol)) c.getLong(mtimeCol) else 0L,
                        isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "listSafEntries($parentDocId) failed: ${t.message}")
        }
        return out
    }

    /**
     * Stream-copy a SAF source file's bytes to [dstUri]. Uses [android.os.FileUtils.copy]
     * which dispatches to splice(2)/sendfile(2) when both descriptors are seekable
     * (typical for SAF-on-storage), falling back to a buffered read/write loop.
     */
    private fun copyOneViaSaf(
        resolver: ContentResolver,
        src: SafFile,
        dstUri: Uri,
        onProgress: (Long) -> Unit,
    ): Long {
        val srcPfd = resolver.openFileDescriptor(src.docUri, "r")
            ?: throw IOException("openSrc null: ${src.name}")
        return srcPfd.use { sp ->
            val dstPfd = resolver.openFileDescriptor(dstUri, "w")
                ?: throw IOException("openDst null: ${src.name}")
            dstPfd.use { dp ->
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                try {
                    android.os.FileUtils.copy(
                        sp.fileDescriptor,
                        dp.fileDescriptor,
                        null, // CancellationSignal — coroutine cancellation handles it
                        executor,
                        android.os.FileUtils.ProgressListener { progress -> onProgress(progress) },
                    )
                } finally {
                    executor.shutdown()
                }
            }
        }
    }

    /** Always-skip directory names when walking a SAF source for copy. */
    private val SAF_SKIP_DIRS = setOf(
        "Android", "LOST.DIR", "System Volume Information",
        "\$RECYCLE.BIN", "RECYCLER",
        ".Trash", ".Trashes", ".Spotlight-V100", ".fseventsd",
        ".TemporaryItems", ".thumbnails",
    )

    // ----------------------------------------------------------------- fix dates

    /**
     * High-parallelism date-fixing flow. Uses the last-batch manifest (recorded
     * at copy time) so we update exactly the files from the most recent copy
     * session — and we use the stored MediaStore URIs directly to avoid the
     * flaky queryMediaUriByPath lookup that was failing for some NEFs.
     */
    fun fixDatesFlow(
        destinationTreeUri: Uri,
        parallelism: Int = 6,
    ): Flow<CopyState> = channelFlow {
        send(CopyState.Scanning(0))
        val resolver = context.contentResolver

        val batch = manifest.loadLastBatch()
        Log.i(TAG, "[fixDates] loaded last batch: ${batch.size} files")
        send(CopyState.Scanning(batch.size))

        if (batch.isEmpty()) {
            send(CopyState.Failed("没有上次拷贝批次记录，请先执行拷贝"))
            return@channelFlow
        }

        val startNanos = System.nanoTime()
        val processed = AtomicInteger(0)
        val fixedOk = AtomicInteger(0)
        val alreadyFixed = AtomicInteger(0)
        val noExif = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val currentName = AtomicReference("...")

        val emitterJob = launch {
            try {
                while (isActive) {
                    delay(200)
                    send(CopyState.Running(
                        currentIndex = processed.get(),
                        totalFiles = batch.size,
                        currentName = currentName.get(),
                        bytesCopied = 0,
                        totalBytes = 0,
                        bytesPerSecond = 0,
                        phase = CopyState.Phase.FIXING_DATES,
                        pendingPostProcess = batch.size - processed.get(),
                    ))
                }
            } catch (_: CancellationException) {}
        }

        val queue = Channel<ManifestStore.BatchEntry>(capacity = Channel.UNLIMITED)
        launch { for (e in batch) queue.send(e); queue.close() }

        val workers = (1..parallelism).map {
            launch(Dispatchers.IO) {
                for (entry in queue) {
                    currentName.set(entry.name)
                    val outcome = try {
                        fixOneDateEntry(resolver, entry)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "[fixDates] threw for ${entry.name}: ${t.javaClass.simpleName}: ${t.message}")
                        FixOutcome.FAILED
                    }
                    when (outcome) {
                        FixOutcome.FIXED -> fixedOk.incrementAndGet()
                        FixOutcome.ALREADY_FIXED -> alreadyFixed.incrementAndGet()
                        FixOutcome.NO_EXIF -> noExif.incrementAndGet()
                        FixOutcome.FAILED -> failedCount.incrementAndGet()
                    }
                    processed.incrementAndGet()
                }
            }
        }
        workers.forEach { it.join() }
        emitterJob.cancelAndJoin()

        val elapsed = (System.nanoTime() - startNanos) / 1_000_000L
        val fixed = fixedOk.get()
        val alreadyOk = alreadyFixed.get()
        val noExifN = noExif.get()
        val failed = failedCount.get()
        Log.i(TAG, "[fixDates] Done: fixed=$fixed already=$alreadyOk no-exif=$noExifN failed=$failed total=${batch.size} in ${elapsed}ms")

        // Success semantics: fixed + already-fixed both count as "successfully handled".
        // Only no-exif and failed are reported as skipped.
        send(CopyState.Done(
            filesCopied = fixed + alreadyOk,
            filesSkipped = noExifN + failed,
            totalBytes = 0,
            elapsedMillis = elapsed,
        ))
    }.flowOn(Dispatchers.IO)

    /** Result of one date-fix attempt — used for end-of-batch reporting. */
    private enum class FixOutcome { FIXED, ALREADY_FIXED, NO_EXIF, FAILED }

    /**
     * Fix mtime + DATE_TAKEN for a single batch entry.
     *
     * CRITICAL SAFETY: We never toggle IS_PENDING on an already-published file.
     * Re-publishing a published file (via IS_PENDING=1→0) causes MediaProvider
     * to rename the underlying file to `.pending-<timestamp>-<name>` on disk.
     * If the subsequent update(IS_PENDING=0) fails or the row is GC'd, the
     * file becomes a permanent orphan with the `.pending-*` prefix — this is
     * what caused mass file loss on repeated fix-dates clicks.
     *
     * New contract:
     *   - If DATE_TAKEN already matches captureMillis → ALREADY_FIXED, do nothing
     *   - If IS_PENDING=1 (first-time publish after copy) → publish with DATE_TAKEN
     *   - If IS_PENDING=0 (already published) → just update DATE_TAKEN, no toggle
     */
    private fun fixOneDateEntry(
        resolver: ContentResolver,
        entry: ManifestStore.BatchEntry,
    ): FixOutcome {
        val name = entry.name
        val ext = name.substringAfterLast('.', "").lowercase()

        val captureMillis = if (ext in EXIF_EXTS) {
            extractExifCaptureMillis(resolver, entry.uri)
        } else 0L

        if (captureMillis <= 0) {
            Log.w(TAG, "[fixDates] no EXIF capture time for $name uri=${entry.uri}")
            return FixOutcome.NO_EXIF
        }

        // Short-circuit: if DATE_TAKEN is already correct, skip ALL operations.
        // This prevents any MediaStore toggling on re-runs.
        if (entry.isMediaStore) {
            val currentDateTaken = readMediaLong(resolver, entry.uri, MediaStore.MediaColumns.DATE_TAKEN)
            if (currentDateTaken == captureMillis) {
                Log.i(TAG, "[fixDates] $name already fixed (DATE_TAKEN=$currentDateTaken) — skipping")
                return FixOutcome.ALREADY_FIXED
            }
        }

        // Set file mtime via JNI futimens (safe — operates only on fd)
        val mtimeOk = MtimeUtil.setMtime(resolver, entry.uri, captureMillis)
        if (!mtimeOk) Log.w(TAG, "[fixDates] mtime set failed for $name")

        val dateTakenOk: Boolean = if (entry.isMediaStore) {
            updateDateTakenSafely(resolver, entry.uri, captureMillis, name)
        } else {
            // SAF-created: best-effort via path lookup
            val docId = try { DocumentsContract.getDocumentId(entry.uri) } catch (_: Throwable) { null }
            val path = if (docId != null && docId.startsWith("primary:"))
                "/storage/emulated/0/" + docId.removePrefix("primary:")
            else null
            if (path != null) {
                val mu = queryMediaUriByPath(resolver, path)
                if (mu != null) updateDateTakenSafely(resolver, mu, captureMillis, name)
                else { Log.w(TAG, "[fixDates] no MediaStore row for SAF file $name"); false }
            } else false
        }

        Log.i(TAG, "[fixDates] $name capture=$captureMillis mtime=$mtimeOk dateTaken=$dateTakenOk")
        return if (mtimeOk && dateTakenOk) FixOutcome.FIXED else FixOutcome.FAILED
    }

    /**
     * Safely update DATE_TAKEN on a MediaStore URI.
     *
     * If the file is currently IS_PENDING=1 (just copied, awaiting first publish):
     *   - Write DATE_TAKEN + flip IS_PENDING=0 in one update. This is the ideal
     *     path: Xiaomi Gallery first sees the file WITH the correct DATE_TAKEN.
     *
     * If the file is already IS_PENDING=0 (published):
     *   - Just update DATE_TAKEN. NO toggle. Xiaomi Gallery's cache may stay
     *     stale but we won't risk file loss from repeated toggling.
     */
    private fun updateDateTakenSafely(
        resolver: ContentResolver,
        mediaUri: Uri,
        captureMillis: Long,
        name: String,
    ): Boolean {
        val currentPending = readMediaLong(resolver, mediaUri, MediaStore.MediaColumns.IS_PENDING)
        val isPending = currentPending == 1L
        Log.i(TAG, "[fixDates] $name currentPending=$currentPending → ${if (isPending) "first-publish" else "in-place update"}")

        repeat(3) { attempt ->
            try {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATE_TAKEN, captureMillis)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, captureMillis / 1000)
                    if (isPending) put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(mediaUri, v, null, null)
            } catch (t: Throwable) {
                Log.w(TAG, "[fixDates] update threw for $name: ${t.message}")
            }
            val readBack = readMediaLong(resolver, mediaUri, MediaStore.MediaColumns.DATE_TAKEN)
            if (readBack == captureMillis) {
                runCatching { resolver.notifyChange(mediaUri, null) }
                Log.i(TAG, "[fixDates] DATE_TAKEN=$captureMillis verified for $name (attempt=${attempt + 1})")
                return true
            }
            Log.w(TAG, "[fixDates] DATE_TAKEN drift for $name: read=$readBack expected=$captureMillis (attempt=${attempt + 1})")
            try { Thread.sleep(200L * (attempt + 1)) } catch (_: Throwable) {}
        }
        return false
    }

    /**
     * If [destinationTreeUri] is a SAF tree under primary external storage AND it's
     * inside one of MediaStore's owned roots (Pictures / DCIM / Movies), return
     * the relative path string suitable for [MediaStore.MediaColumns.RELATIVE_PATH].
     * Otherwise null — caller should fall back to SAF createDocument.
     */
    private fun mediaStoreRelativePath(treeUri: Uri): String? {
        val docId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Throwable) { return null }
        if (!docId.startsWith("primary:")) return null
        val rel = docId.removePrefix("primary:")
        if (rel.isEmpty()) return null
        val first = rel.substringBefore('/')
        if (first !in MEDIASTORE_ROOTS) return null
        // RELATIVE_PATH wants a trailing slash for some providers; trailing-slash
        // is harmless on others, so always append.
        return if (rel.endsWith('/')) rel else "$rel/"
    }

    /**
     * Create the destination URI for one file. Returns (uri, isMediaStore).
     *
     * If the MediaStore relative-path is non-null AND the MIME is image or video,
     * we [ContentResolver.insert] into the relevant collection with IS_PENDING=1.
     * Otherwise, fall back to SAF createDocument.
     */
    private fun createDestinationUri(
        resolver: ContentResolver,
        safDestDir: Uri,
        mediaStoreRelPath: String?,
        fileName: String,
        mime: String,
    ): Pair<Uri, Boolean> {
        if (mediaStoreRelPath != null) {
            val collection = when {
                mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> null
            }
            if (collection != null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, mediaStoreRelPath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values)
                if (uri != null) return uri to true
                Log.w(TAG, "MediaStore insert returned null for $fileName; falling back to SAF")
            }
        }
        val uri = DocumentsContract.createDocument(resolver, safDestDir, mime, fileName)
            ?: throw IOException("createDocument returned null")
        return uri to false
    }

    private fun readMediaLong(
        resolver: ContentResolver,
        mediaUri: Uri,
        column: String,
    ): Long {
        return try {
            resolver.query(mediaUri, arrayOf(column), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        } catch (t: Throwable) {
            Log.w(TAG, "media readback for $column threw: ${t.message}")
            -2L
        }
    }

    /**
     * Fallback when MediaScanner's callback gives us a null URI: directly query
     * MediaStore for a row whose DATA column matches our path. Tries the Images
     * collection first (where image/x-nikon-nef SHOULD land), then Files (which
     * indexes everything).
     */
    private fun queryMediaUriByPath(resolver: ContentResolver, path: String): Uri? {
        val collections = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        )
        for (collection in collections) {
            try {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DATA}=?",
                    arrayOf(path),
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val id = c.getLong(0)
                        Log.i(TAG, "found by path in ${collection.lastPathSegment} id=$id path=$path")
                        return ContentUris.withAppendedId(collection, id)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "queryMediaUriByPath in $collection failed: ${t.message}")
            }
        }
        return null
    }

    /**
     * Read EXIF DateTimeOriginal from the destination file. We read from the
     * destination (regular file, supports seek) rather than the MTP source (sequential
     * pipe) so ExifInterface can do its quick header parse instead of slurping the
     * whole RAW into memory.
     *
     * Returns 0 if no usable timestamp.
     */
    private fun extractExifCaptureMillis(resolver: ContentResolver, dstUri: Uri): Long {
        return try {
            resolver.openFileDescriptor(dstUri, "r")?.use { pfd ->
                // ExifInterface(FileDescriptor) uses lseek() under the hood, so it
                // does targeted reads of the IFD/TIFF tags instead of slurping the
                // whole RAW into memory like the InputStream constructor would.
                val exif = ExifInterface(pfd.fileDescriptor)
                val ms = exif.dateTimeOriginal ?: exif.dateTime ?: -1L
                if (ms > 0) ms else 0L
            } ?: 0L
        } catch (t: Throwable) {
            Log.w(TAG, "EXIF parse failed: ${t.javaClass.simpleName}: ${t.message}")
            0L
        }
    }

    /**
     * Self-repair step for on-disk `.pending-<timestamp>-<name>` files whose
     * MediaStore row was GC'd by the system (e.g. after an app crash or OOM
     * mid-copy). These files can't be fixed via MediaStore API since the row
     * no longer exists. We rename them via SAF back to their original name
     * so the next copy attempt can either skip them (if whole) or overwrite.
     *
     * Safety: skip if the original name already exists in [currentNames]
     * (avoid collision with legitimately-tracked pending files).
     */
    private fun recoverDiskPendingOrphans(
        resolver: ContentResolver,
        treeUri: Uri,
        currentNames: Set<String>,
    ): Set<String> {
        val recovered = mutableSetOf<String>()
        val pendingRegex = Regex("""^\.pending-\d+-(.+)$""")
        try {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
            val candidates = mutableListOf<Pair<String, String>>() // docId -> origName
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    val m = pendingRegex.matchEntire(name) ?: continue
                    val origName = m.groupValues[1]
                    if (origName in currentNames) continue  // collision risk, skip
                    candidates += c.getString(idCol) to origName
                }
            }
            for ((docId, origName) in candidates) {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                try {
                    val newUri = DocumentsContract.renameDocument(resolver, docUri, origName)
                    if (newUri != null) {
                        recovered += origName
                        Log.i(TAG, "recoverDiskPending: renamed stuck pending → $origName")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "recoverDiskPending: rename $origName failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recoverDiskPendingOrphans failed: ${t.message}")
        }
        if (recovered.isNotEmpty()) {
            Log.i(TAG, "recoverDiskPending: recovered ${recovered.size} stuck files")
        }
        return recovered
    }

    /**
     * Detect and delete orphan IS_PENDING=1 files owned by our app in the
     * destination. These are leftovers from cancelled copy sessions — the
     * MediaStore row was created but the transfer never completed.
     *
     * Protection: files whose names appear in [allManifestNames] are LEGITIMATE
     * pending-awaiting-fix-dates files from the previous completed copy.
     * Those are preserved.
     *
     * Returns the set of names we deleted (so callers can exclude them from
     * existingNames and re-copy).
     */
    private fun cleanupOrphanPending(
        resolver: ContentResolver,
        destinationTreeUri: Uri,
        allManifestNames: Set<String>,
    ): Set<String> {
        val mediaStoreRelPath = mediaStoreRelativePath(destinationTreeUri) ?: return emptySet()
        val removed = mutableSetOf<String>()
        val collections = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        )
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
        )
        val selection = "${MediaStore.MediaColumns.IS_PENDING}=1 AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(context.packageName, mediaStoreRelPath)

        val queryBundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }

        for (collection in collections) {
            try {
                resolver.query(collection, projection, queryBundle, null)?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        val name = c.getString(nameCol) ?: continue
                        if (name in allManifestNames) continue  // legitimate, skip
                        val uri = ContentUris.withAppendedId(collection, id)
                        try {
                            val rows = resolver.delete(uri, null, null)
                            if (rows > 0) {
                                removed += name
                                Log.i(TAG, "cleanupOrphan: deleted partial $name (id=$id)")
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "cleanupOrphan: delete $name failed: ${t.message}")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cleanupOrphan query failed: ${t.message}")
            }
        }
        if (removed.isNotEmpty()) {
            Log.i(TAG, "cleanupOrphan: removed ${removed.size} orphans")
        }
        return removed
    }

    /**
     * Enumerate destination file names to suppress duplicate copies.
     *
     * CRITICAL: When a MediaStore row has IS_PENDING=1, MediaProvider
     * renames the underlying file on disk to `.pending-<timestamp>-<name>`.
     * A pure SAF tree query (backed by ExternalStorageProvider → raw
     * filesystem) returns the `.pending-*` disk names, NOT the original
     * DISPLAY_NAMEs. This caused ALL-mode to re-copy every pending file
     * on repeated runs (since `NZF_4393.NEF` wasn't in existingNames —
     * only `.pending-TIMESTAMP-NZF_4393.NEF` was).
     *
     * Fix: also query MediaStore by RELATIVE_PATH (including pending
     * rows) to get the true DISPLAY_NAMEs. The union covers both
     * SAF-created files (non-MediaStore) and pending MediaStore files.
     */
    private fun readDestinationNames(resolver: ContentResolver, treeUri: Uri): Set<String> {
        val names = HashSet<String>()

        // 1) SAF tree query — disk filenames (may include .pending-* for our own pending files
        //    and original names for everything else including SAF-created non-MediaStore files)
        try {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    names += c.getString(nameCol) ?: continue
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "无法读取保存目录列表 (SAF)：${t.message}")
        }

        // 2) MediaStore RELATIVE_PATH query — DISPLAY_NAMEs of all our rows
        //    including IS_PENDING=1. This is what makes the ALL-mode skip
        //    logic correct for files in MediaProvider's `.pending-*` disk state.
        val relPath = mediaStoreRelativePath(treeUri)
        if (relPath != null) {
            val collections = listOf(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            )
            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
            val queryBundle = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(relPath))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            for (collection in collections) {
                try {
                    resolver.query(collection, projection, queryBundle, null)?.use { c ->
                        val col = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        while (c.moveToNext()) {
                            names += c.getString(col) ?: continue
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "MediaStore DISPLAY_NAME 查询失败: ${t.message}")
                }
            }
        }

        return names
    }

    private fun guessMime(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "nef" -> "image/x-nikon-nef"
            "arw" -> "image/x-sony-arw"
            "cr2" -> "image/x-canon-cr2"
            "cr3" -> "image/x-canon-cr3"
            "raf" -> "image/x-fuji-raf"
            "rw2" -> "image/x-panasonic-rw2"
            "dng" -> "image/x-adobe-dng"
            "jpg", "jpeg" -> "image/jpeg"
            "heif", "heic", "hif" -> "image/heif"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            else -> "application/octet-stream"
        }
}

/**
 * Sets a SAF-backed file's modification time via the hidden `Os.utimensat` syscall
 * wrapper, dispatched by reflection through `setHiddenApiExemptions`.
 *
 * Why this is hairy: as of Android 11+, `Os.utimensat` is `@hide` (not in the public
 * SDK), and reflection on hidden APIs is blocked since Android 9. The standard
 * workaround is to call `VMRuntime.setHiddenApiExemptions("L")` once at startup,
 * which whitelists all Java packages for our process. This works through Android 14;
 * if Android disables it in a future release, we cleanly fall back to no-op (the
 * MediaStore.DATE_TAKEN path still gives correct gallery sort).
 *
 * The path argument uses `/proc/self/fd/N` so the syscall works on files we don't
 * directly own on disk (SAF-created files under /storage/emulated/0/Pictures/... are
 * owned by media_rw, not by our app).
 */
private object MtimeUtil {
    private const val TAG = "MtimeUtil"

    @Volatile private var initialized = false
    private var utimensatMethod: Method? = null
    private var tsCtor: Constructor<*>? = null
    private var tsClass: Class<*>? = null

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try { bypassHiddenApi() } catch (t: Throwable) { Log.w(TAG, "bypass failed: ${t.message}") }
            try {
                val osClass = Class.forName("android.system.Os")
                val tsCl = Class.forName("android.system.StructTimespec")
                tsClass = tsCl
                tsCtor = tsCl.getConstructor(java.lang.Long.TYPE, java.lang.Long.TYPE)
                val tsArrCl = java.lang.reflect.Array.newInstance(tsCl, 0).javaClass
                utimensatMethod = osClass.getDeclaredMethod(
                    "utimensat",
                    java.lang.Integer.TYPE,
                    String::class.java,
                    tsArrCl,
                    java.lang.Integer.TYPE,
                ).also { it.isAccessible = true }
                Log.i(TAG, "utimensat reflection ready")
            } catch (t: Throwable) {
                Log.w(TAG, "utimensat init failed: ${t.javaClass.simpleName}: ${t.message}")
            }
            initialized = true
        }
    }

    private fun bypassHiddenApi() {
        val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
        val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
        val runtime = getRuntime.invoke(null)
        val setExemptions = vmRuntimeClass.getDeclaredMethod(
            "setHiddenApiExemptions",
            Array<String>::class.java,
        )
        setExemptions.invoke(runtime, arrayOf("L"))
    }

    fun setMtime(resolver: ContentResolver, uri: Uri, epochMillis: Long): Boolean {
        if (epochMillis <= 0) return false

        // --- Path 1: JNI futimens (works on any Android, no hidden API) ---
        if (NativeMtime.available) {
            try {
                val pfd = resolver.openFileDescriptor(uri, "rw")
                    ?: resolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    val ok = pfd.use { NativeMtime.setMtimeByFd(it.fd, epochMillis) }
                    Log.i(TAG, "setMtime JNI ${if (ok) "VERIFIED" else "MISMATCH"}: target=${epochMillis / 1000}s uri=$uri")
                    if (ok) return true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "setMtime JNI failed: ${t.message}")
            }
        }

        // --- Path 2: reflection fallback (older Androids where hidden API works) ---
        ensureInit()
        val method = utimensatMethod ?: run {
            Log.w(TAG, "setMtime: utimensat method unavailable (init failed)")
            return false
        }
        val ctor = tsCtor ?: return false
        val cls = tsClass ?: return false
        return try {
            val sec = epochMillis / 1000
            val nsec = (epochMillis % 1000) * 1_000_000
            val ts = ctor.newInstance(sec, nsec)
            val arr = java.lang.reflect.Array.newInstance(cls, 2)
            java.lang.reflect.Array.set(arr, 0, ts)
            java.lang.reflect.Array.set(arr, 1, ts)

            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: resolver.openFileDescriptor(uri, "r")
                ?: run {
                    Log.w(TAG, "setMtime: openFileDescriptor returned null for $uri")
                    return false
                }
            pfd.use {
                method.invoke(null, /* AT_FDCWD = */ -100, "/proc/self/fd/${it.fd}", arr, 0)
                try {
                    val stat = Os.fstat(it.fileDescriptor)
                    val actualSec = stat.st_mtime
                    val ok = actualSec == sec
                    Log.i(TAG, "setMtime reflection ${if (ok) "VERIFIED" else "MISMATCH"}: target=${sec}s actual=${actualSec}s uri=$uri")
                    return ok
                } catch (t: Throwable) {
                    Log.w(TAG, "setMtime verify (fstat) failed: ${t.message}")
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setMtime reflection failed for $uri: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }
}
