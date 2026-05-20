package com.garag.nikoncopy.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

private const val TAG = "MediaProbe"

/**
 * Comprehensive set of media file extensions we recognise as importable. Used
 * by [MediaProbe] to classify discovered files, NOT to find them — file
 * discovery is driven by directory walking, not extension globbing.
 *
 * RAW stills:
 *   NEF / NRW (Nikon), ARW / SRF / SR2 (Sony), CR2 / CR3 / CRW (Canon),
 *   RAF (Fuji), RW2 (Panasonic), DNG (Adobe / multi-vendor), ORF (Olympus),
 *   PEF / PTX (Pentax), SRW (Samsung), 3FR (Hasselblad), IIQ (Phase One),
 *   MEF (Mamiya), MOS (Leaf), X3F (Sigma), RWL / KDC (Leica / Kodak).
 * Compressed stills:
 *   JPG / JPEG / JPE, HEIC / HEIF / HIF (Nikon's HEIF), AVIF, TIFF / TIF.
 * Video:
 *   MP4 / MOV / M4V (universal), MTS / M2TS (AVCHD), MXF (broadcast),
 *   AVI / MKV / WMV / FLV (consumer), 3GP, HEVC, WEBM, BRAW (Blackmagic),
 *   R3D (RED), ARI (ARRI).
 */
val KNOWN_MEDIA_EXTS: Set<String> = setOf(
    // RAW stills
    "nef", "nrw", "arw", "srf", "sr2", "cr2", "cr3", "crw",
    "raf", "rw2", "dng", "orf", "pef", "ptx", "srw",
    "3fr", "iiq", "mef", "mos", "x3f", "rwl", "kdc",
    // Compressed stills
    "jpg", "jpeg", "jpe", "heic", "heif", "hif", "avif", "tiff", "tif",
    // Video
    "mp4", "mov", "m4v", "avi", "mkv", "mts", "m2ts", "mxf",
    "3gp", "hevc", "webm", "wmv", "flv",
    "braw", "r3d", "ari",
)

/** Camera-conventional top-level directory names (DCF spec & vendor folders). */
private val CAMERA_DIRS_UPPER = setOf(
    "DCIM", "PRIVATE", "MISC", "MP_ROOT", "AVCHD", "CLIP",
)

/** Human-curated top-level directory names (phone exports, manual organisation). */
private val USER_DIRS_UPPER = setOf(
    "PICTURES", "PHOTOS", "MOVIES", "VIDEOS",
    "CAMERA ROLL", "CAMERA", "DCF",
)

/** Always-skip directories at every level. Don't waste time enumerating these. */
private val SKIP_DIRS = setOf(
    "Android", "LOST.DIR", "System Volume Information",
    "\$RECYCLE.BIN", "RECYCLER",
    ".Trash", ".Trashes", ".Spotlight-V100", ".fseventsd",
    ".TemporaryItems", ".thumbnails",
)

data class DirHit(
    /** Path relative to the scan root, "/" separated. Empty string for root itself. */
    val path: String,
    val fileCount: Int,
    /** Extension (lowercase, no dot) → file count. */
    val extensionCounts: Map<String, Int>,
)

data class MediaProbeResult(
    val directories: List<DirHit>,
    val totalFilesScanned: Int,
    val totalDirsScanned: Int,
    val elapsedMs: Long,
    /** True iff the scan returned via the fast convention probe. */
    val usedConventionProbe: Boolean,
    /** True iff scan stopped early due to a file/dir/time limit. */
    val bailedOnLimit: Boolean,
) {
    val totalMediaFiles: Int get() = directories.sumOf { it.fileCount }
    val allExtensions: Set<String> get() = directories.flatMap { it.extensionCounts.keys }.toSet()
    val allDirectories: Set<String> get() = directories.map { it.path }.toSet()
}

/**
 * Discover importable media inside a SAF tree (e.g. a mounted SD/USB volume).
 *
 * Strategy:
 *   1. Enumerate the tree root and look for conventional camera/user directory
 *      names (`DCIM`, `PRIVATE`, `Pictures`, etc.). If any are found, recurse
 *      into those only — this is the *fast path* for cameras and SD cards,
 *      finishing in seconds.
 *   2. If nothing matches the convention (the device has an ad-hoc layout),
 *      fall back to a bounded breadth-first walk over the entire tree, capped
 *      at [maxFiles] media files, [maxDirs] directories, or [maxMs] wallclock.
 *      The first limit hit short-circuits the scan; [MediaProbeResult.bailedOnLimit]
 *      tells the caller whether the result is partial.
 *
 * Directories named in [SKIP_DIRS] or starting with `.` are skipped at every
 * level so we never blow time on Android system folders, recycle bins, or
 * macOS metadata.
 *
 * Aggregates results into one [DirHit] per parent directory of media.
 */
object MediaProbe {

    private const val DEFAULT_MAX_FILES = 8000
    private const val DEFAULT_MAX_DIRS = 200
    private const val DEFAULT_MAX_MS = 15_000L

    suspend fun probeSaf(
        resolver: ContentResolver,
        treeUri: Uri,
        maxFiles: Int = DEFAULT_MAX_FILES,
        maxDirs: Int = DEFAULT_MAX_DIRS,
        maxMs: Long = DEFAULT_MAX_MS,
    ): MediaProbeResult {
        val started = System.currentTimeMillis()
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val limits = Limits(maxFiles, maxDirs, started + maxMs)

        val rootChildren = listChildren(resolver, treeUri, rootDocId)
        val rootMatchedDirs = rootChildren.filter { entry ->
            entry.isDir && entry.name.notSkippable() && entry.name.matchesConvention()
        }

        val mediaByParent = mutableMapOf<String, MutableMap<String, Int>>()
        var filesScanned = 0
        var dirsScanned = 1

        if (rootMatchedDirs.isNotEmpty()) {
            for (start in rootMatchedDirs) {
                if (limits.exhausted()) break
                val (f, d) = walkSubtree(
                    resolver, treeUri, start.documentId, start.name, mediaByParent, limits,
                )
                filesScanned += f
                dirsScanned += d
            }
            // Also pick up any stray media sitting at the root (e.g. a flat USB stick).
            for (entry in rootChildren) {
                if (limits.exhausted()) break
                if (entry.isDir) continue
                recordMedia(entry.name, parentPath = "", mediaByParent)?.let { filesScanned++ }
            }
            return assemble(mediaByParent, filesScanned, dirsScanned, started, usedConvention = true, bailed = limits.bailed)
        }

        // No convention match → bounded BFS from root.
        for (entry in rootChildren) {
            if (limits.exhausted()) break
            if (entry.isDir) {
                if (!entry.name.notSkippable()) continue
                val (f, d) = walkSubtree(
                    resolver, treeUri, entry.documentId, entry.name, mediaByParent, limits,
                )
                filesScanned += f
                dirsScanned += d
            } else {
                recordMedia(entry.name, parentPath = "", mediaByParent)?.let { filesScanned++ }
            }
        }
        return assemble(mediaByParent, filesScanned, dirsScanned, started, usedConvention = false, bailed = limits.bailed)
    }

    private fun assemble(
        mediaByParent: Map<String, Map<String, Int>>,
        filesScanned: Int,
        dirsScanned: Int,
        startedAt: Long,
        usedConvention: Boolean,
        bailed: Boolean,
    ): MediaProbeResult {
        val hits = mediaByParent
            .map { (path, exts) -> DirHit(path, exts.values.sum(), exts) }
            .sortedByDescending { it.fileCount }
        val elapsed = System.currentTimeMillis() - startedAt
        Log.i(TAG, "probe done: dirs=$dirsScanned files=$filesScanned hits=${hits.size} convention=$usedConvention bailed=$bailed elapsed=${elapsed}ms")
        return MediaProbeResult(
            directories = hits,
            totalFilesScanned = filesScanned,
            totalDirsScanned = dirsScanned,
            elapsedMs = elapsed,
            usedConventionProbe = usedConvention,
            bailedOnLimit = bailed,
        )
    }

    /** Mutable budget; first limit reached aborts the scan. */
    private class Limits(val maxFiles: Int, val maxDirs: Int, val deadlineMs: Long) {
        var bailed: Boolean = false
        fun exhausted(): Boolean {
            if (System.currentTimeMillis() > deadlineMs) { bailed = true; return true }
            return false
        }
    }

    private fun walkSubtree(
        resolver: ContentResolver,
        treeUri: Uri,
        startDocId: String,
        startPath: String,
        mediaByParent: MutableMap<String, MutableMap<String, Int>>,
        limits: Limits,
    ): Pair<Int, Int> {
        var filesScanned = 0
        var dirsScanned = 0
        val queue: ArrayDeque<Pair<String, String>> = ArrayDeque()
        queue.addLast(startDocId to startPath)
        while (queue.isNotEmpty()) {
            if (limits.exhausted()) break
            val (docId, path) = queue.removeFirst()
            dirsScanned++
            for (entry in listChildren(resolver, treeUri, docId)) {
                if (limits.exhausted()) break
                if (entry.isDir) {
                    if (!entry.name.notSkippable()) continue
                    queue.addLast(entry.documentId to "$path/${entry.name}")
                } else {
                    val added = recordMedia(entry.name, parentPath = path, mediaByParent) != null
                    if (added) {
                        filesScanned++
                        if (filesScanned >= limits.maxFiles) {
                            limits.bailed = true
                            break
                        }
                    }
                }
            }
            if (dirsScanned >= limits.maxDirs) {
                limits.bailed = true
                break
            }
        }
        return filesScanned to dirsScanned
    }

    /** If [fileName]'s extension is in [KNOWN_MEDIA_EXTS], record it and return the ext. */
    private fun recordMedia(
        fileName: String,
        parentPath: String,
        mediaByParent: MutableMap<String, MutableMap<String, Int>>,
    ): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isBlank() || ext !in KNOWN_MEDIA_EXTS) return null
        mediaByParent.getOrPut(parentPath) { mutableMapOf() }
            .merge(ext, 1, Int::plus)
        return ext
    }

    private data class Child(val documentId: String, val name: String, val isDir: Boolean)

    private fun listChildren(resolver: ContentResolver, treeUri: Uri, parentDocId: String): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = ArrayList<Child>()
        try {
            resolver.query(childrenUri, proj, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    val mime = c.getString(mimeCol)
                    val name = c.getString(nameCol) ?: continue
                    out += Child(
                        documentId = c.getString(idCol),
                        name = name,
                        isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "listChildren($parentDocId) failed: ${t.message}")
        }
        return out
    }

    private fun String.matchesConvention(): Boolean {
        val u = this.uppercase()
        return u in CAMERA_DIRS_UPPER || u in USER_DIRS_UPPER
    }

    private fun String.notSkippable(): Boolean {
        if (startsWith(".")) return false
        return this !in SKIP_DIRS
    }
}
