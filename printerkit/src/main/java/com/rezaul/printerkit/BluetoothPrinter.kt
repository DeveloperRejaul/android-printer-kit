package com.rezaul.printerkit

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream
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
     * Opens an RFCOMM (Serial Port Profile) socket to the paired device at [address].
     * Any existing connection is closed first. Returns true on success.
     */
    @SuppressLint("MissingPermission")
    fun connectPrinter(address: String): Boolean {
        val adapter = bluetoothAdapter ?: return false
        disconnectPrinter()
        return try {
            val device = adapter.getRemoteDevice(address)
            // Best-effort: speeds up connect if discovery happens to be running, but
            // requires BLUETOOTH_SCAN (not BLUETOOTH_CONNECT) on API 31+, which this
            // library doesn't otherwise need - never let its absence block connecting.
            try {
                adapter.cancelDiscovery()
            } catch (e: SecurityException) {
                Log.w(TAG, "cancelDiscovery() skipped (missing BLUETOOTH_SCAN permission)", e)
            }
            val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            newSocket.connect()
            socket = newSocket
            connectedDevice = device
            prefs.edit().putString(KEY_LAST_ADDRESS, address).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "connectPrinter failed for $address", e)
            closeSocketQuietly()
            false
        }
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
        return connectPrinter(address)
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
    fun printText(text: String, feedLines: Int = 3) {
        val out = requireSocket().outputStream
        out.write(ESC_INIT)
        out.write(text.toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x0A))
        out.write(ByteArray(feedLines) { 0x0A })
        out.flush()
    }

    /**
     * Prints [bitmap] as an ESC/POS raster image, scaled to [printerWidthDots]
     * (384 for common 58mm printers, 576 for 80mm printers).
     *
     * Sent as separate `GS v 0` commands per [BAND_HEIGHT_DOTS]-tall horizontal band,
     * with a short pause between each, rather than one command for the whole image.
     * Many cheap ESC/POS boards have a small receive buffer and either truncate or
     * silently drop an oversized/bursty raster command (connect + write both report
     * success, but nothing comes out) - banding keeps each command small and paced.
     */
    fun printImageBitmap(bitmap: Bitmap, printerWidthDots: Int = 384, feedLines: Int = 3) {
        val out = requireSocket().outputStream
        val scaled = scaleToWidth(bitmap, printerWidthDots)
        try {
            out.write(ESC_INIT)
            out.flush()

            var y = 0
            while (y < scaled.height) {
                val bandHeight = minOf(BAND_HEIGHT_DOTS, scaled.height - y)
                val band = Bitmap.createBitmap(scaled, 0, y, scaled.width, bandHeight)
                try {
                    out.write(toEscPosRaster(band))
                    out.flush()
                } finally {
                    band.recycle()
                }
                Thread.sleep(BAND_DELAY_MS)
                y += bandHeight
            }

            out.write(ByteArray(feedLines) { 0x0A })
            out.flush()
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /** Decodes the image file at [imagePath] and prints it. */
    fun printImageFile(imagePath: String, printerWidthDots: Int = 384, feedLines: Int = 3) {
        val bitmap = BitmapFactory.decodeFile(imagePath)
            ?: throw IllegalArgumentException("Could not decode image: $imagePath")
        try {
            printImageBitmap(bitmap, printerWidthDots, feedLines)
        } finally {
            bitmap.recycle()
        }
    }

    /** Decodes a base64-encoded image and prints it. */
    fun printImageBase64(base64: String, printerWidthDots: Int = 384, feedLines: Int = 3) {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("Could not decode base64 image")
        try {
            printImageBitmap(bitmap, printerWidthDots, feedLines)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Renders one page of a PDF file to an image file using Android's built-in
     * [PdfRenderer] (no external dependency). Returns the absolute path of the
     * saved image.
     */
    fun pdfToImage(
        pdfPath: String,
        imageType: PrinterImageType = PrinterImageType.PNG,
        page: Int = 0,
        targetWidthPx: Int? = null,
        outputDir: String = context.cacheDir.absolutePath
    ): String {
        val file = File(pdfPath)
        require(file.exists()) { "PDF file not found: $pdfPath" }

        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(page in 0 until renderer.pageCount) { "Invalid page index: $page" }
                renderer.openPage(page).use { pdfPage ->
                    val scale = if (targetWidthPx != null) {
                        targetWidthPx.toFloat() / pdfPage.width
                    } else {
                        2f
                    }
                    val width = (pdfPage.width * scale).toInt().coerceAtLeast(1)
                    val height = (pdfPage.height * scale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                    val extension = if (imageType == PrinterImageType.PNG) "png" else "jpg"
                    val outFile = File(outputDir, "print_${System.currentTimeMillis()}.$extension")
                    FileOutputStream(outFile).use { fos ->
                        val format = if (imageType == PrinterImageType.PNG) {
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

    /** Renders [pdfPath] to an image (via [pdfToImage]), then prints it. The temp image is deleted afterwards. */
    fun printPdf(pdfPath: String, printerWidthDots: Int = 384, page: Int = 0, feedLines: Int = 3) {
        val imagePath = pdfToImage(pdfPath, PrinterImageType.PNG, page, targetWidthPx = printerWidthDots)
        try {
            printImageFile(imagePath, printerWidthDots, feedLines)
        } finally {
            File(imagePath).delete()
        }
    }

    /**
     * Converts [html] to a PDF file using Android's built-in WebView print framework
     * (see [HtmlToPdf], no external library). WebView requires the main thread, so this
     * is safe to call from any thread - it hops internally - and [onResult] (the saved
     * PDF's absolute path, or null on failure) always arrives on the main thread.
     *
     * @param pageWidthDp layout width in dp (CSS px) the HTML is rendered at - controls
     *   how large text/content look relative to the page, not the final print size
     *   (that's [printerWidthDots] on [printHtml]/[printPdf], which downscales this).
     * @param heightDp if set, forces the page to exactly this height (dp) instead of
     *   auto-measuring the HTML's content height - use this if you already know the
     *   right height, or want to force a fixed one.
     * @param minPageHeightDp ignored if [heightDp] is set. Otherwise a height floor in
     *   dp for auto-measured content - the page normally just fits the real content, so
     *   this only matters for very short/empty HTML.
     */
    fun htmlToPdf(
        html: String,
        outputPath: String = File(context.cacheDir, "html_${System.currentTimeMillis()}.pdf").absolutePath,
        pageWidthDp: Int = 412,
        heightDp: Int? = null,
        minPageHeightDp: Int = 1000,
        onResult: (String?) -> Unit
    ) {
        HtmlToPdf.convert(context, html, outputPath, pageWidthDp, heightDp, minPageHeightDp) { file ->
            onResult(file?.absolutePath)
        }
    }

    /**
     * Full pipeline: [htmlToPdf] -> [printPdf]. Since [htmlToPdf] needs the main thread
     * and printing does blocking Bluetooth I/O, this is callback-based: the HTML->PDF
     * step runs on the main thread, then printing runs on a background thread.
     * [onResult] is invoked on a background thread with true/false.
     *
     * See [htmlToPdf] for what [pageWidthDp]/[heightDp]/[minPageHeightDp] control.
     */
    fun printHtml(
        html: String,
        printerWidthDots: Int = 384,
        pageWidthDp: Int = 412,
        heightDp: Int? = null,
        minPageHeightDp: Int = 1000,
        onResult: (Boolean) -> Unit = {}
    ) {
        htmlToPdf(html, pageWidthDp = pageWidthDp, heightDp = heightDp, minPageHeightDp = minPageHeightDp) { pdfPath ->
            if (pdfPath == null) {
                onResult(false)
                return@htmlToPdf
            }
            Thread {
                try {
                    printPdf(pdfPath, printerWidthDots)
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

        // Cheap ESC/POS boards commonly have a small receive buffer; a single big
        // GS v 0 command for a tall image can get truncated/dropped silently. Bands
        // this small, paced a few ms apart, print reliably across those boards.
        private const val BAND_HEIGHT_DOTS = 128
        private const val BAND_DELAY_MS = 25L
    }
}
