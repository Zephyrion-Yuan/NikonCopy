package com.garag.nikoncopy.mtp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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

data class OpenedPtpDevice(
    val client: PtpClient,
    val deviceKey: String,
    val deviceName: String,
)

object NikonDirect {

    /**
     * Locate a connected Nikon-branded camera. Returns the first match.
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

    fun findPtpCamera(context: Context, preferredDeviceKey: String? = null): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val cameras = usbManager.deviceList.values.filter { dev ->
            (0 until dev.interfaceCount).any { i ->
                dev.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE
            }
        }
        if (cameras.isEmpty()) {
            Log.i(TAG, "no PTP cameras in deviceList (size=${usbManager.deviceList.size})")
            return null
        }
        if (preferredDeviceKey != null) {
            cameras.firstOrNull { deviceKey(it) == preferredDeviceKey }?.let { return it }
        }
        return cameras.firstOrNull { it.vendorId == NIKON_VID } ?: cameras.first()
    }

    fun deviceKey(device: UsbDevice): String {
        val manufacturer = runCatching { device.manufacturerName }.getOrNull()
            ?.sanitizeKeyPart()
            .orEmpty()
        val product = runCatching { device.productName }.getOrNull()
            ?.sanitizeKeyPart()
            .orEmpty()
        return buildString {
            append("%04x:%04x".format(device.vendorId, device.productId))
            if (manufacturer.isNotBlank() || product.isNotBlank()) {
                append(":")
                append(manufacturer)
                append(":")
                append(product)
            }
        }
    }

    fun deviceDisplayName(device: UsbDevice): String {
        val manufacturer = runCatching { device.manufacturerName }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        val product = runCatching { device.productName }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        return listOfNotNull(manufacturer, product).joinToString(" ")
            .ifBlank { "PTP Camera %04x:%04x".format(device.vendorId, device.productId) }
    }

    fun findPtpInterface(device: UsbDevice): android.hardware.usb.UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) {
                return intf
            }
        }
        return null
    }

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
     * Check whether direct PTP should be enabled. Auto-enables when
     * `com.android.mtp` is disabled (no session competition).
     * When MTP provider is active, direct PTP causes endpoint deadlocks.
     */
    fun isDirectPtpAvailable(context: Context): Boolean {
        return isMtpProviderDisabled(context).also { enabled ->
            Log.i(TAG, "isDirectPtpAvailable=$enabled (com.android.mtp disabled=$enabled)")
        }
    }

    private fun isMtpProviderDisabled(context: Context): Boolean {
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PackageManager.ApplicationInfoFlags.of(0)
            } else {
                @Suppress("DEPRECATION")
                null
            }
            val info = if (flags != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo("com.android.mtp", flags)
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo("com.android.mtp", 0)
            }
            !info.enabled
        } catch (_: PackageManager.NameNotFoundException) {
            // Package not present at all — safe to use direct PTP
            true
        }
    }

    /**
     * Open a [PtpClient] against a connected Nikon.
     *
     * With `com.android.mtp` disabled, the USB stack often re-enumerates the
     * device ~1 s after the first session open (no MTP handler → port reset).
     * We handle this by probing with GetStorageIDs; if the probe fails we
     * wait for the device to reappear and open a fresh PtpClient on the
     * re-enumerated device.
     */
    suspend fun open(context: Context): PtpClient? = openDevice(context)?.client

    suspend fun openDevice(context: Context, preferredDeviceKey: String? = null): OpenedPtpDevice? {
        if (!isDirectPtpAvailable(context)) {
            Log.i(TAG, "direct PTP unavailable (com.android.mtp still enabled)")
            return null
        }

        for (attempt in 1..3) {
            val device = findPtpCamera(context, preferredDeviceKey) ?: return null.also {
                Log.w(TAG, "open[$attempt]: no PTP camera")
            }
            val intf = findPtpInterface(device) ?: return null.also {
                Log.w(TAG, "open[$attempt]: no PTP interface")
            }
            if (!ensurePermission(context, device)) return null.also {
                Log.w(TAG, "open[$attempt]: permission denied")
            }
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

            val conn = usbManager.openDevice(device) ?: return null.also {
                Log.w(TAG, "open[$attempt]: openDevice returned null")
            }
            val ptp = try {
                PtpClient(conn, intf, cleanConnection = true)
            } catch (t: Throwable) {
                Log.w(TAG, "open[$attempt]: PtpClient ctor failed: ${t.message}")
                try { conn.close() } catch (_: Throwable) {}
                if (attempt < 3) {
                    Log.i(TAG, "open[$attempt]: waiting for USB re-enumeration...")
                    kotlinx.coroutines.delay(2500)
                    continue
                }
                return null
            }

            // Health check: if GetStorageIDs works, the connection is alive.
            val healthy = try {
                ptp.getStorageIds()
                true
            } catch (_: Throwable) {
                false
            }

            if (healthy) {
                val key = deviceKey(device)
                val name = deviceDisplayName(device)
                Log.i(TAG, "open[$attempt]: connection verified key=$key name=$name")
                return OpenedPtpDevice(ptp, key, name)
            }

            Log.w(TAG, "open[$attempt]: health check failed; USB port likely re-enumerating")
            try { ptp.close() } catch (_: Throwable) {}

            if (attempt < 3) {
                // Wait for the device to disconnect and reappear
                Log.i(TAG, "open[$attempt]: waiting for USB re-enumeration...")
                kotlinx.coroutines.delay(2500)
            }
        }

        Log.e(TAG, "open: all attempts exhausted")
        return null
    }

    private fun String.sanitizeKeyPart(): String {
        return trim()
            .lowercase()
            .replace(Regex("""\s+"""), "_")
            .replace(Regex("""[^a-z0-9_.-]"""), "")
    }
}
