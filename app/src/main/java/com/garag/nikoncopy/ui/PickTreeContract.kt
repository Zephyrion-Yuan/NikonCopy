package com.garag.nikoncopy.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts

private const val TAG = "PickTreeContract"

/** AOSP DocumentsUI package name. Present on stock Android, HyperOS, MIUI, OxygenOS. */
private const val DOCUMENTSUI_PKG = "com.android.documentsui"

/**
 * Variant of [ActivityResultContracts.OpenDocumentTree] that routes the picker to
 * AOSP DocumentsUI when present, dodging OEM file-manager handlers that misbehave
 * with the consent dialog.
 *
 * The motivating bug: on HyperOS (Xiaomi 17 Pro reported), the OEM "文件管理"
 * app handles `ACTION_OPEN_DOCUMENT_TREE` by default but dismisses the
 * "允许访问 xxx" confirmation prompt almost instantly — the user has
 * no chance to tap Allow and the result returns as RESULT_CANCELED / null.
 *
 * Strategy: if `com.android.documentsui` is installed AND can resolve the
 * intent, force the picker to use it via [Intent.setPackage]. Otherwise leave
 * the intent un-pinned so the system chooser picks whichever handler exists.
 *
 * Heavy diagnostic logging is intentional — we are debugging this on devices
 * we can't reproduce in-house, so the [com.garag.nikoncopy.util.LogCollector]
 * dump from the affected user is our primary signal. The logs answer:
 *   - which packages claim to handle ACTION_OPEN_DOCUMENT_TREE on this device
 *   - whether `setPackage` actually took effect or fell back
 *   - which package + activity is the final resolved handler
 *   - what resultCode + intent we got back from the picker
 *   - whether we got a null URI (potential auto-dismiss) vs valid URI
 */
class ForceDocumentsUITreeContract : ActivityResultContracts.OpenDocumentTree() {

    override fun createIntent(context: Context, input: Uri?): Intent {
        val startedAt = System.currentTimeMillis()
        val intent = super.createIntent(context, input)
        Log.i(TAG, "createIntent: input=$input action=${intent.action} flags=0x${intent.flags.toString(16)}")

        val pm = context.packageManager

        // Diagnostic: list every handler that could take this intent before we
        // pin a package. This tells us whether HyperOS/Xiaomi has hidden the
        // AOSP DocumentsUI, replaced it, or is just routing through their own
        // file manager by default-priority.
        val allHandlers = try {
            @Suppress("QueryPermissionsNeeded")
            pm.queryIntentActivities(intent, 0)
                .joinToString { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
        } catch (t: Throwable) {
            "query failed: ${t.javaClass.simpleName}: ${t.message}"
        }
        Log.i(TAG, "createIntent: ACTION_OPEN_DOCUMENT_TREE handlers: $allHandlers")

        // Try to route through AOSP DocumentsUI.
        try {
            val info = pm.getPackageInfo(DOCUMENTSUI_PKG, 0)
            val enabled = runCatching {
                pm.getApplicationInfo(DOCUMENTSUI_PKG, 0).enabled
            }.getOrDefault(true)
            Log.i(TAG, "createIntent: $DOCUMENTSUI_PKG version=${info?.versionName} enabled=$enabled")

            intent.setPackage(DOCUMENTSUI_PKG)
            val resolved = intent.resolveActivity(pm)
            if (resolved == null) {
                Log.w(TAG, "createIntent: setPackage($DOCUMENTSUI_PKG) didn't resolve — falling back to system chooser")
                intent.setPackage(null)
            } else {
                Log.i(TAG, "createIntent: pinned to $DOCUMENTSUI_PKG → ${resolved.className}")
            }
        } catch (_: PackageManager.NameNotFoundException) {
            Log.i(TAG, "createIntent: $DOCUMENTSUI_PKG not installed; using system chooser")
        } catch (t: Throwable) {
            Log.w(TAG, "createIntent: routing check threw, leaving intent un-pinned: ${t.message}")
        }

        // Final resolved activity — what the user will actually see.
        val finalResolved = intent.resolveActivity(pm)
        Log.i(TAG, "createIntent: DONE in ${System.currentTimeMillis() - startedAt}ms; final handler=$finalResolved package=${intent.`package`}")
        PickerDiagnostics.lastLaunchAtMs = System.currentTimeMillis()
        return intent
    }
}

/**
 * Process-global state for picker diagnostics. `parseResult` on [ActivityResultContracts.OpenDocumentTree]
 * is final so we can't hook the parse step directly — instead the contract
 * records launch time here, and the launcher's lambda callsites (in
 * [com.garag.nikoncopy.ui.SettingsScreen]) read it to log elapsed time +
 * detect the HyperOS auto-dismiss pattern.
 */
object PickerDiagnostics {
    /** Wallclock when the contract last produced an intent. */
    @Volatile
    var lastLaunchAtMs: Long = 0L

    /**
     * Standardised callback log + suspect-autodismiss tagging. Invoke at the
     * top of every tree-picker `ActivityResultCallback`. [label] disambiguates
     * which launcher fired (we have three: dest, msc-source, msc-tree).
     */
    fun onPickerResult(label: String, uri: Uri?) {
        val elapsed = if (lastLaunchAtMs > 0) System.currentTimeMillis() - lastLaunchAtMs else -1L
        Log.i(TAG, "callback[$label]: uri=$uri elapsedSinceLaunch=${elapsed}ms")
        if (uri == null && elapsed in 0..2000L) {
            // Almost certainly the HyperOS / OEM auto-dismiss pattern, not a
            // real user-initiated cancel. Tag explicitly so it's easy to grep.
            Log.w(TAG, "callback[$label]: SUSPECTED_AUTODISMISS — null URI returned ${elapsed}ms after launch")
        }
    }
}
