package com.garag.nikoncopy.util

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory ring buffer of the current process's logcat output. Captures every
 * line written to logcat for our PID from process start onwards, capped at
 * [MAX_LINES] so memory stays bounded.
 *
 * Purpose: let users dump app logs to clipboard from Settings when they hit a
 * bug we can't reproduce in-house — e.g. the HyperOS SAF picker dismissing the
 * "允许访问" consent dialog before they can tap Allow. The captured stream
 * tells us the actual ResolveInfo the system chose, the time-to-cancel of the
 * picker activity, and whether our Activity got destroyed during the picker
 * (lifecycle logs from [com.garag.nikoncopy.MainActivity]).
 *
 * Logcat invocation: `logcat -v threadtime --pid=<self> -T 1`. The `--pid`
 * filter requires API 24+ (we are minSdk 30, safe). The platform restricts
 * non-system apps to seeing only their own UID's logs since Android 4.1, so
 * no `READ_LOGS` permission is required.
 */
object LogCollector {
    private const val TAG = "LogCollector"
    private const val MAX_LINES = 2000

    /**
     * Time-since-last-reset threshold for [resetBufferIfStale]. Idle background
     * sessions longer than this clear the buffer the next time the user comes
     * back to the foreground — the stale log lines from "an hour ago" would
     * just be noise the user has to scroll past when debugging fresh issues.
     * "Negotiable" per the spec; 1 hour is a reasonable default.
     */
    private val STALE_THRESHOLD_MS = TimeUnit.HOURS.toMillis(1)

    private val buffer = ConcurrentLinkedDeque<String>()
    private val started = AtomicBoolean(false)

    /**
     * Monotonic clock value of the last buffer-clear point. Uses
     * [SystemClock.elapsedRealtime] so manual time-zone or NTP adjustments
     * (which jump [System.currentTimeMillis]) can't trick the staleness check.
     */
    private val lastResetAt = AtomicLong(SystemClock.elapsedRealtime())

    /** Idempotent. Starts a daemon thread that tails `logcat` into [buffer]. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread({ captureLoop() }, "LogCollector").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "started; pid=${android.os.Process.myPid()} maxLines=$MAX_LINES staleThreshold=${TimeUnit.MILLISECONDS.toMinutes(STALE_THRESHOLD_MS)}min")
    }

    /** Full buffer joined with `\n`. Caller may truncate further if needed. */
    fun snapshot(): String = buffer.joinToString("\n")

    /** Approximate line count currently retained. */
    fun lineCount(): Int = buffer.size

    /**
     * If more than [STALE_THRESHOLD_MS] has passed since the last reset (or
     * process start), clear the in-memory buffer. Designed to be called from
     * [com.garag.nikoncopy.MainActivity.onResume] so background→foreground
     * transitions after a long idle start with a clean slate — the alternative
     * (a periodic timer thread or AlarmManager) costs battery and gets frozen
     * anyway by Xiaomi's aggressive background restrictions.
     *
     * Cheap no-op when not stale, so calling on every resume is fine.
     */
    fun resetBufferIfStale() {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - lastResetAt.get()
        if (elapsedMs < STALE_THRESHOLD_MS) return
        val cleared = buffer.size
        buffer.clear()
        lastResetAt.set(now)
        Log.i(TAG, "buffer auto-reset after ${TimeUnit.MILLISECONDS.toMinutes(elapsedMs)}min idle (cleared $cleared lines)")
    }

    /**
     * Delete every `nikoncopy-log-*.txt` file in the app's external cache —
     * stale dumps from previous process lifetimes that the user already
     * shared (or didn't) and shouldn't be confused with the next session's
     * fresh export. Called from [com.garag.nikoncopy.NikonCopyApp.onCreate]
     * on every cold start.
     *
     * Touches disk only; does NOT clear the in-memory buffer (that lives in
     * a brand-new process on cold start anyway).
     */
    fun cleanupOldFiles(context: Context) {
        val dir = context.externalCacheDir ?: return
        var deleted = 0
        try {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith("nikoncopy-log-") && f.name.endsWith(".txt")) {
                    if (f.delete()) deleted++
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cleanupOldFiles failed: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        if (deleted > 0) Log.i(TAG, "cleanupOldFiles: deleted $deleted stale dump file(s)")
    }

    /**
     * Filter [snapshot] for the "复制运行日志" button.
     *
     * Rules (evaluated top-to-bottom):
     *  1. **Drop** if tag in [NOISY_TAGS]. Overrides everything else — these
     *     tags spam at W/E priority on Xiaomi/HyperOS (InsetsSource alone can
     *     emit hundreds of lines per second during Compose layout) and would
     *     drown out the operationally useful events plus blow past clipboard
     *     truncation limits in chat apps.
     *  2. **Keep** if tag in [RELEVANT_TAGS] — our own code's events plus
     *     handpicked system-side tags worth seeing (e.g. ActivityTaskManager,
     *     DocumentsUI).
     *  3. **Keep** if priority is W / E / F — catches unanticipated tags that
     *     might be relevant when triaging a new bug.
     *  4. Otherwise drop.
     *
     * Output is wrapped in BEGIN/END markers so when a chat app truncates the
     * paste mid-line, the recipient can tell at a glance that they didn't get
     * the full content.
     */
    fun filterForReport(raw: String): String {
        val body = raw.lineSequence().filter { keepInReport(it) }.joinToString("\n")
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val lineCount = body.count { it == '\n' } + (if (body.isEmpty()) 0 else 1)
        return buildString {
            append("========== NikonCopy log dump @ $now (").append(lineCount).append(" lines) ==========\n")
            append(body)
            if (body.isNotEmpty() && !body.endsWith("\n")) append('\n')
            append("========== END (").append(length).append(" chars) ==========")
        }
    }

    /**
     * Persist the filtered log to a timestamped text file in the app's external
     * cache so the user has a fallback if the clipboard gets truncated by their
     * messaging app. Returns the file, or null on I/O failure.
     *
     * External cache (not external files) is intentional: cache is auto-cleaned
     * by the system + by "clear cache" actions, so old log files don't pile up
     * forever. The user only needs each dump long enough to share it once.
     */
    fun exportToFile(context: Context): File? {
        val payload = filterForReport(snapshot())
        val dir = context.externalCacheDir ?: context.cacheDir
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "nikoncopy-log-$ts.txt")
        return try {
            file.writeText(payload, Charsets.UTF_8)
            file
        } catch (t: Throwable) {
            Log.w(TAG, "exportToFile failed: ${t.message}")
            null
        }
    }

    /**
     * Regex over the threadtime header. Capture groups: 1=priority char,
     * 2=tag (everything up to the first colon). The leading `\s+` matches
     * the whitespace separating TID from the priority field.
     */
    private val LINE_HEADER_RE = Regex("""\s+([VDIWEF])\s+([^:]+?):""")

    /** Tags worth keeping at any priority. */
    private val RELEVANT_TAGS = setOf(
        // Our own code
        "MainActivity", "NikonCopyApp",
        "PickTreeContract", "SettingsScreen", "HomeScreen",
        "CopyEngine", "CopyService", "CopyViewModel",
        "NikonDirect", "PtpClient", "DestinationIndex",
        "DeviceDetector", "MediaProbe", "ManifestStore",
        "SettingsRepository",
        "MtimeUtil", "LogCollector", "OrphanCleaner",
        "RootPatcher",
        // System-side tags routinely relevant to our bug surface
        "AndroidRuntime",   // crashes
        "ActivityManager",  // process / activity transitions
        "ActivityTaskManager",
        "WindowManager",
        "DocumentsUI",      // SAF picker
    )

    /**
     * Tags that spam at W/E priority but never carry actionable information
     * for the bugs this app cares about. Dropped unconditionally — they would
     * otherwise drown out the relevant events AND push past clipboard
     * truncation limits in messaging apps.
     *
     * Confirmed offenders on HyperOS / Xiaomi 17 Pro from a real bug-report
     * paste: `InsetsSource` fired ~30 times in 300ms during Compose layout,
     * `garag.nikoncopy` (the process-name tag that ART uses for class-load
     * verification warnings), and the MIUI internal trackers below.
     */
    private val NOISY_TAGS = setOf(
        // Compose / WindowManager layout spam
        "InsetsSource", "WindowInsets", "ViewRootImpl", "HwViewRootImpl",
        // ART class-loader / dex-optimization noise tagged with our process name
        "garag.nikoncopy",
        // Xiaomi / MIUI internal subsystems
        "MI-PreRender", "MIUIScout", "MIUIPerf", "MiuiFreeFormPipController",
        "FramePredict", "ContentCatcherManager", "VolumeBoostNotifier",
        // Generic libc / linker complaints about Xiaomi vendor properties
        "libc", "linker",
        // Graphics / IPC noise
        "BufferQueueProducer", "OpenGLRenderer", "Choreographer", "ChoreographerEx",
        // Profile / library installation noise
        "ProfileInstaller",
        // Input method spam
        "IInputConnectionWrapper", "InputMethodManager", "InputTransport",
    )

    private fun keepInReport(line: String): Boolean {
        val m = LINE_HEADER_RE.find(line) ?: return false
        val priority = m.groupValues[1]
        val tag = m.groupValues[2].trim()
        if (tag in NOISY_TAGS) return false  // ALWAYS drop — overrides W/E/F
        if (tag in RELEVANT_TAGS) return true
        if (priority == "W" || priority == "E" || priority == "F") return true
        return false
    }

    private fun captureLoop() {
        val pid = android.os.Process.myPid()
        try {
            val proc = Runtime.getRuntime().exec(arrayOf(
                "logcat",
                "-v", "threadtime",  // include date / pid / tid / priority / tag
                "-T", "1",           // start from now, then follow
                "--pid=$pid",
            ))
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    buffer.addLast(line)
                    while (buffer.size > MAX_LINES) buffer.pollFirst()
                    line = reader.readLine()
                }
            }
        } catch (t: Throwable) {
            // Common in dev when logcat command isn't accessible; silently log and exit.
            // Buffer stays empty in that case but the app keeps working.
            Log.w(TAG, "captureLoop terminated: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
