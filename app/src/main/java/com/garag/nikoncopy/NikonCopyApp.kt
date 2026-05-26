package com.garag.nikoncopy

import android.app.Application
import com.garag.nikoncopy.util.LogCollector

/**
 * Custom [Application] solely so we can start [LogCollector] before any other
 * code runs — that way every onCreate log from MainActivity / CopyService /
 * ViewModels lands in the buffer too, not just whatever happens after the user
 * opens Settings.
 */
class NikonCopyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Order: start the capture thread first so the cleanup's own log lines
        // get tailed into the buffer for the new session. cleanupOldFiles only
        // touches disk, never the in-memory state.
        LogCollector.start()
        LogCollector.cleanupOldFiles(this)
    }
}
