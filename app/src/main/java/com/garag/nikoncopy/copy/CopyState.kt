package com.garag.nikoncopy.copy

sealed class CopyState {
    data object Idle : CopyState()

    data class Scanning(val foundFiles: Int) : CopyState()

    enum class Phase {
        COPYING,
        FINALIZING,
        FIXING_DATES,
    }

    data class Running(
        val currentIndex: Int,
        val totalFiles: Int,
        val currentName: String,
        val bytesCopied: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
        val phase: Phase,
        val pendingPostProcess: Int,
    ) : CopyState() {
        val progress: Float
            get() = if (totalFiles <= 0) 0f
            else if (phase == Phase.FIXING_DATES) currentIndex.toFloat() / totalFiles
            else if (totalBytes <= 0) 0f
            else (bytesCopied.toDouble() / totalBytes.toDouble()).toFloat()
    }

    data class Done(
        val filesCopied: Int,
        val filesSkipped: Int,
        val totalBytes: Long,
        val elapsedMillis: Long,
        /**
         * Files that the engine attempted to copy but couldn't — exhausted the
         * end-of-batch retry pass. They have NO record in the manifest and NO
         * partial bytes left in the destination (the per-attempt catch path
         * deletes any half-written row), so simply re-running "拷贝全部" or
         * "增量拷贝" will retry them safely with no duplication risk.
         */
        val filesFailed: Int = 0,
    ) : CopyState()

    data class Failed(val message: String) : CopyState()
}
