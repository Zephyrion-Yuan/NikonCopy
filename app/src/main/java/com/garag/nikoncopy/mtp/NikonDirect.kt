package com.garag.nikoncopy.mtp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "NikonDirect"

private const val NIKON_VID = 0x04B0    // 1200 — Nikon Corporation

private const val ACTION_USB_PERMISSION = "com.garag.nikoncopy.USB_PERMISSION"

object NikonDirect {

    /**
     * Locate a connected Nikon-branded camera. Returns the first match.
     * Note: PIDs vary per model; we filter on VID only and let interface-class
     * matching (class=6 still-image) below confirm it's a PTP-capable camera.
     */
    fun findNikon(context: Context): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val candidates = usbManager.deviceList.values.filter { it.vendorId == NIKON_VID }
        if (candidates.isEmpty()) {
            Log.i(TAG, "no Nikon USB devices in deviceList (size=${usbManager.deviceList.size})")
            return null
        }
        val ptpCamera = candidates.firstOrNull { dev ->
            (0 until dev.interfaceCount).any { i ->
                val intf = dev.getInterface(i)
                intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE
            }
        }
        if (ptpCamera == null) {
            Log.w(TAG, "Nikon device found but no still-image interface — not a camera?")
        }
        return ptpCamera
    }

    /**
     * Pick the still-image PTP interface (class=6, subclass=1, protocol=1) on the
     * device. Returns null if not present.
     */
    fun findPtpInterface(device: UsbDevice): android.hardware.usb.UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) {
                return intf
            }
        }
        return null
    }

    /**
     * Request USB permission for [device] from the user. Suspends until the system
     * dialog is dismissed (granted or not). Returns true on grant.
     *
     * Implementation: register a private broadcast receiver, fire the system's
     * permission dialog with a PendingIntent that resolves to that broadcast,
     * resume on receipt.
     */
    suspend fun ensurePermission(context: Context, device: UsbDevice): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        if (usbManager.hasPermission(device)) return true

        return suspendCancellableCoroutine { cont ->
            val appCtx = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    try { c.unregisterReceiver(this) } catch (_: Throwable) {}
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB permission granted=$granted device=${device.deviceName}")
                    if (cont.isActive) cont.resume(granted)
                }
            }

            // Android 14 requires explicit export flags; receiver is private to our package.
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.RECEIVER_NOT_EXPORTED
            } else 0
            ContextCompat.registerReceiver(appCtx, receiver, filter, flags)

            val pi = PendingIntent.getBroadcast(
                appCtx,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(appCtx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usbManager.requestPermission(device, pi)

            cont.invokeOnCancellation {
                try { appCtx.unregisterReceiver(receiver) } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Open a [PtpClient] against a connected Nikon. Returns null with a logged
     * reason if the camera isn't present, permission was denied, or claim failed.
     *
     * NOTE: this force-claims interface 0, kicking out `com.android.mtp` if it
     * had the device. SAF-based access to Nikon won't work while we hold it.
     * Closing the returned PtpClient releases the interface and SAF resumes.
     */
    /**
     * On Android 16 (SDK 36) the direct-PTP path is non-functional:
     *   - `Os.utimensat` reflection blocked (`core-platform-api` denial)
     *   - `setHiddenApiExemptions` itself blocked (no bypass for #1)
     *   - `Os.ioctlInt` blocked → `USBDEVFS_RESET` ioctl unavailable
     *
     * Without USBDEVFS_RESET we can't evict `com.android.mtp` from the camera, so
     * our PTP commands are silently rejected (bulk OUT returns -1 immediately).
     *
     * Set this to `false` to permanently route through SAF. Re-enable when
     * targeting older Android, or when a privileged USB-reset path is added.
     */
    private const val ENABLE_DIRECT_PTP = false

    suspend fun open(context: Context): PtpClient? {
        if (!ENABLE_DIRECT_PTP) {
            Log.i(TAG, "direct PTP disabled (Android 16 hidden-API restrictions)")
            return null
        }
        val device = findNikon(context) ?: return null.also {
            Log.w(TAG, "open: no Nikon device")
        }
        val intf = findPtpInterface(device) ?: return null.also {
            Log.w(TAG, "open: no PTP interface")
        }
        if (!ensurePermission(context, device)) return null.also {
            Log.w(TAG, "open: permission denied")
        }
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        // First attempt: open + try to use the device as-is.
        val firstConn = usbManager.openDevice(device) ?: return null.also {
            Log.w(TAG, "open: openDevice returned null")
        }
        try {
            return PtpClient(firstConn, intf)
        } catch (t: Throwable) {
            Log.w(TAG, "open: first PtpClient ctor failed (${t.message}); attempting USBDEVFS_RESET")
        }

        // Second attempt: full USB device reset via ioctl, then re-open. The reset
        // re-enumerates the device — even com.android.mtp loses its handle, so the
        // camera-side PTP state is forced fresh.
        try {
            val reset = PtpClient.tryIoctlResetWithoutSession(firstConn)
            try { firstConn.close() } catch (_: Throwable) {}
            if (!reset) {
                Log.w(TAG, "open: USBDEVFS_RESET failed; giving up on direct path")
                return null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "open: reset attempt threw: ${t.message}")
            try { firstConn.close() } catch (_: Throwable) {}
            return null
        }

        // Wait for the device to re-enumerate, then look it up again — UsbDevice
        // identity is tied to the bus path, which usually survives a reset, but
        // we re-fetch defensively.
        kotlinx.coroutines.delay(800)
        val device2 = findNikon(context) ?: return null.also {
            Log.w(TAG, "open: post-reset no Nikon found")
        }
        val intf2 = findPtpInterface(device2) ?: return null.also {
            Log.w(TAG, "open: post-reset no PTP interface")
        }
        if (!ensurePermission(context, device2)) return null.also {
            Log.w(TAG, "open: post-reset permission denied")
        }
        val secondConn = usbManager.openDevice(device2) ?: return null.also {
            Log.w(TAG, "open: post-reset openDevice null")
        }
        return try {
            PtpClient(secondConn, intf2)
        } catch (t: Throwable) {
            Log.e(TAG, "open: post-reset PtpClient ctor still failed", t)
            try { secondConn.close() } catch (_: Throwable) {}
            null
        }
    }
}
