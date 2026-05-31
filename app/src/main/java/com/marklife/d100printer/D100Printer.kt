package com.marklife.d100printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Marklife D100 printer driver over Bluetooth classic SPP/RFCOMM.
 *
 * Protocol summary (from d100-porting-guide.md):
 *  1. Connect via RFCOMM to SPP UUID.
 *  2. Send 45-byte READY_COMMAND and wait for a valid 0x53/XOR status packet.
 *  3. Build a native raster payload (speed + density + JBIG1 image blocks).
 *  4. Transmit in 127-byte chunks with a 5 ms gap between chunks.
 */
@SuppressLint("MissingPermission")
class D100Printer(
    private val bluetoothAdapter: BluetoothAdapter,
    private val preferredAddress: String? = null,
    private val nativeWidthDots: Int = NATIVE_WIDTH_DOTS,
    private val paperType: Int = PAPER_TYPE_GAP,
) {

    private data class HandshakeData(
        val statusPackets: List<ByteArray>,
        val rawBytes: ByteArray,
    )

    data class ConnectionTestResult(
        val connected: Boolean,
        val deviceName: String,
        val deviceAddress: String,
        val statusPacketsHex: List<String>,
    )

    companion object {
        private const val TAG = "D100Printer"

        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        // Known D100 identifiers — used to locate the paired device without scanning.
        private const val D100_BT_ADDRESS = "02:26:5D:C0:1B:43"
        private const val D100_NAME_PREFIX = "D100"

        private const val NATIVE_WIDTH_DOTS = 864   // hardware page-width register
        const val PAPER_TYPE_CONTINUOUS = 1
        const val PAPER_TYPE_GAP = 2

        private const val MAX_BLOCK_HEIGHT = 255    // max rows per JBIG block
        private const val CHUNK_SIZE        = 127   // SPP write chunk size
        private const val CHUNK_DELAY_MS    = 14L
        private const val GRAY_THRESHOLD   = 135    // pixels ≤ threshold → black
        private const val HANDSHAKE_TIMEOUT_MS = 12_000L
        private const val HANDSHAKE_RETRIES = 2
        private const val DEVICE_INFO_READ_WINDOW_MS = 2_500L

        // 45-byte D100 ready handshake (verbatim from the working Node.js capture).
        private val READY_COMMAND = byteArrayOf(
            0x10, 0x05, 0xFF.b, 0x01, 0x02,
            0x10, 0x05, 0xFF.b, 0x01, 0x02,
            0x03, 0xFF.b, 0x20, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x08, 0x00,
            0xD4.b, 0x18, 0x44, 0x45, 0x56,
            0x49, 0x43, 0x45, 0x3F, 0x3F,
            0x1F, 0x28, 0x63, 0x0A, 0x00,
            0x1B, 0x40, 0xF2.b, 0x5B, 0x00,
            0x00, 0x46, 0xCB.b, 0x1B, 0x40,
        )

        // Observed in the ready stream; sending this explicitly can return extra device info.
        private val DEVICE_INFO_QUERY = byteArrayOf(0x1F, 0x28, 0x63, 0x0A, 0x00)
        private val DEVICE_INFO_ASCII_QUERY = "DEVICE??".toByteArray(Charsets.US_ASCII)

        private val PAGE_START = byteArrayOf(0x1A, 0x0C, 0xFF.toByte())
        private val PAGE_END = byteArrayOf(0x1A, 0x0C, 0x00) // page/continuous marker OFF

        // Extension property: concise Int → Byte conversion for literal byte arrays above.
        private val Int.b get() = toByte()
    }

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var lastHandshakePackets: List<ByteArray> = emptyList()
    private var lastHandshakeRawBytes: ByteArray = byteArrayOf()
    private var lastConnectedDeviceName: String = ""
    private var lastConnectedDeviceAddress: String = ""

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Connects to the D100 over Bluetooth SPP and waits for the ready handshake.
     * The printer must be paired beforehand (PIN: 0000).
     *
     * @throws IOException if the device is not found or the handshake times out.
     */
    fun connect(scanTimeoutMs: Long = 15_000) {
        val device = findDevice(scanTimeoutMs)
        lastConnectedDeviceName = device.name ?: "Unknown"
        lastConnectedDeviceAddress = device.address
        var lastError: Exception? = null

        for ((mode, factory) in buildSocketAttempts(device)) {
            var sock: BluetoothSocket? = null
            try {
                disconnect()
                Log.d(TAG, "Connecting to ${device.address} using $mode …")

                bluetoothAdapter.cancelDiscovery()
                sock = factory()
                sock.connect()

                socket = sock
                output = sock.outputStream
                input = sock.inputStream

                val handshake = waitUntilReadyWithRetry(HANDSHAKE_TIMEOUT_MS)
                lastHandshakePackets = handshake.statusPackets
                lastHandshakeRawBytes = handshake.rawBytes
                Log.d(TAG, "D100 ready handshake complete using $mode.")
                return
            } catch (error: Exception) {
                lastError = error
                Log.w(TAG, "Connection attempt failed with $mode: ${error.message}")
                try {
                    sock?.close()
                } catch (_: IOException) {
                }
                disconnect()
            }
        }

        throw IOException(
            "Failed to connect to printer. Socket might be closed or timed out: ${lastError?.message}",
            lastError
        )
    }

    /**
     * Connects to the printer, performs the ready handshake, then returns diagnostic data.
     * This is intended for a Settings "Test connection" workflow.
     */
    fun testConnection(scanTimeoutMs: Long = 15_000): ConnectionTestResult {
        connect(scanTimeoutMs)
        return try {
            val extraInfo = requestDeviceInfo()
            // Keep the probe side effect (some devices only answer after explicit query),
            // but do not expose SN/FW in the UI for now.
            if (extraInfo.isNotEmpty()) {
                lastHandshakeRawBytes = ByteArray(lastHandshakeRawBytes.size + extraInfo.size).also { out ->
                    lastHandshakeRawBytes.copyInto(out, 0)
                    extraInfo.copyInto(out, lastHandshakeRawBytes.size)
                }
            }

            val packetHex = lastHandshakePackets.map { packet -> packet.toHexString() }
            ConnectionTestResult(
                connected = true,
                deviceName = lastConnectedDeviceName,
                deviceAddress = lastConnectedDeviceAddress,
                statusPacketsHex = packetHex,
            )
        } finally {
            disconnect()
        }
    }

    /**
     * Sends a single label bitmap to the printer.
     * The bitmap should already be scaled to [PRINT_WIDTH_DOTS] × [PRINT_HEIGHT_DOTS].
     */
    fun printBitmap(bitmap: Bitmap) {
        val out = output ?: throw IOException("Not connected — call connect() first.")
        val payload = buildPayload(bitmap)
        Log.d(TAG, "Sending ${payload.size} bytes in ${CHUNK_SIZE}-byte chunks.")
        sendChunked(out, payload)
    }

    /** Closes the RFCOMM socket. Safe to call even if not connected. */
    fun disconnect() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        output = null
        input  = null
        lastHandshakePackets = emptyList()
        lastHandshakeRawBytes = byteArrayOf()
        Log.d(TAG, "Disconnected.")
    }

    // -------------------------------------------------------------------------
    // Device discovery
    // -------------------------------------------------------------------------

    private fun findDevice(timeoutMs: Long): BluetoothDevice {
        // Prefer the bonded device list — avoids an explicit Bluetooth scan.
        val bonded = bluetoothAdapter.bondedDevices ?: emptySet()

        preferredAddress?.let { targetAddress ->
            bonded.find { it.address.equals(targetAddress, ignoreCase = true) }
                ?.let { return it }

            throw IOException(
                "Selected printer $targetAddress is not in paired devices. " +
                "Open Settings and choose a paired printer."
            )
        }

        bonded.find { it.address.equals(D100_BT_ADDRESS, ignoreCase = true) }
            ?.let { return it }

        bonded.find { it.name?.startsWith(D100_NAME_PREFIX) == true }
            ?.let {
                Log.d(TAG, "Found bonded D100: ${it.name} (${it.address})")
                return it
            }

        // Fallback: start discovery and poll the bonded list for newly paired devices.
        Log.d(TAG, "D100 not in bonded list — starting discovery.")
        bluetoothAdapter.startDiscovery()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            bluetoothAdapter.bondedDevices
                ?.find { it.name?.startsWith(D100_NAME_PREFIX) == true }
                ?.let {
                    bluetoothAdapter.cancelDiscovery()
                    return it
                }
            Thread.sleep(300)
        }
        bluetoothAdapter.cancelDiscovery()
        throw IOException(
            "D100 printer not found. Make sure it is powered on and paired " +
            "(Bluetooth name starts with \"D100\", PIN: 0000)."
        )
    }

    // -------------------------------------------------------------------------
    // Ready handshake
    // -------------------------------------------------------------------------

    private fun waitUntilReady(timeoutMs: Long): HandshakeData {
        val inp = input ?: throw IOException("Input stream unavailable.")
        output!!.write(READY_COMMAND)
        output!!.flush()

        val rxBuf = mutableListOf<Byte>()
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            repeat(inp.available()) { rxBuf.add(inp.read().toByte()) }
            val packets = extractValidReadyPackets(rxBuf)
            if (packets.isNotEmpty()) {
                val tailDeadline = System.currentTimeMillis() + 800
                while (System.currentTimeMillis() < tailDeadline) {
                    repeat(inp.available()) { rxBuf.add(inp.read().toByte()) }
                    Thread.sleep(50)
                }

                return HandshakeData(
                    statusPackets = extractValidReadyPackets(rxBuf),
                    rawBytes = rxBuf.toByteArray(),
                )
            }
            Thread.sleep(200)
        }

        throw IOException(
            "D100 ready handshake timed out — printer did not send the expected " +
            "0x53/XOR status packet within ${timeoutMs}ms."
        )
    }

    private fun waitUntilReadyWithRetry(timeoutMs: Long): HandshakeData {
        var lastError: IOException? = null
        var data = HandshakeData(emptyList(), byteArrayOf())

        repeat(HANDSHAKE_RETRIES) { attempt ->
            try {
                // Some units send stale bytes right after connect; flush before handshake.
                val inp = input
                if (inp != null) {
                    while (inp.available() > 0) {
                        inp.read()
                    }
                }
                Thread.sleep(200)
                data = waitUntilReady(timeoutMs)
                return data
            } catch (error: IOException) {
                lastError = error
                if (attempt < HANDSHAKE_RETRIES - 1) {
                    Thread.sleep(250)
                }
            }
        }

        throw lastError ?: IOException("Ready handshake failed.")
    }

    /**
     * Returns true when the buffer contains a packet that:
     *  - starts with 0x53
     *  - XOR of every byte in the packet equals 0x00
     */
    private fun extractValidReadyPackets(buf: List<Byte>): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        var start = 0

        while (start < buf.size) {
            if (buf[start] != 0x53.toByte()) {
                start++
                continue
            }

            var xor = 0
            var foundEnd = -1
            for (end in start until buf.size) {
                xor = xor xor (buf[end].toInt() and 0xFF)
                if (xor == 0 && end > start) {
                    foundEnd = end
                    break
                }
            }

            if (foundEnd > start) {
                packets.add(buf.subList(start, foundEnd + 1).toByteArray())
                start = foundEnd + 1
            } else {
                start++
            }
        }

        return packets
    }

    // -------------------------------------------------------------------------
    // Payload construction
    // -------------------------------------------------------------------------

    private fun buildPayload(bitmap: Bitmap): ByteArray {
        val parts = mutableListOf<ByteArray>()
        parts.add(buildPrintHeader(nativeWidthDots))
        if (paperType == PAPER_TYPE_GAP) {
            parts.add(PAGE_START)
        }

        var y = 0
        while (y < bitmap.height) {
            val blockH = minOf(MAX_BLOCK_HEIGHT, bitmap.height - y)
            val pixels  = bitmapSliceToPackedBits(bitmap, y, blockH)
            val jbig    = JbigEncoder.encode(pixels, bitmap.width, blockH)
            parts.add(buildJbigBlockHeader(jbig, bitmap.width, blockH))
            parts.add(jbig)
            y += blockH
        }

        if (paperType == PAPER_TYPE_GAP) {
            parts.add(PAGE_END)
        }
        return parts.concat()
    }

    private fun buildPrintHeader(widthDots: Int): ByteArray {
        val clampedWidth = widthDots.coerceIn(1, 0xFFFF)
        // Gap media can require gentler pacing on some D100 firmware revisions.
        val speed = if (paperType == PAPER_TYPE_GAP) 0x64 else 0x96
        return byteArrayOf(
            0x1F, 0x28, 0x73, 0x02, 0x00, speed.toByte(), 0x00, // speed tuned by media
            0x12, 0x23, 0x07,                                    // density = 7
            0x1D, 0x4C, 0x00, 0x00,                              // left margin = 0
            0x1D, 0x57,
            (clampedWidth and 0xFF).toByte(),
            ((clampedWidth shr 8) and 0xFF).toByte(),
            0x1B, 0x61, 0x01,                                    // alignment = centred
        )
    }

    /**
     * Converts a horizontal slice of [bitmap] starting at [yStart] with [height] rows
     * into a 1-bpp packed big-endian byte array (MSB = leftmost pixel).
     * Pixels with luminance ≤ [GRAY_THRESHOLD] are rendered as black (bit = 1).
     */
    private fun bitmapSliceToPackedBits(bitmap: Bitmap, yStart: Int, height: Int): ByteArray {
        val width      = bitmap.width
        val rowBytes   = (width + 7) / 8
        val out        = ByteArray(rowBytes * height)

        for (row in 0 until height) {
            for (x in 0 until width) {
                val px    = bitmap.getPixel(x, yStart + row)
                val lum   = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3
                if (lum <= GRAY_THRESHOLD) {
                    out[row * rowBytes + x / 8] =
                        (out[row * rowBytes + x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
                }
            }
        }
        return out
    }

    /**
     * Builds the 8-byte `1F 28 4A` block header that precedes each JBIG stream.
     * Layout: opcode(3) | length LE16 | width LE16 | height(1 byte)
     * Length = jbig.size + 3  (covers the width + height fields).
     */
    private fun buildJbigBlockHeader(jbig: ByteArray, width: Int, height: Int): ByteArray {
        val length = jbig.size + 3
        require(length <= 0xFFFF) { "JBIG block too large: $length bytes" }
        require(width  <= 0xFFFF) { "Block width too large: $width"       }
        require(height <=   0xFF) { "Block height too large: $height"     }

        return byteArrayOf(
            0x1F, 0x28, 0x4A,
            (length        and 0xFF).toByte(),
            ((length shr 8) and 0xFF).toByte(),
            (width         and 0xFF).toByte(),
            ((width  shr 8) and 0xFF).toByte(),
            height.toByte(),
        )
    }

    // -------------------------------------------------------------------------
    // Serial transmission
    // -------------------------------------------------------------------------

    private fun sendChunked(out: OutputStream, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + CHUNK_SIZE, data.size)
            var sent = false
            var lastError: IOException? = null

            repeat(2) { attempt ->
                if (sent) return@repeat
                try {
                    out.write(data, offset, end - offset)
                    out.flush()
                    sent = true
                } catch (error: IOException) {
                    lastError = error
                    if (attempt == 0) {
                        Thread.sleep(120)
                    }
                }
            }

            if (!sent) {
                throw IOException(
                    "Bluetooth write failed near byte $offset/${data.size}: ${lastError?.message}",
                    lastError
                )
            }

            offset = end
            Thread.sleep(CHUNK_DELAY_MS)
        }
    }

    private fun buildSocketAttempts(device: BluetoothDevice): List<Pair<String, () -> BluetoothSocket>> {
        val attempts = mutableListOf<Pair<String, () -> BluetoothSocket>>()

        attempts += "secure-rfcomm-uuid" to { device.createRfcommSocketToServiceRecord(SPP_UUID) }

        attempts += "insecure-rfcomm-uuid" to {
            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        }

        attempts += "rfcomm-channel-1-reflection" to {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            method.invoke(device, 1) as BluetoothSocket
        }

        return attempts
    }

    private fun parseFirmwareVersion(rawBytes: ByteArray): String? {
        val text = rawBytes.toPrintableAscii()
        val stf = Regex("STF[0-9A-Za-z\\.\\-]+", RegexOption.IGNORE_CASE).find(text)?.value
        if (stf != null) return stf

        val generic = Regex("(?:FW|FIRMWARE)\\s*[:=]?\\s*([0-9A-Za-z\\.\\-]{4,})", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
        return generic
    }

    private fun parseSerialNumber(rawBytes: ByteArray): String? {
        val texts = buildList {
            add(rawBytes.toPrintableAscii())
            add(String(rawBytes, Charsets.UTF_16LE))
            add(String(rawBytes, Charsets.UTF_16BE))
        }

        for (text in texts) {
            val tagged = Regex("SN\\s*[:=]?\\s*([0-9A-Za-z\\-]{6,})", RegexOption.IGNORE_CASE)
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
            if (!tagged.isNullOrBlank()) return tagged

            val snLike = Regex("(?<!\\d)(\\d{8,20})(?!\\d)")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
            if (!snLike.isNullOrBlank()) return snLike
        }

        // Some firmwares may return compact numeric identifiers in BCD-like bytes.
        val bcdRun = parseBcdDigitRun(rawBytes)
        if (!bcdRun.isNullOrBlank()) return bcdRun

        return null
    }

    private fun parseBcdDigitRun(bytes: ByteArray): String? {
        var best: String? = null
        var current = StringBuilder()

        fun flushCurrent() {
            val candidate = current.toString()
            if (candidate.length >= 8 && candidate.any { it != '0' }) {
                if (best == null || candidate.length > best!!.length) {
                    best = candidate
                }
            }
            current = StringBuilder()
        }

        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            val hi = (value shr 4) and 0x0F
            val lo = value and 0x0F

            if (hi <= 9 && lo <= 9) {
                current.append(('0'.code + hi).toChar())
                current.append(('0'.code + lo).toChar())
            } else {
                flushCurrent()
            }
        }

        flushCurrent()
        return best
    }

    private fun requestDeviceInfo(): ByteArray {
        val out = output ?: return byteArrayOf()

        return try {
            out.write(DEVICE_INFO_QUERY)
            out.flush()
            Thread.sleep(80)

            out.write(DEVICE_INFO_ASCII_QUERY)
            out.flush()

            readAvailableBytes(DEVICE_INFO_READ_WINDOW_MS)
        } catch (_: Exception) {
            byteArrayOf()
        }
    }

    private fun readAvailableBytes(windowMs: Long): ByteArray {
        val inp = input ?: return byteArrayOf()
        val collected = mutableListOf<Byte>()
        val deadline = System.currentTimeMillis() + windowMs

        while (System.currentTimeMillis() < deadline) {
            var readAny = false
            while (inp.available() > 0) {
                collected.add(inp.read().toByte())
                readAny = true
            }

            if (!readAny) {
                Thread.sleep(40)
            }
        }

        return collected.toByteArray()
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = " ") { byte -> "%02X".format(byte.toInt() and 0xFF) }

    private fun ByteArray.toPrintableAscii(): String =
        map { byte -> byte.toInt() and 0xFF }
            .map { value -> if (value in 32..126) value.toChar() else ' ' }
            .joinToString(separator = "")
            .replace(Regex("\\s+"), " ")
            .trim()

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private fun List<ByteArray>.concat(): ByteArray {
        val total = sumOf { it.size }
        val out   = ByteArray(total)
        var pos   = 0
        for (arr in this) { arr.copyInto(out, pos); pos += arr.size }
        return out
    }
}
