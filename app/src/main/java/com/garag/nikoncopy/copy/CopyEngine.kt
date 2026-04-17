package com.garag.nikoncopy.copy

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.FileUtils
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

private val EXTENSIONS = setOf("nef", "jpg", "jpeg", "mp4")
private val EXIF_EXTS = setOf("nef", "jpg", "jpeg")
private val MEDIASTORE_ROOTS = setOf("Pictures", "DCIM", "Movies")

private data class SourceFile(
    val docUri: Uri,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?,
)

private data class EnrichTask(val src: SourceFile, val dstUri: Uri, val isMediaStore: Boolean)

class CopyEngine(
    private val context: Context,
    private val manifest: ManifestStore,
) {

    fun copyFlow(
        sourceTreeUri: Uri,
        destinationTreeUri: Uri,
        mode: CopyMode,
    ): Flow<CopyState> = channelFlow {
        send(CopyState.Scanning(0))
        val resolver = context.contentResolver

        val existingNames = readDestinationNames(resolver, destinationTreeUri)
        val manifestNames = if (mode == CopyMode.INCREMENTAL) manifest.loadAll() else emptySet()
        Log.i(TAG, "mode=$mode existingNames=${existingNames.size} manifestNames=${manifestNames.size}")

        // Walk source tree.
        val candidates = mutableListOf<SourceFile>()
        try {
            walkTree(resolver, sourceTreeUri) { f ->
                val ext = f.name.substringAfterLast('.', "").lowercase()
                if (ext in EXTENSIONS) candidates += f
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "Scan failed", t)
            send(CopyState.Failed("扫描失败: ${t.message ?: t.javaClass.simpleName}"))
            return@channelFlow
        }

        val (toCopy, toSkip) = candidates.partition { f ->
            when (mode) {
                CopyMode.ALL -> f.name !in existingNames
                CopyMode.INCREMENTAL -> f.name !in existingNames && f.name !in manifestNames
            }
        }
        send(CopyState.Scanning(candidates.size))
        Log.i(TAG, "candidates=${candidates.size} toCopy=${toCopy.size} toSkip=${toSkip.size}")

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

        val perFileBytes = AtomicLong(0)         // bytes copied within current file
        val priorTotalBytes = AtomicLong(0)      // bytes from already-completed files
        val currentIndex = AtomicInteger(0)
        val currentName = AtomicReference("(scanning)")

        // Periodic progress emitter — runs concurrently with the per-file copies and
        // pulls progress out of the atomics every 200 ms.
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
                    ))
                }
            } catch (_: CancellationException) {
                // expected on shutdown
            }
        }

        val session = manifest.openAppendSession()
        val destDir = DocumentsContract.buildDocumentUriUsingTree(
            destinationTreeUri,
            DocumentsContract.getTreeDocumentId(destinationTreeUri),
        )
        // If destination is under Pictures/DCIM/Movies, prefer MediaStore.insert so
        // we are recorded as the row's owner. That removes the SecurityException
        // we'd otherwise hit on DATE_TAKEN updates — and DATE_TAKEN is the only
        // way Xiaomi 相册 sorts NEF correctly (its native MediaScanner can't read
        // NEF EXIF, so the column otherwise stays NULL forever).
        val mediaStoreRelPath = mediaStoreRelativePath(destinationTreeUri)
        Log.i(TAG, "destination MediaStore relPath=$mediaStoreRelPath (null = SAF-only)")

        try {
            for ((idx, src) in toCopy.withIndex()) {
                currentIndex.set(idx + 1)
                currentName.set(src.name)
                perFileBytes.set(0)

                val mime = src.mimeType ?: guessMime(src.name)
                val (newDoc, isMediaStore) = try {
                    createDestinationUri(resolver, destDir, mediaStoreRelPath, src.name, mime)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.e(TAG, "createDocument failed for ${src.name}", t)
                    emitterJob.cancelAndJoin()
                    send(CopyState.Failed("创建文件失败 (${src.name}): ${t.message ?: t.javaClass.simpleName}"))
                    return@channelFlow
                }

                val fileStartNanos = System.nanoTime()
                val copiedBytes = try {
                    withContext(Dispatchers.IO) {
                        copyOneViaFileUtils(resolver, src.docUri, newDoc, src.name) { progress ->
                            perFileBytes.set(progress)
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.e(TAG, "Copy failed for ${src.name}", t)
                    runCatching {
                        if (isMediaStore) resolver.delete(newDoc, null, null)
                        else DocumentsContract.deleteDocument(resolver, newDoc)
                    }
                    emitterJob.cancelAndJoin()
                    send(CopyState.Failed("拷贝失败 (${src.name}): ${t.message ?: t.javaClass.simpleName}"))
                    return@channelFlow
                }
                val fileDurationMs = (System.nanoTime() - fileStartNanos) / 1_000_000L
                val fileRateMBs = if (fileDurationMs > 0) copiedBytes / 1024.0 / 1024.0 / (fileDurationMs / 1000.0) else 0.0
                Log.i(TAG, "copied ${src.name} ${copiedBytes}B in ${fileDurationMs}ms = %.1f MB/s ${if (isMediaStore) "[mediastore]" else "[saf]"}".format(fileRateMBs))

                priorTotalBytes.addAndGet(copiedBytes)
                perFileBytes.set(0)

                // Capture time → DATE_TAKEN (and mtime if utimensat is available).
                enrichDestination(resolver, src, newDoc, isMediaStore)

                // Publish the MediaStore entry now that the bytes are written.
                if (isMediaStore) {
                    runCatching {
                        val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        resolver.update(newDoc, v, null, null)
                    }.onFailure { Log.w(TAG, "IS_PENDING=0 failed for ${src.name}: ${it.message}") }
                }

                session.add(src.name)
            }
        } finally {
            session.close()
            emitterJob.cancelAndJoin()
        }

        val elapsed = (System.nanoTime() - startNanos) / 1_000_000L
        val totalBytes = priorTotalBytes.get()
        val avgMBs = if (elapsed > 0) totalBytes / 1024.0 / 1024.0 / (elapsed / 1000.0) else 0.0
        Log.i(TAG, "Done: ${toCopy.size} files, $totalBytes B in ${elapsed}ms = %.1f MB/s avg".format(avgMBs))

        send(CopyState.Done(
            filesCopied = toCopy.size,
            filesSkipped = toSkip.size,
            totalBytes = totalBytes,
            elapsedMillis = elapsed,
        ))
    }.flowOn(Dispatchers.IO)

    /**
     * Direct-mode copy flow: source is a [PtpClient] holding the Nikon's USB
     * interface 0 directly (bypasses `com.android.mtp` entirely). Same destination
     * + manifest + EXIF/DATE_TAKEN logic as [copyFlow]; only the byte-pumping side
     * is different.
     *
     * Caller is responsible for opening the PtpClient (USB perm, claim) and closing
     * it after the flow completes. We do NOT close it here so the caller can decide
     * lifetime (e.g. keep open across multiple back-to-back copies).
     */
    fun copyFlowDirect(
        ptp: PtpClient,
        destinationTreeUri: Uri,
        mode: CopyMode,
    ): Flow<CopyState> = channelFlow {
        send(CopyState.Scanning(0))
        val resolver = context.contentResolver

        val existingNames = readDestinationNames(resolver, destinationTreeUri)
        val manifestNames = if (mode == CopyMode.INCREMENTAL) manifest.loadAll() else emptySet()
        Log.i(TAG, "[direct] mode=$mode existingNames=${existingNames.size} manifestNames=${manifestNames.size}")

        val candidates = try {
            ptp.listFiles()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "[direct] PTP listFiles failed", t)
            send(CopyState.Failed("PTP 列表失败: ${t.message ?: t.javaClass.simpleName}"))
            return@channelFlow
        }

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
                    ))
                }
            } catch (_: CancellationException) {}
        }

        val session = manifest.openAppendSession()
        val destDir = DocumentsContract.buildDocumentUriUsingTree(
            destinationTreeUri,
            DocumentsContract.getTreeDocumentId(destinationTreeUri),
        )

        // Pipeline: producer (this coroutine) does sequential PTP transfers and
        // hands the destination off to an enricher coroutine. EXIF parse + mtime +
        // MediaStore DATE_TAKEN can take 100-500 ms per NEF — running them on the
        // PTP critical path serialises them with USB transfers, leaving the camera
        // idle. With a dedicated consumer, the next file's PTP transfer overlaps
        // with the previous file's enrichment.
        val mediaStoreRelPath = mediaStoreRelativePath(destinationTreeUri)
        Log.i(TAG, "[direct] destination MediaStore relPath=$mediaStoreRelPath")

        val enrichQueue = Channel<EnrichTask>(capacity = Channel.UNLIMITED)
        val enricherJob = launch(Dispatchers.IO) {
            for (task in enrichQueue) {
                try {
                    enrichDestination(resolver, task.src, task.dstUri, task.isMediaStore)
                    if (task.isMediaStore) {
                        runCatching {
                            val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                            resolver.update(task.dstUri, v, null, null)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "enrich failed for ${task.src.name}: ${t.message}")
                }
            }
        }

        try {
            for ((idx, mtpFile) in toCopy.withIndex()) {
                currentIndex.set(idx + 1)
                currentName.set(mtpFile.name)
                perFileBytes.set(0)

                val mime = guessMime(mtpFile.name)
                val (newDoc, isMediaStore) = try {
                    createDestinationUri(resolver, destDir, mediaStoreRelPath, mtpFile.name, mime)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.e(TAG, "[direct] createDocument failed for ${mtpFile.name}", t)
                    enrichQueue.close()
                    enricherJob.join()
                    emitterJob.cancelAndJoin()
                    send(CopyState.Failed("创建文件失败 (${mtpFile.name}): ${t.message}"))
                    return@channelFlow
                }

                val fileStartNanos = System.nanoTime()
                val copiedBytes = try {
                    withContext(Dispatchers.IO) {
                        copyOneViaPtp(ptp, mtpFile, newDoc) { bytes ->
                            perFileBytes.set(bytes)
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.e(TAG, "[direct] PTP copy failed for ${mtpFile.name}", t)
                    runCatching {
                        if (isMediaStore) resolver.delete(newDoc, null, null)
                        else DocumentsContract.deleteDocument(resolver, newDoc)
                    }
                    enrichQueue.close()
                    enricherJob.join()
                    emitterJob.cancelAndJoin()
                    send(CopyState.Failed("PTP 拷贝失败 (${mtpFile.name}): ${t.message}"))
                    return@channelFlow
                }
                val fileMs = (System.nanoTime() - fileStartNanos) / 1_000_000L
                val fileMBs = if (fileMs > 0) copiedBytes / 1024.0 / 1024.0 / (fileMs / 1000.0) else 0.0
                Log.i(TAG, "[direct] PTP ${mtpFile.name} ${copiedBytes}B in ${fileMs}ms = %.1f MB/s ${if (isMediaStore) "[mediastore]" else "[saf]"}".format(fileMBs))

                priorTotalBytes.addAndGet(copiedBytes)
                perFileBytes.set(0)

                // Mark as successfully copied IMMEDIATELY (file is on disk; manifest
                // tracks PTP success, not enrichment). Then hand off enrichment to
                // the consumer; producer can start next PTP transfer right away.
                session.add(mtpFile.name)
                val pseudoSrc = SourceFile(
                    docUri = Uri.EMPTY,
                    name = mtpFile.name,
                    size = mtpFile.size,
                    lastModified = mtpFile.dateModifiedMillis,
                    mimeType = mime,
                )
                enrichQueue.send(EnrichTask(pseudoSrc, newDoc, isMediaStore))
            }
        } finally {
            // Stop accepting new tasks; wait for in-flight enrichments to drain so
            // DATE_TAKEN/mtime are written before we tell the user "done".
            enrichQueue.close()
            try { enricherJob.join() } catch (_: Throwable) {}
            session.close()
            emitterJob.cancelAndJoin()
        }

        val elapsed = (System.nanoTime() - startNanos) / 1_000_000L
        val totalBytes = priorTotalBytes.get()
        val avgMBs = if (elapsed > 0) totalBytes / 1024.0 / 1024.0 / (elapsed / 1000.0) else 0.0
        Log.i(TAG, "[direct] Done: ${toCopy.size} files, $totalBytes B in ${elapsed}ms = %.1f MB/s avg".format(avgMBs))

        send(CopyState.Done(
            filesCopied = toCopy.size,
            filesSkipped = toSkip.size,
            totalBytes = totalBytes,
            elapsedMillis = elapsed,
        ))
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

    /**
     * Use [FileUtils.copy] for the actual transfer — Android's implementation calls
     * splice(2) when the source is a regular fd (zero-copy in kernel) and falls back
     * to a tuned read/write loop. Returns total bytes copied.
     *
     * The progress listener fires at internal checkpoints (typically every 64 KiB
     * for the read/write fallback). Listener is invoked inline on the calling thread
     * since we pass null Executor — it just updates an AtomicLong.
     */
    private fun copyOneViaFileUtils(
        resolver: ContentResolver,
        srcUri: Uri,
        dstUri: Uri,
        fileName: String,
        onProgress: (Long) -> Unit,
    ): Long {
        val srcPfd = resolver.openFileDescriptor(srcUri, "r")
            ?: throw IOException("openSrc null: $fileName")
        return srcPfd.use { src ->
            val dstPfd = resolver.openFileDescriptor(dstUri, "w")
                ?: throw IOException("openDst null: $fileName")
            dstPfd.use { dst ->
                FileUtils.copy(
                    src.fileDescriptor,
                    dst.fileDescriptor,
                    null, // CancellationSignal — we rely on coroutine cancellation
                    null, // Executor — listener fires inline (we just update an atomic)
                ) { progress -> onProgress(progress) }
            }
        }
    }

    /**
     * Three-step post-copy enrichment so gallery apps sort by capture time:
     *
     *  1. Pull EXIF DateTimeOriginal from the destination file. androidx.exifinterface
     *     handles NEF (TIFF-based RAW) which the system MediaScanner can't parse.
     *  2. Set destination mtime to that time via MtimeUtil — covers gallery apps that
     *     fall back to file mtime when DATE_TAKEN is missing.
     *  3. Trigger MediaScanner; on its callback, write DATE_TAKEN into MediaStore via
     *     ContentResolver.update(). For JPG this is redundant (MediaScanner already
     *     extracted it); for NEF this is the *primary* mechanism that makes Xiaomi
     *     Gallery's "by capture time" sort work.
     */
    private fun enrichDestination(
        resolver: ContentResolver,
        src: SourceFile,
        dstUri: Uri,
        isMediaStore: Boolean,
    ) {
        val ext = src.name.substringAfterLast('.', "").lowercase()

        val tExifStart = System.nanoTime()
        val captureMillis = if (ext in EXIF_EXTS) {
            val exifMs = extractExifCaptureMillis(resolver, dstUri)
            if (exifMs > 0) exifMs else src.lastModified
        } else {
            src.lastModified
        }
        val tExifMs = (System.nanoTime() - tExifStart) / 1_000_000L

        val tMtimeStart = System.nanoTime()
        val mtimeOk = if (captureMillis > 0) MtimeUtil.setMtime(resolver, dstUri, captureMillis) else false
        val tMtimeMs = (System.nanoTime() - tMtimeStart) / 1_000_000L

        Log.i(TAG, "enrich ${src.name}: capture=$captureMillis exifT=${tExifMs}ms mtimeOK=$mtimeOk mtimeT=${tMtimeMs}ms isMediaStore=$isMediaStore")

        if (captureMillis <= 0) return

        if (isMediaStore) {
            // We own this row (we did the insert); update DATE_TAKEN directly.
            // No SecurityException risk, no MediaScanner round-trip needed.
            updateAndVerifyDateTaken(resolver, dstUri, captureMillis, src.name, scanT = 0)
            return
        }

        // SAF-created file: we don't own its MediaStore row. Best-effort: trigger
        // a scan and try to update on the resulting row (will throw SecurityException
        // on Android 11+ if we're not the owner, but JPG works anyway because
        // MediaScanner extracts DATE_TAKEN from JPG EXIF natively).
        val docId = try { DocumentsContract.getDocumentId(dstUri) } catch (_: Throwable) { return }
        if (!docId.startsWith("primary:")) return
        val path = "/storage/emulated/0/" + docId.removePrefix("primary:")

        try {
            val tScanStart = System.nanoTime()
            MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, scannedUri ->
                val tScanMs = (System.nanoTime() - tScanStart) / 1_000_000L
                val mediaUri = scannedUri ?: queryMediaUriByPath(resolver, path)
                if (mediaUri == null) {
                    Log.w(TAG, "no MediaStore URI for ${src.name} (scanT=${tScanMs}ms)")
                    return@scanFile
                }
                val ok = updateAndVerifyDateTaken(resolver, mediaUri, captureMillis, src.name, scanT = tScanMs)
                if (!ok) {
                    val byPath = queryMediaUriByPath(resolver, path)
                    if (byPath != null && byPath != mediaUri) {
                        updateAndVerifyDateTaken(resolver, byPath, captureMillis, src.name, scanT = tScanMs)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "media scan failed for ${src.name}: ${t.message}")
        }
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

    /**
     * Write DATE_TAKEN + DATE_MODIFIED to a MediaStore row and immediately re-read
     * to verify persistence. Some providers (especially for less-common MIME types)
     * silently drop updates; the read-back tells us so we can try the other URI.
     */
    private fun updateAndVerifyDateTaken(
        resolver: ContentResolver,
        mediaUri: Uri,
        captureMillis: Long,
        name: String,
        scanT: Long,
    ): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATE_TAKEN, captureMillis)
            put(MediaStore.MediaColumns.DATE_MODIFIED, captureMillis / 1000)
        }
        val rows = try {
            resolver.update(mediaUri, values, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "DATE_TAKEN update threw for $name uri=$mediaUri: ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
        // Read back to see what actually persisted.
        val readBack = try {
            resolver.query(mediaUri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        } catch (t: Throwable) {
            Log.w(TAG, "DATE_TAKEN readback threw: ${t.message}")
            -2L
        }
        val verified = readBack == captureMillis
        Log.i(TAG, "DATE_TAKEN $name uri=$mediaUri rows=$rows scanT=${scanT}ms wrote=$captureMillis read=$readBack verify=${if (verified) "OK" else "FAIL"}")
        runCatching { resolver.notifyChange(mediaUri, null) }
        return verified
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

    private fun walkTree(
        resolver: ContentResolver,
        treeUri: Uri,
        onFile: (SourceFile) -> Unit,
    ) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val stack = ArrayDeque<String>()
        stack.addLast(rootDocId)

        while (stack.isNotEmpty()) {
            val parentId = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            queryChildren(resolver, treeUri, childrenUri) { entry ->
                if (entry.isDir) {
                    stack.addLast(entry.documentId)
                } else {
                    onFile(
                        SourceFile(
                            docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId),
                            name = entry.name,
                            size = entry.size,
                            lastModified = entry.lastModified,
                            mimeType = entry.mimeType,
                        )
                    )
                }
            }
        }
    }

    private data class Entry(
        val documentId: String,
        val name: String,
        val size: Long,
        val lastModified: Long,
        val mimeType: String?,
        val isDir: Boolean,
    )

    private fun queryChildren(
        resolver: ContentResolver,
        @Suppress("UNUSED_PARAMETER") treeUri: Uri,
        childrenUri: Uri,
        block: (Entry) -> Unit,
    ) {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(childrenUri, projection, null, null, null)
            if (cursor == null) return
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val mtimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeCol)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                block(
                    Entry(
                        documentId = cursor.getString(idCol),
                        name = cursor.getString(nameCol) ?: "(unnamed)",
                        size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L,
                        lastModified = if (mtimeCol >= 0 && !cursor.isNull(mtimeCol)) cursor.getLong(mtimeCol) else 0L,
                        mimeType = mime,
                        isDir = isDir,
                    )
                )
            }
        } finally {
            cursor?.close()
        }
    }

    private fun readDestinationNames(resolver: ContentResolver, treeUri: Uri): Set<String> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        val names = HashSet<String>()
        var c: Cursor? = null
        try {
            c = resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )
            if (c != null) {
                val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    names += c.getString(nameCol) ?: continue
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "无法读取目标目录列表，跳过去重: ${t.message}")
        } finally {
            c?.close()
        }
        return names
    }

    private fun guessMime(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "nef" -> "image/x-nikon-nef"
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
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
                // Verify by reading mtime back via the public Os.fstat API. If the
                // syscall silently failed (e.g. EPERM on a media_rw-owned file under
                // scoped storage), this comparison reveals it.
                try {
                    val stat = Os.fstat(it.fileDescriptor)
                    val actualSec = stat.st_mtime
                    val ok = actualSec == sec
                    Log.i(TAG, "setMtime ${if (ok) "VERIFIED" else "MISMATCH"}: target=${sec}s actual=${actualSec}s uri=$uri")
                    return ok
                } catch (t: Throwable) {
                    Log.w(TAG, "setMtime verify (fstat) failed: ${t.message}")
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setMtime failed for $uri: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }
}
