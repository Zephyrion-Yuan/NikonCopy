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
    /** Distinct extensions we sampled in this directory. Counts intentionally drop:
     *  with the two-stage probe we record at most one example per (dir, ext) pair to
     *  keep coverage of the directory list complete even on volumes with 60+ subdirs. */
    val extensions: Set<String>,
    /** Max `COLUMN_LAST_MODIFIED` we saw across all media files inside this dir.
     *  Used by the settings UI to sort the source-subdir checkboxes newest-first
     *  (so the user's most recent shoot lands at the top of the list). 0 when
     *  the SAF provider didn't supply mtimes (rare; some camera providers omit
     *  it but standard SD/USB MSC always has it). */
    val lastModifiedAt: Long = 0L,
)

data class MediaProbeResult(
    val directories: List<DirHit>,
    val totalDirsScanned: Int,
    val elapsedMs: Long,
    /** True iff the scan returned via the fast convention probe. */
    val usedConventionProbe: Boolean,
    /** True iff scan stopped early due to a dir or time limit. */
    val bailedOnLimit: Boolean,
) {
    val allExtensions: Set<String> get() = directories.flatMap { it.extensions }.toSet()
    val allDirectories: Set<String> get() = directories.map { it.path }.toSet()
}

/**
 * Discover importable media inside a SAF tree (e.g. a mounted SD/USB volume).
 *
 * Two-stage strategy designed to be **complete on the directory list** even when
 * a volume has dozens of top-level subdirectories with thousands of files each:
 *
 *   1. Walk the tree depth-first, but for each (directory × extension) pair record
 *      AT MOST ONE example. The cursor still iterates a dir's children, but the
 *      `mediaByParent` map stops growing after we've seen each ext once per dir.
 *      This decouples the scan's cost from the file count — a 10k-file dir costs
 *      the same as a 10-file dir for our purposes.
 *   2. Enumerate conventional camera/user directory names at the root for a fast
 *      path; if no convention match, fall back to a bounded BFS over the whole
 *      tree (dir + wallclock limits only — no per-file limit, since files don't
 *      meaningfully accumulate state under the dedup rule).
 *
 * Directories named in [SKIP_DIRS] or starting with `.` are skipped at every
 * level so we never waste time on Android system folders, recycle bins, or
 * macOS metadata. Hidden files at the destination root (e.g. `.nikoncopy_index.json`)
 * are also ignored.
 */
object MediaProbe {

    /** Generous limits: dir/time only. With per-(dir,ext) dedup the file count is bounded
     *  by `dirs × known-exts`, so we don't need a separate file cap. */
    private const val DEFAULT_MAX_DIRS = 2000
    private const val DEFAULT_MAX_MS = 30_000L

    suspend fun probeSaf(
        resolver: ContentResolver,
        treeUri: Uri,
        maxDirs: Int = DEFAULT_MAX_DIRS,
        maxMs: Long = DEFAULT_MAX_MS,
    ): MediaProbeResult {
        val started = System.currentTimeMillis()
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val limits = Limits(maxDirs, started + maxMs)

        val rootChildren = listChildren(resolver, treeUri, rootDocId)
        val rootMatchedDirs = rootChildren.filter { entry ->
            entry.isDir && entry.name.notSkippable() && entry.name.matchesConvention()
        }

        val mediaByParent = mutableMapOf<String, MutableSet<String>>()
        val mtimeByParent = mutableMapOf<String, Long>()
        var dirsScanned = 1

        if (rootMatchedDirs.isNotEmpty()) {
            for (start in rootMatchedDirs) {
                if (limits.exhausted()) break
                dirsScanned += walkSubtree(
                    resolver, treeUri, start.documentId, start.name, mediaByParent, mtimeByParent, limits,
                )
            }
            // Also pick up any stray media sitting at the root (e.g. a flat USB stick).
            for (entry in rootChildren) {
                if (entry.isDir) continue
                recordMedia(entry.name, entry.lastModified, parentPath = "", mediaByParent, mtimeByParent)
            }
            return assemble(mediaByParent, mtimeByParent, dirsScanned, started, usedConvention = true, bailed = limits.bailed)
        }

        // No convention match → bounded BFS from root.
        for (entry in rootChildren) {
            if (limits.exhausted()) break
            if (entry.isDir) {
                if (!entry.name.notSkippable()) continue
                dirsScanned += walkSubtree(
                    resolver, treeUri, entry.documentId, entry.name, mediaByParent, mtimeByParent, limits,
                )
            } else {
                recordMedia(entry.name, entry.lastModified, parentPath = "", mediaByParent, mtimeByParent)
            }
        }
        return assemble(mediaByParent, mtimeByParent, dirsScanned, started, usedConvention = false, bailed = limits.bailed)
    }

    private fun assemble(
        mediaByParent: Map<String, Set<String>>,
        mtimeByParent: Map<String, Long>,
        dirsScanned: Int,
        startedAt: Long,
        usedConvention: Boolean,
        bailed: Boolean,
    ): MediaProbeResult {
        val hits = mediaByParent
            .map { (path, exts) -> DirHit(path, exts.toSet(), mtimeByParent[path] ?: 0L) }
            .sortedByDescending { it.lastModifiedAt }
        val elapsed = System.currentTimeMillis() - startedAt
        Log.i(TAG, "probe done: dirs=$dirsScanned hits=${hits.size} exts=${hits.flatMap { it.extensions }.toSet().size} convention=$usedConvention bailed=$bailed elapsed=${elapsed}ms")
        return MediaProbeResult(
            directories = hits,
            totalDirsScanned = dirsScanned,
            elapsedMs = elapsed,
            usedConventionProbe = usedConvention,
            bailedOnLimit = bailed,
        )
    }

    /** Mutable budget; first limit reached aborts the scan. */
    private class Limits(val maxDirs: Int, val deadlineMs: Long) {
        var bailed: Boolean = false
        fun exhausted(): Boolean {
            if (System.currentTimeMillis() > deadlineMs) { bailed = true; return true }
            return false
        }
    }

    /** Returns the number of additional directories scanned. */
    private fun walkSubtree(
        resolver: ContentResolver,
        treeUri: Uri,
        startDocId: String,
        startPath: String,
        mediaByParent: MutableMap<String, MutableSet<String>>,
        mtimeByParent: MutableMap<String, Long>,
        limits: Limits,
    ): Int {
        var dirsScanned = 0
        val queue: ArrayDeque<Pair<String, String>> = ArrayDeque()
        queue.addLast(startDocId to startPath)
        while (queue.isNotEmpty()) {
            if (limits.exhausted()) break
            val (docId, path) = queue.removeFirst()
            dirsScanned++
            // Track which exts we've already recorded for this dir so we can stop
            // scanning its children once everything's been sampled. This is the key
            // dedup optimisation: a 10k-file folder costs the same as a 10-file one.
            //
            // We still let mtime updates pass through every file (no dedup) so the
            // dir's recorded mtime is the max across ALL its children, not just
            // the first-of-each-ext sample. That gives the settings UI an
            // accurate "newest shoot" timestamp for sorting.
            val recordedHere = mediaByParent[path] ?: mutableSetOf()
            for (entry in listChildren(resolver, treeUri, docId)) {
                if (limits.exhausted()) break
                if (entry.isDir) {
                    if (!entry.name.notSkippable()) continue
                    queue.addLast(entry.documentId to "$path/${entry.name}")
                } else {
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    if (ext.isBlank() || ext !in KNOWN_MEDIA_EXTS) continue
                    // mtime tracking is independent of ext dedup — always update.
                    if (entry.lastModified > (mtimeByParent[path] ?: 0L)) {
                        mtimeByParent[path] = entry.lastModified
                    }
                    if (ext in recordedHere) continue  // already sampled this ext in this dir
                    mediaByParent.getOrPut(path) { mutableSetOf() }.add(ext)
                    recordedHere.add(ext)
                }
            }
            if (dirsScanned + 1 > limits.maxDirs) {
                limits.bailed = true
                break
            }
        }
        return dirsScanned
    }

    /** If [fileName]'s extension is in [KNOWN_MEDIA_EXTS], record it. Dedup'd by (path, ext). */
    private fun recordMedia(
        fileName: String,
        lastModified: Long,
        parentPath: String,
        mediaByParent: MutableMap<String, MutableSet<String>>,
        mtimeByParent: MutableMap<String, Long>,
    ) {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isBlank() || ext !in KNOWN_MEDIA_EXTS) return
        mediaByParent.getOrPut(parentPath) { mutableSetOf() }.add(ext)
        if (lastModified > (mtimeByParent[parentPath] ?: 0L)) {
            mtimeByParent[parentPath] = lastModified
        }
    }

    private data class Child(
        val documentId: String,
        val name: String,
        val isDir: Boolean,
        val lastModified: Long,
    )

    private fun listChildren(resolver: ContentResolver, treeUri: Uri, parentDocId: String): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<Child>()
        try {
            resolver.query(childrenUri, proj, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val mtimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (c.moveToNext()) {
                    val mime = c.getString(mimeCol)
                    val name = c.getString(nameCol) ?: continue
                    out += Child(
                        documentId = c.getString(idCol),
                        name = name,
                        isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        lastModified = if (mtimeCol >= 0 && !c.isNull(mtimeCol)) c.getLong(mtimeCol) else 0L,
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
