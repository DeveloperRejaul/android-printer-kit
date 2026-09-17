package com.rezaul.printerkit

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * A paired (bonded) Bluetooth printer.
 */
data class BluetoothPrinterDevice(
    val name: String?,
    val address: String
)

/**
 * Output image format for [BluetoothPrinter.pdfToImage].
 */
enum class PrinterImageType { PNG, JPEG }

/** Parameters for [BluetoothPrinter.connectPrinter] / [BluetoothPrinterService.connectAndKeepAlive]. */
data class ConnectPrinterParams(val address: String)

/** Parameters for [BluetoothPrinter.printText]. */
data class PrintTextParams(val text: String, val feedLines: Int = 3)

/**
 * Parameters for [BluetoothPrinter.printImageBitmap]. [bandHeightDots]/[bandDelayMs] control
 * how the image is paced to the printer - see [BluetoothPrinter.printImageBitmap] for why.
 * Lower [bandHeightDots] and/or raise [bandDelayMs] for printers with a small receive buffer
 * that drop or garble large images.
 */
data class PrintImageBitmapParams(
    val bitmap: Bitmap,
    val printerWidthDots: Int = 384,
    val feedLines: Int = 3,
    val bandHeightDots: Int = 16,
    val bandDelayMs: Long = 60L
)

/** Parameters for [BluetoothPrinter.printImageFile]. See [PrintImageBitmapParams] for [bandHeightDots]/[bandDelayMs]. */
data class PrintImageFileParams(
    val imagePath: String,
    val printerWidthDots: Int = 384,
    val feedLines: Int = 3,
    val bandHeightDots: Int = 16,
    val bandDelayMs: Long = 60L
)

/** Parameters for [BluetoothPrinter.printImageBase64]. See [PrintImageBitmapParams] for [bandHeightDots]/[bandDelayMs]. */
data class PrintImageBase64Params(
    val base64: String,
    val printerWidthDots: Int = 384,
    val feedLines: Int = 3,
    val bandHeightDots: Int = 16,
    val bandDelayMs: Long = 60L
)

/** Parameters for [BluetoothPrinter.pdfToImage]. [outputDir] defaults to the app's cache dir when null. */
data class PdfToImageParams(
    val pdfPath: String,
    val imageType: PrinterImageType = PrinterImageType.PNG,
    val page: Int = 0,
    val targetWidthPx: Int? = null,
    val outputDir: String? = null
)

/** Parameters for [BluetoothPrinter.printPdf]. See [PrintImageBitmapParams] for [bandHeightDots]/[bandDelayMs]. */
data class PrintPdfParams(
    val pdfPath: String,
    val printerWidthDots: Int = 384,
    val page: Int = 0,
    val feedLines: Int = 3,
    val bandHeightDots: Int = 16,
    val bandDelayMs: Long = 60L
)

/**
 * Parameters for [BluetoothPrinter.htmlToPdf]. [outputPath] defaults to a fresh file
 * under the app's cache dir when null. See [BluetoothPrinter.htmlToPdf] for what
 * [pageWidthDp]/[heightDp]/[minPageHeightDp] control.
 */
data class HtmlToPdfParams(
    val html: String,
    val outputPath: String? = null,
    val pageWidthDp: Int = 412,
    val heightDp: Int? = null,
    val minPageHeightDp: Int = 1000
)

/**
 * Parameters for [BluetoothPrinter.printHtml]. See [HtmlToPdfParams] for the HTML sizing
 * fields and [PrintImageBitmapParams] for [bandHeightDots]/[bandDelayMs].
 */
data class PrintHtmlParams(
    val html: String,
    val printerWidthDots: Int = 384,
    val pageWidthDp: Int = 412,
    val heightDp: Int? = null,
    val minPageHeightDp: Int = 1000,
    val bandHeightDots: Int = 16,
    val bandDelayMs: Long = 60L
)

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/**
 * Talks to a Bluetooth Classic (SPP) ESC/POS thermal/POS printer that is already
 * paired with the device via Android's Bluetooth settings.
 *
 * Printing is done in two ways:
 * - [printText]: raw ESC/POS text using the printer's built-in font (ASCII only).
 * - [printImage] / [printPdf]: renders content to a bitmap and sends it as an
 *   ESC/POS raster image, which is required for anything with non-Latin text
 *   (e.g. Bangla) since POS printer fonts don't support it.
 *
 * All connection and printing calls perform blocking I/O and must be invoked
 * from a background thread.
 */
class BluetoothPrinter(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    private var socket: BluetoothSocket? = null
    private var connectedDevice: BluetoothDevice? = null

    // Remembers the last successfully connected printer's address, so a fresh
    // process (e.g. app reopened after being fully killed) can reconnect to the
    // same printer automatically instead of the caller having to pick it again -
    // see autoConnectIfAvailable().
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // BluetoothSocket.isConnected() (see isConnectedPrinter()) never updates itself -
    // it stays true even after the printer physically drops the link, since nothing
    // tells it to. Listening for the system's own ACL-disconnect broadcast lets us
    // notice a real disconnect immediately and clear the socket, so isConnectedPrinter()
    // reports the truth instead of a stale flag. Registered for the app's lifetime,
    // same as this class's other application-scoped state - there's nothing to leak.
    private val connectionWatcher = object : BroadcastReceiver() {
        override fun onReceive(receivedContext: Context, intent: Intent) {
            val device = getDeviceExtra(intent) ?: return
            if (device.address == connectedDevice?.address) {
                Log.w(TAG, "Printer ${device.address} physically disconnected")
                closeSocketQuietly()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            connectionWatcher,
            IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * Returns all Bluetooth devices already paired with this phone/tablet.
     * On Android 12+ this requires the BLUETOOTH_CONNECT runtime permission;
     * if it hasn't been granted, an empty list is returned.
     */
    @SuppressLint("MissingPermission")
    fun getBondedBluetoothPrinters(): List<BluetoothPrinterDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        return try {
            adapter.bondedDevices.map { BluetoothPrinterDevice(it.name, it.address) }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission", e)
            emptyList()
        }
    }

    /**
     * Opens an RFCOMM (Serial Port Profile) socket to the paired device at [params]'s
     * address. Any existing connection is closed first. Returns true on success.
     *
     * Tries the standard SDP-based socket first, then falls back to a direct RFCOMM
     * channel via reflection - many cheap ESC/POS boards have a broken/slow SDP service
     * record that makes [BluetoothDevice.createRfcommSocketToServiceRecord] fail or hang
     * even though the printer is reachable, and this fallback is the standard workaround.
     */
    @SuppressLint("MissingPermission")
    fun connectPrinter(params: ConnectPrinterParams): Boolean {
        val adapter = bluetoothAdapter ?: return false
        val wasConnected = isConnectedPrinter()
        disconnectPrinter()
        if (wasConnected) {
            // Give the printer's own Bluetooth stack a moment to notice the ACL
            // disconnect and free its (usually single) connection slot before asking
            // it to accept a new one - reconnecting instantly after closing the old
            // socket can otherwise get rejected on cheap boards.
            Thread.sleep(RECONNECT_SETTLE_MS)
        }

        val device = try {
            adapter.getRemoteDevice(params.address)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid printer address ${params.address}", e)
            return false
        }
        // Best-effort: speeds up connect if discovery happens to be running, but
        // requires BLUETOOTH_SCAN (not BLUETOOTH_CONNECT) on API 31+, which this
        // library doesn't otherwise need - never let its absence block connecting.
        try {
            adapter.cancelDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "cancelDiscovery() skipped (missing BLUETOOTH_SCAN permission)", e)
        }

        if (tryOpenSocket(device) { device.createRfcommSocketToServiceRecord(SPP_UUID) }) return true
        Log.w(TAG, "SDP-based connect failed for ${params.address}; trying direct RFCOMM channel fallback")
        return tryOpenSocket(device) { createFallbackRfcommSocket(device) }
    }

    /** Opens [openSocket] and connects it; on success stores it as the active connection. */
    private fun tryOpenSocket(device: BluetoothDevice, openSocket: () -> BluetoothSocket): Boolean {
        return try {
            val newSocket = openSocket()
            newSocket.connect()
            socket = newSocket
            connectedDevice = device
            prefs.edit().putString(KEY_LAST_ADDRESS, device.address).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "connect attempt failed for ${device.address}", e)
            closeSocketQuietly()
            false
        }
    }

    /**
     * Opens an RFCOMM socket on a fixed channel via the hidden `createRfcommSocket(int)`
     * API, bypassing SDP lookup entirely. Channel 1 is what SPP profiles conventionally
     * use and is the standard fallback for printers with broken SDP records.
     */
    private fun createFallbackRfcommSocket(device: BluetoothDevice): BluetoothSocket {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(device, 1) as BluetoothSocket
    }

    /**
     * Reconnects to the printer this device was last successfully connected to
     * (persisted by [connectPrinter]), if any and if not already connected. Intended
     * to be called once when a fresh process starts (e.g. [BluetoothPrinterService]'s
     * creation) so the app doesn't need the user to re-pick the printer every time it
     * was fully killed and reopened. Returns true if connected (already, or just now).
     */
    fun autoConnectIfAvailable(): Boolean {
        if (isConnectedPrinter()) return true
        val address = prefs.getString(KEY_LAST_ADDRESS, null) ?: return false
        return connectPrinter(ConnectPrinterParams(address))
    }

    /** Closes the current printer connection, if any, and forgets it for [autoConnectIfAvailable]. Safe to call when already disconnected. */
    fun disconnectPrinter() {
        closeSocketQuietly()
        prefs.edit().remove(KEY_LAST_ADDRESS).apply()
    }

    /** True if a printer socket is currently open. */
    fun isConnectedPrinter(): Boolean = socket?.isConnected == true

    /** The currently connected printer, or null if none. */
    @SuppressLint("MissingPermission")
    fun getConnectedPrinter(): BluetoothPrinterDevice? {
        val device = connectedDevice ?: return null
        if (!isConnectedPrinter()) return null
        return BluetoothPrinterDevice(device.name, device.address)
    }

    /**
     * Sends raw text to the printer using its built-in font (ASCII only - not
     * suitable for Bangla or other non-Latin scripts, use [printImageBitmap] for that).
     */
    fun printText(params: PrintTextParams) {
        writeWithRetry {
            val out = requireSocket().outputStream
            out.write(ESC_INIT)
            out.write(params.text.toByteArray(Charsets.US_ASCII))
            out.write(byteArrayOf(0x0A))
            out.write(ByteArray(params.feedLines) { 0x0A })
            out.flush()
        }
    }

    /**
     * Prints [PrintImageBitmapParams.bitmap] as an ESC/POS raster image, scaled to
     * [PrintImageBitmapParams.printerWidthDots] (384 for common 58mm printers, 576
     * for 80mm printers).
     *
     * Sent as separate `GS v 0` commands per [PrintImageBitmapParams.bandHeightDots]-tall
     * horizontal band, with a [PrintImageBitmapParams.bandDelayMs] pause between each,
     * rather than one command for the whole image. Many cheap ESC/POS boards have a small
     * receive buffer and either truncate or silently drop an oversized/bursty raster command
     * (connect + write both report success, but nothing comes out) - banding keeps each
     * command small and paced. Lower [PrintImageBitmapParams.bandHeightDots] and/or raise
     * [PrintImageBitmapParams.bandDelayMs] if a printer still drops or garbles large images.
     */
    fun printImageBitmap(params: PrintImageBitmapParams) {
        val scaled = scaleToWidth(params.bitmap, params.printerWidthDots)
        try {
            writeWithRetry {
                val out = requireSocket().outputStream
                out.write(ESC_INIT)
                out.flush()

                var y = 0
                while (y < scaled.height) {
                    val bandHeight = minOf(params.bandHeightDots, scaled.height - y)
                    val band = Bitmap.createBitmap(scaled, 0, y, scaled.width, bandHeight)
                    try {
                        out.write(toEscPosRaster(band))
                        out.flush()
                    } finally {
                        band.recycle()
                    }
                    Thread.sleep(params.bandDelayMs)
                    y += bandHeight
                }

                out.write(ByteArray(params.feedLines) { 0x0A })
                out.flush()
            }
        } finally {
            if (scaled !== params.bitmap) scaled.recycle()
        }
    }

    /** Decodes the image file at [PrintImageFileParams.imagePath] and prints it. */
    fun printImageFile(params: PrintImageFileParams) {
        val bitmap = BitmapFactory.decodeFile(params.imagePath)
            ?: throw IllegalArgumentException("Could not decode image: ${params.imagePath}")
        try {
            printImageBitmap(
                PrintImageBitmapParams(
                    bitmap, params.printerWidthDots, params.feedLines, params.bandHeightDots, params.bandDelayMs
                )
            )
        } finally {
            bitmap.recycle()
        }
    }

    /** Decodes a base64-encoded image and prints it. */
    fun printImageBase64(params: PrintImageBase64Params) {
        val bytes = Base64.decode(params.base64, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("Could not decode base64 image")
        try {
            printImageBitmap(
                PrintImageBitmapParams(
                    bitmap, params.printerWidthDots, params.feedLines, params.bandHeightDots, params.bandDelayMs
                )
            )
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Renders one page of a PDF file to an image file using Android's built-in
     * [PdfRenderer] (no external dependency). Returns the absolute path of the
     * saved image.
     */
    fun pdfToImage(params: PdfToImageParams): String {
        val file = File(params.pdfPath)
        require(file.exists()) { "PDF file not found: ${params.pdfPath}" }
        val outputDir = params.outputDir ?: context.cacheDir.absolutePath

        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(params.page in 0 until renderer.pageCount) { "Invalid page index: ${params.page}" }
                renderer.openPage(params.page).use { pdfPage ->
                    val scale = if (params.targetWidthPx != null) {
                        params.targetWidthPx.toFloat() / pdfPage.width
                    } else {
                        2f
                    }
                    val width = (pdfPage.width * scale).toInt().coerceAtLeast(1)
                    val height = (pdfPage.height * scale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                    val extension = if (params.imageType == PrinterImageType.PNG) "png" else "jpg"
                    val outFile = File(outputDir, "print_${System.currentTimeMillis()}.$extension")
                    FileOutputStream(outFile).use { fos ->
                        val format = if (params.imageType == PrinterImageType.PNG) {
                            Bitmap.CompressFormat.PNG
                        } else {
                            Bitmap.CompressFormat.JPEG
                        }
                        bitmap.compress(format, 90, fos)
                    }
                    bitmap.recycle()
                    return outFile.absolutePath
                }
            }
        }
    }

    /** Renders [PrintPdfParams.pdfPath] to an image (via [pdfToImage]), then prints it. The temp image is deleted afterwards. */
    fun printPdf(params: PrintPdfParams) {
        val imagePath = pdfToImage(
            PdfToImageParams(
                pdfPath = params.pdfPath,
                imageType = PrinterImageType.PNG,
                page = params.page,
                targetWidthPx = params.printerWidthDots
            )
        )
        try {
            printImageFile(
                PrintImageFileParams(
                    imagePath, params.printerWidthDots, params.feedLines, params.bandHeightDots, params.bandDelayMs
                )
            )
        } finally {
            File(imagePath).delete()
        }
    }

    /**
     * Converts [HtmlToPdfParams.html] to a PDF file using Android's built-in WebView print
     * framework (see [HtmlToPdf], no external library). WebView requires the main thread, so
     * this is safe to call from any thread - it hops internally - and [onResult] (the saved
     * PDF's absolute path, or null on failure) always arrives on the main thread.
     *
     * See [HtmlToPdfParams] for what its fields control.
     */
    fun htmlToPdf(params: HtmlToPdfParams, onResult: (String?) -> Unit) {
        val outputPath = params.outputPath
            ?: File(context.cacheDir, "html_${System.currentTimeMillis()}.pdf").absolutePath
        HtmlToPdf.convert(
            context, params.html, outputPath, params.pageWidthDp, params.heightDp, params.minPageHeightDp
        ) { file ->
            onResult(file?.absolutePath)
        }
    }

    /**
     * Full pipeline: [htmlToPdf] -> [printPdf]. Since [htmlToPdf] needs the main thread
     * and printing does blocking Bluetooth I/O, this is callback-based: the HTML->PDF
     * step runs on the main thread, then printing runs on a background thread.
     * [onResult] is invoked on a background thread with true/false.
     *
     * See [PrintHtmlParams] for what its fields control.
     */
    fun printHtml(params: PrintHtmlParams, onResult: (Boolean) -> Unit = {}) {
        htmlToPdf(
            HtmlToPdfParams(
                html = params.html,
                pageWidthDp = params.pageWidthDp,
                heightDp = params.heightDp,
                minPageHeightDp = params.minPageHeightDp
            )
        ) { pdfPath ->
            if (pdfPath == null) {
                onResult(false)
                return@htmlToPdf
            }
            Thread {
                try {
                    printPdf(
                        PrintPdfParams(
                            pdfPath, params.printerWidthDots,
                            bandHeightDots = params.bandHeightDots, bandDelayMs = params.bandDelayMs
                        )
                    )
                    onResult(true)
                } catch (e: Exception) {
                    Log.e(TAG, "printHtml failed", e)
                    onResult(false)
                } finally {
                    File(pdfPath).delete()
                }
            }.start()
        }
    }

    private fun requireSocket(): BluetoothSocket {
        return socket ?: throw IllegalStateException("Printer not connected. Call connectPrinter() first.")
    }

    /**
     * Runs [action] (a full write to the printer's [BluetoothSocket]); if it fails with an
     * [IOException] - e.g. "Broken pipe" because a cheap ESC/POS board silently dropped the
     * link mid-print - reconnects to the same device once and retries [action] from scratch
     * exactly once. A second failure (or a failed reconnect) propagates the exception as-is.
     */
    private fun writeWithRetry(action: () -> Unit) {
        try {
            action()
        } catch (e: IOException) {
            val address = connectedDevice?.address
            Log.w(TAG, "Write to printer failed (${e.message}); reconnecting and retrying once", e)
            closeSocketQuietly()
            if (address == null || !connectPrinter(ConnectPrinterParams(address))) {
                throw e
            }
            action()
        }
    }

    /** Type-safe [BluetoothDevice.EXTRA_DEVICE] extraction across API levels. */
    private fun getDeviceExtra(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun closeSocketQuietly() {
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing printer socket", e)
        } finally {
            socket = null
            connectedDevice = null
        }
    }

    private fun scaleToWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
        if (bitmap.width == targetWidth) return bitmap
        val ratio = targetWidth.toFloat() / bitmap.width
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /**
     * Converts [bitmap] into ESC/POS "GS v 0" raster bit-image bytes (1 bit per pixel),
     * using Floyd-Steinberg dithering for readable output on a 1-bit thermal head.
     */
    private fun toEscPosRaster(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val widthBytes = (width + 7) / 8

        val gray = FloatArray(width * height)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = Color.alpha(pixel)
            gray[i] = if (a == 0) {
                255f
            } else {
                0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel)
            }
        }

        val out = ByteArray(widthBytes * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val old = gray[idx]
                val newVal = if (old < 128f) 0f else 255f
                val error = old - newVal
                if (newVal == 0f) {
                    val byteIndex = y * widthBytes + (x / 8)
                    val bit = 7 - (x % 8)
                    out[byteIndex] = (out[byteIndex].toInt() or (1 shl bit)).toByte()
                }
                if (x + 1 < width) gray[idx + 1] += error * 7f / 16f
                if (y + 1 < height) {
                    if (x > 0) gray[idx + width - 1] += error * 3f / 16f
                    gray[idx + width] += error * 5f / 16f
                    if (x + 1 < width) gray[idx + width + 1] += error * 1f / 16f
                }
            }
        }

        val header = byteArrayOf(
            0x1D, 0x76, 0x30, 0x00,
            (widthBytes and 0xFF).toByte(), ((widthBytes shr 8) and 0xFF).toByte(),
            (height and 0xFF).toByte(), ((height shr 8) and 0xFF).toByte()
        )
        return header + out
    }

    companion object {
        private const val TAG = "BluetoothPrinter"
        private const val PREFS_NAME = "bluetooth_printer_prefs"
        private const val KEY_LAST_ADDRESS = "last_connected_address"
        private val ESC_INIT = byteArrayOf(0x1B, 0x40) // ESC @ : initialize printer
        private const val RECONNECT_SETTLE_MS = 200L
    }
}
