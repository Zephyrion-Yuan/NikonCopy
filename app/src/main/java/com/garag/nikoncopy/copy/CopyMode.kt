package com.garag.nikoncopy.copy

enum class CopyMode {
    /** Copy every source file whose extension matches and whose name isn't in destination. */
    ALL,

    /**
     * Copy every source file whose extension matches and which is neither in the
     * destination nor in the persisted "已成功" 清单. This makes incremental copy
     * resumable (failed files have no manifest entry, so they retry) while still
     * respecting user-deleted files (they have a manifest entry, so they're skipped).
     */
    INCREMENTAL,
}
