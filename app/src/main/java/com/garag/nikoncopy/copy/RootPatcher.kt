package com.garag.nikoncopy.copy

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "RootPatcher"

/**
 * Applies the two system-level tweaks that make NikonCopy run at full speed
 * when root (or adb shell) is available:
 *
 *   1. `pm disable-user --user 0 com.android.mtp`
 *      - Stops the system MTP DocumentsProvider from claiming the PTP session
 *        when the Nikon plugs in. Enables direct 160+ MB/s USB transfers.
 *
 *   2. `settings put global hidden_api_policy 1`
 *      - Allows reflection to Android hidden APIs as a fallback. Not strictly
 *        needed for core functionality (JNI futimens handles mtime), but
 *        removes warnings and gives the reflection-based fallback a chance.
 *
 * The app tries `su -c <cmd>` which works on Magisk/KernelSU/APatch devices.
 * On devices without any root solution, surfaces the commands for the user
 * to run via adb instead.
 */
class RootPatcher(private val context: Context) {

    data class Status(
        val mtpDisabled: Boolean,
        val hiddenApiPolicy: Int,
    ) {
        val fullyApplied: Boolean get() = mtpDisabled && hiddenApiPolicy == 1
    }

    enum class Result { APPLIED, NO_ROOT, PARTIAL, ALREADY_APPLIED }

    fun currentStatus(): Status {
        val mtpDisabled = try {
            val info = context.packageManager.getApplicationInfo("com.android.mtp", 0)
            !info.enabled
        } catch (_: PackageManager.NameNotFoundException) {
            true // package gone = effectively disabled
        } catch (_: Throwable) {
            false
        }
        val policy = try {
            Settings.Global.getInt(context.contentResolver, "hidden_api_policy", 0)
        } catch (_: Throwable) {
            0
        }
        return Status(mtpDisabled, policy)
    }

    suspend fun apply(): Result = withContext(Dispatchers.IO) {
        val before = currentStatus()
        if (before.fullyApplied) return@withContext Result.ALREADY_APPLIED

        val cmds = buildList {
            if (!before.mtpDisabled) add("pm disable-user --user 0 com.android.mtp")
            if (before.hiddenApiPolicy != 1) add("settings put global hidden_api_policy 1")
        }
        val script = cmds.joinToString(" && ")

        val suOk = runSu(script)
        if (!suOk) return@withContext Result.NO_ROOT

        val after = currentStatus()
        return@withContext when {
            after.fullyApplied -> Result.APPLIED
            after.mtpDisabled || after.hiddenApiPolicy == 1 -> Result.PARTIAL
            else -> Result.NO_ROOT
        }
    }

    private fun runSu(cmd: String): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val exit = process.waitFor()
            Log.i(TAG, "su -c '$cmd' exit=$exit output=$output")
            exit == 0
        } catch (t: Throwable) {
            Log.w(TAG, "su exec failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    companion object {
        /** Copy-paste-friendly adb command for users without root. */
        const val ADB_INSTRUCTIONS =
            "adb shell pm disable-user --user 0 com.android.mtp && " +
            "adb shell settings put global hidden_api_policy 1"
    }
}
