package com.garag.nikoncopy.mtp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "PtpClient"

// PTP container types (PIMA 15740, ISO 15740).
private const val TYPE_COMMAND = 1
private const val TYPE_DATA = 2
private const val TYPE_RESPONSE = 3
private const val TYPE_EVENT = 4

// PTP/MTP operation codes we use.
private const val OP_OPEN_SESSION = 0x1002
private const val OP_CLOSE_SESSION = 0x1003
private const val OP_GET_STORAGE_IDS = 0x1004
private const val OP_GET_STORAGE_INFO = 0x1005
private const val OP_GET_OBJECT_HANDLES = 0x1007
private const val OP_GET_OBJECT_INFO = 0x1008
private const val OP_GET_OBJECT = 0x1009

// Response codes.
private const val RESP_OK = 0x2001
private const val RESP_SESSION_ALREADY_OPEN = 0x201E

// MTP object format codes.
private const val FMT_ASSOCIATION = 0x3001
private const val FMT_EXIF_JPEG = 0x3801
private const val FMT_TIFF = 0x380D
private const val FMT_TIFF_EP = 0x380E
private const val FMT_MP4 = 0xB982 // Nikon-ish; format codes vary by camera
private const val FMT_UNDEFINED = 0x3000

// Bulk transfer constants.
private const val MAX_BULK_BYTES = 262144       // 256 KiB — reduces JNI call overhead on USB 3.0
private const val COMMAND_TIMEOUT_MS = 5_000
private const val OBJECT_TIMEOUT_MS = 30_000
private const val RECOVERY_DRAIN_TIMEOUT_MS = 100
private const val RECOVERY_DRAIN_MAX_READS = 8
private const val RECOVERY_SETTLE_MS = 250L
private const val STALE_SKIP_MAX = 4

val DEFAULT_MEDIA_EXTENSIONS = setOf(
    "jpg", "jpeg",
    "nef", "arw", "cr2", "cr3", "raf", "rw2", "dng",
    "heif", "heic", "hif",
    "mp4", "mov",
)

data class MtpFile(
    val handle: Int,
    val name: String,
    val size: Long,
    val dateModifiedMillis: Long,   // 0 if camera didn't supply it
    val format: Int,
    val storageId: Int,
    val parentHandle: Int,
    val directoryPath: String = "/",
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()
}

/**
 * Bare-metal PTP client that talks directly to a Nikon (or any class-6/1/1 PTP
 * device) over USB. Bypasses Android's `MtpDevice` Java wrapper so we can:
 *
 *  1. Force-claim interface 0 even when `com.android.mtp` (`MtpDocumentsProvider`)
 *     already holds it — `MtpDevice.open()` doesn't expose `force=true`, but
 *     `UsbDeviceConnection.claimInterface(intf, true)` does.
 *  2. Issue large `GetObject` transactions for max throughput, since each PTP
 *     command has ~10–20 ms of overhead and a single transaction per file
 *     dramatically outperforms `com.android.mtp`'s AppFuse 128 KiB chunking.
 *
 * Implementation references the PTP/MTP container format (12-byte header + body):
 *
 *   uint32 length            // total bytes including this header
 *   uint16 type              // 1=command, 2=data, 3=response, 4=event
 *   uint16 code              // command/response code
 *   uint32 transactionId     // unique per command in a session
 *   ... params (4 bytes each, command/response only) or raw data ...
 *
 * Lifecycle: ctor performs claim + OpenSession; [close] performs CloseSession +
 * release. The instance is single-threaded; do not share across coroutines.
 */
class PtpClient(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    cleanConnection: Boolean = false,
) : AutoCloseable {

    private val sessionId = 1
    private val bulkIn: UsbEndpoint
    private val bulkOut: UsbEndpoint
    private var nextTxn: Int = 0
    private var lastSentTxn: Int = -1     // for response-vs-request transactionId matching
    private var sessionOpen = false

    init {
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
        }
        bulkIn = inEp ?: throw IOException("PTP: no bulk IN endpoint on interface ${intf.id}")
        bulkOut = outEp ?: throw IOException("PTP: no bulk OUT endpoint on interface ${intf.id}")
        Log.i(TAG, "PTP endpoints: in=ep${bulkIn.address} maxPkt=${bulkIn.maxPacketSize} out=ep${bulkOut.address} maxPkt=${bulkOut.maxPacketSize}")

        if (!connection.claimInterface(intf, /* force = */ true)) {
            throw IOException("PTP: force claimInterface failed")
        }

        if (cleanConnection) {
            // com.android.mtp is disabled — no stale state to clean up.
            // Skipping clearHalt avoids desynchronising the DATA0/DATA1 toggle
            // on an already-clean USB pipe.
            Log.i(TAG, "clean connection: skipping clearHalt/drain")
        } else {
            // com.android.mtp may have left the bulk endpoints stalled or with
            // residual data. Clear halt + drain puts pipes in a known state.
            clearHalt(bulkOut)
            clearHalt(bulkIn)
            drainBulkIn()
        }

        try {
            openSession(sessionId)
            sessionOpen = true
            // Give the camera time to initialise its storage subsystem after
            // the session is established — Nikon Z f needs a brief settle.
            Thread.sleep(300)
            Log.i(TAG, "PTP session opened (settled)")
        } catch (t: Throwable) {
            try { connection.releaseInterface(intf) } catch (_: Throwable) {}
            throw t
        }
    }

    companion object {
        /**
         * Static: ask the kernel to do a full USB device reset (USBDEVFS_RESET) on
         * the given connection. Re-enumerates the device — kicks `com.android.mtp`
         * off too, so camera-side PTP state is forced fresh. Caller must close +
         * reopen the device after this.
         *
         * Uses `Os.ioctlInt` via reflection because the USBDEVFS_RESET constant
         * isn't in the public OsConstants and the method is package-private on some
         * Android versions.
         */
        fun tryIoctlResetWithoutSession(conn: UsbDeviceConnection): Boolean {
            return try {
                val osClass = Class.forName("android.system.Os")
                val method = osClass.getDeclaredMethod(
                    "ioctlInt", java.io.FileDescriptor::class.java, Int::class.javaPrimitiveType,
                ).also { it.isAccessible = true }
                // _IO('U', 20) per linux/usbdevice_fs.h
                val usbdevfsReset = (('U'.code) shl 8) or 20
                val fd = java.io.FileDescriptor()
                val fdField = java.io.FileDescriptor::class.java
                    .getDeclaredField("descriptor").also { it.isAccessible = true }
                fdField.setInt(fd, conn.fileDescriptor)
                method.invoke(null, fd, usbdevfsReset)
                Log.i(TAG, "USBDEVFS_RESET ioctl ok (device will re-enumerate)")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "USBDEVFS_RESET failed: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }
    }

    override fun close() {
        try {
            if (sessionOpen) {
                runCatching { closeSession() }
                sessionOpen = false
            }
        } finally {
            try { connection.releaseInterface(intf) } catch (_: Throwable) {}
            try { connection.close() } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------- enumeration

    fun listFiles(allowedExtensions: Set<String> = DEFAULT_MEDIA_EXTENSIONS): List<MtpFile> {
        val normalizedExtensions = allowedExtensions.map { it.lowercase() }.toSet()
        val all = mutableListOf<MtpFile>()
        val storageIds = getStorageIds()
        Log.i(TAG, "PTP storages=${storageIds.toList()}")
        for (storageId in storageIds) {
            // PTP semantics for `parent` vary by camera:
            //   - Nikon Z f: parent=0 returns ALL objects flat (files + dirs)
            //   - Other bodies: parent=0 may return only root-level children (just DCIM/)
            // Strategy: ask for parent=0, classify each handle by format. If we find
            //   any non-directory file, trust the flat listing. If every handle is a
            //   directory (FMT_ASSOCIATION), recurse into them.
            val rootHandles = getObjectHandles(storageId, format = 0, parent = 0)
            Log.i(TAG, "PTP storage=0x${storageId.toString(16)} rootHandles=${rootHandles.size}")
            if (rootHandles.isEmpty()) continue

            val rootInfos = rootHandles.toList().mapNotNull { h ->
                try { getObjectInfo(h) } catch (t: Throwable) {
                    Log.w(TAG, "GetObjectInfo($h) failed: ${t.message}")
                    null
                }
            }
            val rootNonDirCount = rootInfos.count { it.format != FMT_ASSOCIATION }
            val flatMode = rootNonDirCount > 0
            Log.i(TAG, "PTP storage=0x${storageId.toString(16)} flatMode=$flatMode (non-dirs at root=$rootNonDirCount)")

            if (flatMode) {
                // Flat listing — root already contains the files and usually the
                // directory association objects needed to reconstruct paths.
                val dirs = rootInfos
                    .filter { it.format == FMT_ASSOCIATION }
                    .associateBy { it.handle }
                for (info in rootInfos) {
                    if (info.format == FMT_ASSOCIATION) continue
                    if (info.extension in normalizedExtensions) {
                        all += info.copy(directoryPath = directoryPathFor(info.parentHandle, dirs))
                    }
                }
            } else {
                // Recursive traversal — walk subdirectories
                val stack = ArrayDeque<Pair<Int, String>>()
                for (info in rootInfos) {
                    if (info.format == FMT_ASSOCIATION) {
                        stack.addLast(info.handle to childPath("/", info.name))
                    }
                }
                while (stack.isNotEmpty()) {
                    val (parent, parentPath) = stack.removeLast()
                    val children = try {
                        getObjectHandles(storageId, format = 0, parent = parent)
                    } catch (t: Throwable) {
                        Log.w(TAG, "GetObjectHandles(parent=$parent) failed: ${t.message}")
                        continue
                    }
                    for (ch in children) {
                        val info = try { getObjectInfo(ch) } catch (_: Throwable) { null } ?: continue
                        if (info.format == FMT_ASSOCIATION) {
                            stack.addLast(ch to childPath(parentPath, info.name))
                        } else {
                            if (info.extension in normalizedExtensions) {
                                all += info.copy(directoryPath = parentPath)
                            }
                        }
                    }
                }
            }
        }
        return all
    }

    private fun directoryPathFor(parentHandle: Int, dirs: Map<Int, MtpFile>): String {
        if (parentHandle == 0 || parentHandle == -1) return "/"
        val parts = mutableListOf<String>()
        var current = parentHandle
        var guard = 0
        while (current != 0 && current != -1 && guard++ < 64) {
            val dir = dirs[current] ?: break
            parts += dir.name
            current = dir.parentHandle
        }
        return if (parts.isEmpty()) "/" else "/" + parts.asReversed().joinToString("/")
    }

    private fun childPath(parent: String, child: String): String {
        return if (parent == "/") "/$child" else "$parent/$child"
    }

    fun getStorageIds(): IntArray {
        return withTransportRecovery("GetStorageIDs") {
            sendCommand(OP_GET_STORAGE_IDS)
            val data = readData()
            val resp = readResponse()
            if (resp.code != RESP_OK) throw IOException("GetStorageIDs response 0x${resp.code.toString(16)}")
            // Data: uint32 count + count * uint32 storageId
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val count = bb.int
            IntArray(count) { bb.int }
        }
    }

    private fun getObjectHandles(storageId: Int, format: Int, parent: Int): IntArray {
        return withTransportRecovery("GetObjectHandles(storage=0x${storageId.toString(16)})") {
            sendCommand(OP_GET_OBJECT_HANDLES, intArrayOf(storageId, format, parent))
            val data = readData()
            val resp = readResponse()
            if (resp.code != RESP_OK) throw IOException("GetObjectHandles response 0x${resp.code.toString(16)}")
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val count = bb.int
            IntArray(count) { bb.int }
        }
    }

    private fun getObjectInfo(handle: Int): MtpFile? {
        return withTransportRecovery("GetObjectInfo($handle)") {
            sendCommand(OP_GET_OBJECT_INFO, intArrayOf(handle))
            val data = readData()
            val resp = readResponse()
            if (resp.code != RESP_OK) {
                Log.w(TAG, "GetObjectInfo($handle) bad response 0x${resp.code.toString(16)}")
                return@withTransportRecovery null
            }
            val result = parseObjectInfo(handle, data)
            if (result == null) {
                val hex = data.take(64).joinToString(" ") { "%02x".format(it) }
                Log.w(TAG, "GetObjectInfo($handle) parse null: dataSize=${data.size} hex=$hex")
            }
            result
        }
    }

    /**
     * Stream the object body straight to [output] using the largest practical
     * bulk reads. PTP container format: [12-byte header][N data bytes]. The 32-bit
     * length field caps a single object at ~4 GiB — fine for stills, may truncate
     * very long 4K MP4. We document the limit; a future iteration can switch to
     * GetPartialObject64 (op 0x95C1 on Nikon).
     */
    fun copyObjectTo(handle: Int, expectedSize: Long, output: OutputStream, onProgress: (Long) -> Unit) {
        sendCommand(OP_GET_OBJECT, intArrayOf(handle))

        // Read first chunk with a full-sized buffer to avoid -EOVERFLOW on USB 3.0
        // (device sends header + initial payload bytes in one USB packet).
        val buf = ByteArray(MAX_BULK_BYTES)
        val firstN = connection.bulkTransfer(bulkIn, buf, buf.size, OBJECT_TIMEOUT_MS)
        if (firstN < 12) throw IOException("GetObject: first read $firstN < 12")
        val length = (ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()) and 0xFFFFFFFFL
        val type = ByteBuffer.wrap(buf, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        if (type != TYPE_DATA) throw IOException("GetObject: expected data container, got type=$type")

        val dataLen = length - 12
        if (dataLen < 0) throw IOException("GetObject: dataLen<0 ($dataLen)")
        if (expectedSize > 0 && dataLen != expectedSize) {
            Log.w(TAG, "GetObject($handle): header data=$dataLen but ObjectInfo size=$expectedSize — using header value")
        }

        // Write payload bytes already received in the first read.
        val firstPayload = firstN - 12
        if (firstPayload > 0) {
            output.write(buf, 12, firstPayload)
            onProgress(firstPayload.toLong())
        }

        var read = firstPayload.toLong()
        while (read < dataLen) {
            val want = minOf(buf.size.toLong(), dataLen - read).toInt()
            val n = connection.bulkTransfer(bulkIn, buf, want, OBJECT_TIMEOUT_MS)
            if (n <= 0) throw IOException("GetObject: bulkTransfer returned $n at offset=$read of $dataLen")
            output.write(buf, 0, n)
            read += n
            onProgress(read)
        }

        val resp = readResponse()
        if (resp.code != RESP_OK) throw IOException("GetObject response 0x${resp.code.toString(16)}")
    }

    // -------------------------------------------------------------- internals

    private fun openSession(sessionId: Int) {
        nextTxn = 0
        sendCommand(OP_OPEN_SESSION, intArrayOf(sessionId))
        val resp = readResponse()
        when (resp.code) {
            RESP_OK -> {
                nextTxn = 1
                return
            }
            RESP_SESSION_ALREADY_OPEN -> {
                // Match ptp_keeper.c: if Nikon already has a session from the system
                // stack, don't reset immediately. The reset itself was the observed
                // trigger for bulk endpoint deadlock on Android 16.
                Log.w(TAG, "OpenSession → SessionAlreadyOpen; proceeding without reset")
                nextTxn = 1
                return
            }
            else -> throw IOException("OpenSession response 0x${resp.code.toString(16)}")
        }
    }

    /**
     * PTP class-specific Reset Device request (USB Still Image Capture, §10.6 of the
     * PIMA 15740 USB Implementation). Clears the responder's session state +
     * transaction queue. Some cameras require this when interface ownership has
     * changed mid-session.
     */
    private fun ptpClassReset() {
        try {
            val rc = connection.controlTransfer(
                /* requestType */ 0x21,  // class | host-to-device | interface
                /* request */ 0x66,
                /* value */ 0,
                /* index */ intf.id,
                null, 0,
                COMMAND_TIMEOUT_MS,
            )
            Log.i(TAG, "PTP class reset rc=$rc")
        } catch (t: Throwable) {
            Log.w(TAG, "PTP class reset failed: ${t.message}")
        }
    }

    private fun closeSession() {
        sendCommand(OP_CLOSE_SESSION)
        readResponse()
    }

    private inline fun <T> withTransportRecovery(opName: String, block: () -> T): T {
        return try {
            block()
        } catch (t: Throwable) {
            if (t !is IOException) throw t
            Log.w(TAG, "$opName failed: ${t.message}; attempting transport recovery")
            recoverTransport()
            block()
        }
    }

    private fun recoverTransport() {
        // NOTE: PTP class reset (controlTransfer 0x21/0x66) was tried and observed
        // to break the OUT pipe permanently (subsequent bulkTransfer wrote -1).
        // Instead we just re-clear stalls and drain. If that's not enough, caller
        // can fall through to a full USBDEVFS_RESET via [ioctlResetDevice].
        clearHalt(bulkOut)
        clearHalt(bulkIn)
        Thread.sleep(RECOVERY_SETTLE_MS)
        drainBulkIn()
        // We do NOT re-OpenSession here — the camera may still consider its session
        // valid, and re-issuing OpenSession in the wrong state historically tripped
        // SessionAlreadyOpen + cascading endpoint errors. Try the next operation
        // directly; if it fails again, the outer code will fall back to SAF.
        Log.i(TAG, "PTP transport recovered (clearHalt + drain)")
    }

    private fun clearHalt(endpoint: UsbEndpoint) {
        try {
            val rc = connection.controlTransfer(
                /* requestType */ 0x02, // standard | host-to-device | endpoint
                /* request */ 0x01,     // CLEAR_FEATURE
                /* value */ 0,          // ENDPOINT_HALT
                /* index */ endpoint.address,
                null, 0,
                COMMAND_TIMEOUT_MS,
            )
            Log.i(TAG, "CLEAR_FEATURE(ENDPOINT_HALT) ep${endpoint.address} rc=$rc")
        } catch (t: Throwable) {
            Log.w(TAG, "CLEAR_FEATURE ep${endpoint.address} failed: ${t.message}")
        }
    }

    private fun drainBulkIn() {
        val buf = ByteArray(MAX_BULK_BYTES)
        var drained = 0
        var reads = 0
        while (reads < RECOVERY_DRAIN_MAX_READS) {
            val n = connection.bulkTransfer(bulkIn, buf, buf.size, RECOVERY_DRAIN_TIMEOUT_MS)
            if (n <= 0) break
            drained += n
            reads++
        }
        Log.i(TAG, "drain bulk-in: reads=$reads bytes=$drained")
    }

    private fun sendCommand(code: Int, params: IntArray = intArrayOf()) {
        val length = 12 + params.size * 4
        val buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(length)
        buf.putShort(TYPE_COMMAND.toShort())
        buf.putShort(code.toShort())
        val txn = nextTxn
        buf.putInt(txn)
        for (p in params) buf.putInt(p)
        val n = connection.bulkTransfer(bulkOut, buf.array(), length, COMMAND_TIMEOUT_MS)
        if (n != length) throw IOException("PTP cmd 0x${code.toString(16)}: bulkTransfer wrote $n != $length")
        lastSentTxn = txn
        nextTxn = if (txn == 0) 1 else txn + 1
    }

    private data class Response(val code: Int, val params: IntArray)

    private fun readResponse(): Response {
        // Use a large buffer to avoid -EOVERFLOW on USB 3.0 SuperSpeed —
        // the device may bundle data + response in one packet.
        repeat(STALE_SKIP_MAX) {
            val buf = ByteArray(MAX_BULK_BYTES)
            val n = connection.bulkTransfer(bulkIn, buf, buf.size, COMMAND_TIMEOUT_MS)
            if (n < 12) throw IOException("PTP response: read $n bytes")
            val bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
            val length = bb.int
            val type = bb.short.toInt() and 0xFFFF
            val code = bb.short.toInt() and 0xFFFF
            val txn = bb.int
            if (txn != lastSentTxn) {
                Log.w(TAG, "stale PTP response: type=$type code=0x${code.toString(16)} txn=$txn (expected $lastSentTxn); discarding")
                return@repeat
            }
            if (type == TYPE_DATA) {
                // We were expecting a response but got data — this happens when
                // the responder finishes sending data + status in two containers
                // and we got the data half. Drain its payload then try again.
                val payload = (length - 12).toInt()
                if (payload > 0) skipBytes(payload)
                return@repeat
            }
            if (type != TYPE_RESPONSE) throw IOException("PTP response: type=$type expected $TYPE_RESPONSE")
            val pCount = (length - 12) / 4
            val params = IntArray(pCount) { bb.int }
            return Response(code, params)
        }
        throw IOException("PTP response: too many stale frames (lastSentTxn=$lastSentTxn)")
    }

    /** Read and discard [count] bytes from bulkIn. */
    private fun skipBytes(count: Int) {
        val buf = ByteArray(MAX_BULK_BYTES)
        var read = 0
        while (read < count) {
            val want = minOf(buf.size, count - read)
            val n = connection.bulkTransfer(bulkIn, buf, want, COMMAND_TIMEOUT_MS)
            if (n <= 0) return
            read += n
        }
    }

    /**
     * Read a complete PTP data container (header + body) into a single byte[].
     * Used for small payloads (StorageIDs, ObjectInfo, etc.) — large file payloads
     * stream via [copyObjectTo].
     *
     * IMPORTANT: on USB 3.0 SuperSpeed (maxPkt=1024), the device sends the entire
     * container as one USB packet when it fits. Reading only 12 bytes for the header
     * causes -EOVERFLOW if the container is larger. We therefore read the first chunk
     * with a full-sized buffer and parse header + (possibly partial) payload from it.
     */
    private fun readData(): ByteArray {
        repeat(STALE_SKIP_MAX) {
            val buf = ByteArray(MAX_BULK_BYTES)
            val n = connection.bulkTransfer(bulkIn, buf, buf.size, COMMAND_TIMEOUT_MS)
            if (n < 12) throw IOException("PTP data: first read $n < 12")
            val bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
            val length = bb.int.toLong() and 0xFFFFFFFFL
            val type = bb.short.toInt() and 0xFFFF
            bb.short // code (unused here)
            val txn = bb.int

            if (txn != lastSentTxn) {
                Log.w(TAG, "stale PTP container: type=$type txn=$txn (expected $lastSentTxn); discarding ${length}B")
                val remaining = (length - n).toInt()
                if (remaining > 0) skipBytes(remaining)
                return@repeat
            }
            if (type != TYPE_DATA) throw IOException("PTP data: type=$type expected $TYPE_DATA")
            val payloadLen = (length - 12).toInt()
            if (payloadLen <= 0) return ByteArray(0)

            val out = ByteArray(payloadLen)
            // Copy payload bytes already received in the first read.
            val already = minOf(n - 12, payloadLen)
            System.arraycopy(buf, 12, out, 0, already)
            var read = already
            while (read < payloadLen) {
                val want = minOf(MAX_BULK_BYTES, payloadLen - read)
                val got = connection.bulkTransfer(bulkIn, out, read, want, COMMAND_TIMEOUT_MS)
                if (got <= 0) throw IOException("PTP data: bulkTransfer $got at $read of $payloadLen")
                read += got
            }
            return out
        }
        throw IOException("PTP data: too many stale frames (lastSentTxn=$lastSentTxn)")
    }

    /**
     * Parse PTP/MTP ObjectInfo dataset. Layout (PIMA 15740 §13.7.5):
     *
     *   uint32 storageId
     *   uint16 objectFormat
     *   uint16 protectionStatus
     *   uint32 objectCompressedSize     (32-bit; for >4 GiB use ObjectInfo64 / props)
     *   uint16 thumbFormat ... thumbCompressedSize, thumbPixWidth/Height,
     *   uint32 imagePixWidth, imagePixHeight, imageBitDepth
     *   uint32 parentObject
     *   uint16 associationType        ← NOTE: uint16, not uint32!
     *   uint32 associationDesc, sequenceNumber
     *   PTP-string filename
     *   PTP-string captureDate         "YYYYMMDDThhmmss" (with optional ".s" fractional)
     *   PTP-string modificationDate
     *   PTP-string keywords
     */
    private fun parseObjectInfo(handle: Int, data: ByteArray): MtpFile? {
        if (data.size < 52) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val storageId = bb.int
        val format = bb.short.toInt() and 0xFFFF
        bb.short // protectionStatus
        val compressedSize = bb.int.toLong() and 0xFFFFFFFFL
        // Skip thumb fields + image dimensions + parent/association/sequence
        bb.short                          // thumbFormat (uint16)
        bb.int                            // thumbCompressedSize (uint32)
        bb.int; bb.int                    // thumbPixWidth, thumbPixHeight (uint32 x2)
        bb.int; bb.int; bb.int            // imagePixWidth, imagePixHeight, imageBitDepth (uint32 x3)
        val parentObject = bb.int        // parentObject (uint32)
        bb.short                          // associationType (uint16!)
        bb.int                            // associationDesc (uint32)
        bb.int                            // sequenceNumber (uint32)

        val name = readPtpString(bb) ?: return null
        val captureDate = readPtpString(bb)
        val modDate = readPtpString(bb)

        val captureMillis = parsePtpDate(modDate ?: captureDate)
        return MtpFile(
            handle = handle,
            name = name,
            size = compressedSize,
            dateModifiedMillis = captureMillis,
            format = format,
            storageId = storageId,
            parentHandle = parentObject,
        )
    }

    /**
     * Read a PTP string: 1 byte numChars (incl. null terminator), then numChars
     * UTF-16LE chars. numChars=0 → null.
     */
    private fun readPtpString(bb: ByteBuffer): String? {
        if (!bb.hasRemaining()) return null
        val numChars = bb.get().toInt() and 0xFF
        if (numChars == 0) return null
        val sb = StringBuilder(numChars)
        for (i in 0 until numChars) {
            if (bb.remaining() < 2) return null
            val c = bb.short.toInt() and 0xFFFF
            if (c == 0) {
                // Skip remaining of the string in the buffer (PTP strings are exactly numChars chars).
                val remaining = numChars - i - 1
                bb.position(bb.position() + remaining * 2)
                break
            }
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    /**
     * PTP date format: "YYYYMMDDThhmmss" or "YYYYMMDDThhmmss.s" (fractional second).
     * Returned as epoch millis interpreted in the device's CURRENT timezone — which
     * matches how cameras typically write it (camera-local time without TZ info).
     */
    private fun parsePtpDate(s: String?): Long {
        if (s.isNullOrEmpty()) return 0L
        return try {
            val core = if (s.length >= 15) s.substring(0, 15) else return 0L
            val sdf = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getDefault()
            sdf.parse(core)?.time ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }
}
