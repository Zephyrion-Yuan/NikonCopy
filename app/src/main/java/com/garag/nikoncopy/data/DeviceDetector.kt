package com.garag.nikoncopy.data

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import com.garag.nikoncopy.mtp.NikonDirect

private const val TAG = "DeviceDetector"

/**
 * Anything currently plugged in / mounted that we might want to copy from.
 * Each detected device has a stable [deviceKey] that matches a saved
 * [DeviceProfile]'s `deviceKey` field, enabling auto-switch between profiles.
 */
sealed class DetectedDevice {
    abstract val deviceKey: String
    abstract val deviceName: String
    abstract val kind: DeviceKind

    data class Ptp(
        override val deviceKey: String,
        override val deviceName: String,
        val usbDevice: UsbDevice,
    ) : DetectedDevice() {
        override val kind: DeviceKind = DeviceKind.PTP
    }

    /** A removable storage volume (SD card, OTG USB stick, USB MSC reader). */
    data class Msc(
        override val deviceKey: String,
        override val deviceName: String,
        val volumeUuid: String?,
        val volumePath: String?,
    ) : DetectedDevice() {
        override val kind: DeviceKind = DeviceKind.MSC
    }
}

object DeviceDetector {

    /** Snapshot of everything currently detectable. Cheap, safe to call from UI. */
    fun scan(context: Context): List<DetectedDevice> {
        val ptp = scanPtp(context)
        val msc = scanMsc(context)
        return ptp + msc
    }

    fun scanPtp(context: Context): List<DetectedDevice.Ptp> {
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return um.deviceList.values.filter { dev ->
            (0 until dev.interfaceCount).any { i ->
                dev.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE
            }
        }.map { dev ->
            DetectedDevice.Ptp(
                deviceKey = NikonDirect.deviceKey(dev),
                deviceName = NikonDirect.deviceDisplayName(dev),
                usbDevice = dev,
            )
        }
    }

    fun scanMsc(context: Context): List<DetectedDevice.Msc> {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return emptyList()
        return try {
            sm.storageVolumes
                .filter { it.isRemovable && !it.isPrimary }
                .map { vol -> mscFromVolume(context, vol) }
        } catch (t: Throwable) {
            Log.w(TAG, "scanMsc failed: ${t.message}")
            emptyList()
        }
    }

    private fun mscFromVolume(context: Context, vol: StorageVolume): DetectedDevice.Msc {
        val uuid = runCatching { vol.uuid }.getOrNull()
        val name = runCatching { vol.getDescription(context) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "外接存储"
        val path = runCatching { vol.directory?.absolutePath }.getOrNull()
        return DetectedDevice.Msc(
            deviceKey = "msc:${uuid ?: path ?: name}",
            deviceName = name,
            volumeUuid = uuid,
            volumePath = path,
        )
    }
}
