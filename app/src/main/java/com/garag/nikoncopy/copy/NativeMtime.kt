package com.garag.nikoncopy.copy

import android.util.Log

/**
 * JNI bridge to `futimens()` — sets file mtime via a standard POSIX syscall,
 * bypassing Android's hidden-API restrictions on `Os.utimensat`.
 *
 * Works on any Android version, no root or reflection needed.
 */
object NativeMtime {
    private const val TAG = "NativeMtime"

    val available: Boolean = try {
        System.loadLibrary("mtime_jni")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "native lib unavailable: ${t.message}")
        false
    }

    /**
     * Set the mtime of an already-opened fd to [epochMillis].
     * Returns true if the mtime was set and verified via fstat.
     */
    fun setMtimeByFd(fd: Int, epochMillis: Long): Boolean {
        if (!available) return false
        return try {
            nSetMtimeByFd(fd, epochMillis)
        } catch (t: Throwable) {
            Log.w(TAG, "setMtimeByFd failed: ${t.message}")
            false
        }
    }

    @JvmStatic
    private external fun nSetMtimeByFd(fd: Int, timeMillis: Long): Boolean
}
