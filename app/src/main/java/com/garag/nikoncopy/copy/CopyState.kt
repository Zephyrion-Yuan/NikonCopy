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
    ) : CopyState()

    data class Failed(val message: String) : CopyState()
}
