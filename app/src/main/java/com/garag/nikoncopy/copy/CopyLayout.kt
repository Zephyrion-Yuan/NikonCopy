package com.garag.nikoncopy.copy

/**
 * Describes how copied files should be placed under the destination tree.
 *
 *  - **Flat (subdir == null)**: every file lands at `<dest>/<filename>` regardless
 *    of where it lived under the source. Skip-by-name and the manifest are keyed
 *    by `<filename>`. This is the historical behaviour and the default.
 *
 *  - **Structured (subdir != null)**: files preserve their source-side directory
 *    layout under a per-device subdirectory:
 *    `<dest>/<subdir>/<sourceRelPath>/<filename>`. Skip-by-name and the manifest
 *    are keyed by `<sourceRelPath>/<filename>` so the same basename in two
 *    different source subdirs are correctly treated as different files. The
 *    [subdir] is allocated and frozen at first copy via
 *    [com.garag.nikoncopy.data.DestinationIndex] — the user-facing
 *    `DeviceProfile.deviceName` can be renamed later without disturbing the
 *    physical layout.
 */
data class CopyLayout(
    val subdir: String? = null,
) {
    val preservesStructure: Boolean get() = subdir != null

    /**
     * Build the skip/manifest key for a source file at [sourceRelPath]/[filename].
     * In flat mode the relPath is dropped; in structured mode it's preserved
     * (with an empty relPath collapsing to just the filename for files that live
     * at the source root).
     */
    fun keyFor(sourceRelPath: String, filename: String): String {
        val normalized = sourceRelPath.trim().trim('/')
        return if (!preservesStructure || normalized.isEmpty()) filename
        else "$normalized/$filename"
    }
}
