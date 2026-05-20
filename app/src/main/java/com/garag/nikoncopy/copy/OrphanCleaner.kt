package com.garag.nikoncopy.copy

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "OrphanCleaner"

/**
 * Finds and removes `.pending-<timestamp>-<name>` orphan files in a destination
 * directory. These are leftovers from prior cancelled / force-killed copies
 * where MediaProvider renamed the on-disk file to the `.pending-*` form but
 * the row ended up un-usable (either stuck at IS_PENDING=1 or GC'd entirely).
 *
 * Three detection paths are tried in order of reliability:
 *   1. MediaStore by _data LIKE `%/.pending-%` — catches rows where the data
 *      column still points at a pending-prefixed path
 *   2. MediaStore IS_PENDING=1 — catches stuck pending rows
 *   3. SAF tree queryChildDocuments — catches on-disk files that SAF is
 *      willing to expose (some providers show dotfiles, some hide them)
 */
class OrphanCleaner(private val context: Context) {

    data class Entry(
        val name: String,
        val deleteAction: suspend () -> Boolean,
    )

    suspend fun scan(destinationTreeUri: Uri): List<Entry> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val seen = HashSet<String>()
        val out = mutableListOf<Entry>()

        for (e in scanMediaStoreByData(resolver, destinationTreeUri)) {
            if (seen.add(e.name + "|ms")) out += e
        }
        for (e in scanMediaStorePending(resolver, destinationTreeUri)) {
            if (seen.add(e.name + "|msp")) out += e
        }
        for (e in scanSafChildren(resolver, destinationTreeUri)) {
            if (seen.add(e.name + "|saf")) out += e
        }
        out
    }

    suspend fun cleanAll(destinationTreeUri: Uri): Int = withContext(Dispatchers.IO) {
        val entries = scan(destinationTreeUri)
        var removed = 0
        for (e in entries) {
            try {
                if (e.deleteAction()) removed++
            } catch (t: Throwable) {
                Log.w(TAG, "cleanAll: delete failed for ${e.name}: ${t.message}")
            }
        }
        Log.i(TAG, "cleanAll: removed $removed / ${entries.size}")
        removed
    }

    // ---------- detection paths ----------

    /** MediaStore rows whose _data path contains `/.pending-` (any collection). */
    private fun scanMediaStoreByData(resolver: ContentResolver, treeUri: Uri): List<Entry> {
        val relPath = mediaStoreRelativePathFor(treeUri) ?: return emptyList()
        val collections = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        )
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA)
        val selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
        val dataPattern = "%/${relPath.trimEnd('/')}/.pending-%"
        val queryBundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(dataPattern))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        val out = mutableListOf<Entry>()
        for (collection in collections) {
            try {
                resolver.query(collection, projection, queryBundle, null)?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val dataCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        val name = c.getString(nameCol) ?: c.getString(dataCol)?.substringAfterLast('/') ?: "?"
                        val uri = ContentUris.withAppendedId(collection, id)
                        out += Entry(name) {
                            try { resolver.delete(uri, null, null) > 0 } catch (_: Throwable) { false }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "scanMediaStoreByData $collection failed: ${t.message}")
            }
        }
        return out
    }

    /** MediaStore rows with IS_PENDING=1 owned by us under destination. */
    private fun scanMediaStorePending(resolver: ContentResolver, treeUri: Uri): List<Entry> {
        val relPath = mediaStoreRelativePathFor(treeUri) ?: return emptyList()
        val collections = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        )
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.IS_PENDING}=1 AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val queryBundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(context.packageName, relPath))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val out = mutableListOf<Entry>()
        for (collection in collections) {
            try {
                resolver.query(collection, projection, queryBundle, null)?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                        val name = c.getString(nameCol) ?: "?"
                        out += Entry(name) {
                            try { resolver.delete(uri, null, null) > 0 } catch (_: Throwable) { false }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "scanMediaStorePending $collection failed: ${t.message}")
            }
        }
        return out
    }

    /** SAF tree children whose display_name starts with `.pending-`. */
    private fun scanSafChildren(resolver: ContentResolver, treeUri: Uri): List<Entry> {
        val out = mutableListOf<Entry>()
        val pendingRegex = Regex("""^\.pending-\d+-.+""")
        try {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
            resolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ), null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    if (!pendingRegex.matches(name)) continue
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(idCol))
                    out += Entry(name) {
                        try { DocumentsContract.deleteDocument(resolver, docUri) } catch (_: Throwable) { false }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "scanSafChildren failed: ${t.message}")
        }
        return out
    }

    // Duplicate of CopyEngine.mediaStoreRelativePath — kept here to avoid coupling.
    private fun mediaStoreRelativePathFor(treeUri: Uri): String? {
        val docId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Throwable) { return null }
        if (!docId.startsWith("primary:")) return null
        val rel = docId.removePrefix("primary:")
        if (rel.isEmpty()) return null
        val first = rel.substringBefore('/')
        val roots = setOf("Pictures", "DCIM", "Movies")
        if (first !in roots) return null
        return if (rel.endsWith('/')) rel else "$rel/"
    }
}
