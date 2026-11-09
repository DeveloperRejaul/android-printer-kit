package com.rezaul.printerkit

import android.bluetooth.BluetoothSocket
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * The only thing the ESC/POS printing code needs from a connection: somewhere to write bytes
 * (and optionally read status bytes back). Bluetooth, Wi-Fi/LAN and USB each provide one, so
 * printing code stays identical.
 */
internal interface PrinterTransport : Closeable {
    val outputStream: OutputStream
    val isConnected: Boolean

    /** Reads up to [buffer].size bytes, waiting at most [timeoutMs]. Returns 0 if nothing arrived, -1 if reading is unsupported. */
    fun read(buffer: ByteArray, timeoutMs: Int): Int
}

/** Polls an [InputStream] without blocking forever (socket streams have no read timeout of their own). */
private fun InputStream.readWithTimeout(buffer: ByteArray, timeoutMs: Int): Int {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (available() > 0) return read(buffer)
        Thread.sleep(20)
    }
    return 0
}

internal class BluetoothTransport(private val socket: BluetoothSocket) : PrinterTransport {
    override val outputStream: OutputStream get() = socket.outputStream
    override val isConnected: Boolean get() = socket.isConnected
    override fun read(buffer: ByteArray, timeoutMs: Int) = socket.inputStream.readWithTimeout(buffer, timeoutMs)
    override fun close() = socket.close()
}

internal class NetworkTransport(private val socket: Socket) : PrinterTransport {
    override val outputStream: OutputStream get() = socket.getOutputStream()
    override val isConnected: Boolean get() = socket.isConnected && !socket.isClosed && !socket.isOutputShutdown
    override fun read(buffer: ByteArray, timeoutMs: Int) = socket.getInputStream().readWithTimeout(buffer, timeoutMs)
    override fun close() = socket.close()
}

internal class UsbTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint?
) : PrinterTransport {

    @Volatile private var closed = false

    override val isConnected: Boolean get() = !closed

    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("USB printer closed")
            var sent = 0
            while (sent < len) {
                val chunk = minOf(WRITE_CHUNK, len - sent)
                val result = connection.bulkTransfer(outEndpoint, b, off + sent, chunk, WRITE_TIMEOUT_MS)
                if (result < 0) throw IOException("USB write failed")
                sent += result
            }
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val endpoint = inEndpoint ?: return -1
        // bulkTransfer treats a timeout of 0 as "wait forever" (unlike the Bluetooth/TCP transports, where 0
        // means "don't wait"), so a non-blocking poll must use the shortest real timeout instead.
        val result = connection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs.coerceAtLeast(1))
        return if (result < 0) 0 else result
    }

    override fun close() {
        if (closed) return
        closed = true
        try { connection.releaseInterface(usbInterface) } finally { connection.close() }
    }

    private companion object {
        const val WRITE_CHUNK = 4096
        const val WRITE_TIMEOUT_MS = 5000
    }
}
