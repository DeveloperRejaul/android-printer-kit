package com.rezaul.printerkit

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import com.rezaul.printerkit.model.BluetoothPrinterDevice
import com.rezaul.printerkit.model.PrinterConfidence
import com.rezaul.printerkit.model.PrinterConnectionType
import com.rezaul.printerkit.model.PrinterFilter
import com.rezaul.printerkit.model.ReadDirParams
import com.rezaul.printerkit.model.ReadFileParams
import com.rezaul.printerkit.model.ReadFileResult
import com.rezaul.printerkit.model.HtmlToImageParams
import com.rezaul.printerkit.model.ReadDirResult
import com.rezaul.printerkit.model.PrinterStatus
import com.rezaul.printerkit.model.RemoveFileParams
import com.rezaul.printerkit.model.RemoveFileResult
import com.rezaul.printerkit.model.UsbPrinterDevice
import com.rezaul.printerkit.model.PrinterImageType
import com.rezaul.printerkit.model.ConnectPrinterParams
import com.rezaul.printerkit.model.PrintTextParams
import com.rezaul.printerkit.model.SaveFileParams
import com.rezaul.printerkit.model.SaveFileResult
import com.rezaul.printerkit.model.FileLocation
import com.rezaul.printerkit.model.DirectoryPaths
import com.rezaul.printerkit.model.PrintImageBitmapParams
import com.rezaul.printerkit.model.PrintImageFileParams
import com.rezaul.printerkit.model.PrintImageBase64Params
import com.rezaul.printerkit.model.PdfToImageParams
import com.rezaul.printerkit.model.PrintPdfParams
import com.rezaul.printerkit.model.HtmlToPdfParams
import com.rezaul.printerkit.model.PrintHtmlParams

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/**
 * Talks to an ESC/POS thermal/POS printer over Bluetooth Classic (SPP, must already be
 * paired in Android's Bluetooth settings) or Wi-Fi/LAN (raw TCP, port 9100) - choose with
 * [ConnectPrinterParams.type]. All printing code is identical for both.
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
class PosPrinter(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    private var transport: PrinterTransport? = null
    private var connectedDevice: BluetoothDevice? = null
    private var connectedUsbName: String? = null

    // Display name captured at connect time: once Bluetooth is off, BluetoothDevice.getName() returns null.
    private var connectedName: String? = null

    // What the active connection was opened with - used to reconnect after a dropped write, for any connection type.
    private var activeParams: ConnectPrinterParams? = null

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
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = getDeviceExtra(intent) ?: return
                    if (device.address == connectedDevice?.address) {
                        Log.w(TAG, "Printer ${device.address} physically disconnected")
                        handleConnectionLost()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // The USB printer was unplugged.
                    val params = activeParams
                    if (params?.type == PrinterConnectionType.USB) {
                        val device = getUsbDeviceExtra(intent)
                        if (device == null || (device.vendorId == params.vendorId && device.productId == params.productId)) {
                            Log.w(TAG, "USB printer unplugged")
                            handleConnectionLost()
                        }
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    // Turning Bluetooth off drops the link too, but not always with a per-device ACL broadcast.
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val bluetoothPrinter = activeParams?.type == PrinterConnectionType.BLUETOOTH
                    if (bluetoothPrinter && (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF)) {
                        Log.w(TAG, "Bluetooth turned off while a printer was connected")
                        handleConnectionLost()
                    }
                }
            }
        }
    }

    /**
     * Called when the connection drops on its own (printer off / out of range, Bluetooth turned off, a
     * failed write that could not be recovered) - not when the caller disconnects on purpose. Set by
     * [PrinterService] to replace its "connected" notification. Always invoked on the main thread.
     * Receives the lost printer's name (null if unknown).
     */
    @Volatile
    var onConnectionLost: ((printerName: String?) -> Unit)? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Clears the dead connection and tells [onConnectionLost] (once - nothing happens if already disconnected). */
    private fun handleConnectionLost() {
        if (transport == null && activeParams == null) return
        val name = getConnectedPrinterNameOrNull()
        closeSocketQuietly()
        val listener = onConnectionLost ?: return
        mainHandler.post { listener(name) }
    }

    private fun getConnectedPrinterNameOrNull(): String? = connectedName

    init {
        ContextCompat.registerReceiver(
            context,
            connectionWatcher,
            IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED).apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * True if the device's Bluetooth adapter itself is turned on. Unlike
     * [getBondedBluetoothPrinters], this needs no runtime permission - it's the
     * right check to run before asking the user to grant one, so a disabled
     * adapter can be reported as "turn on Bluetooth" rather than "grant permission".
     */
    fun isBluetoothEnabled(): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java)
        return manager?.adapter?.isEnabled == true
    }

    /**
     * Returns the Bluetooth devices already paired with this phone/tablet (all of them by default, each rated by how much it looks like a printer)
     * (see [PrinterFilter]; pass [PrinterFilter.LIKELY_PRINTERS] to hide devices that don't look like printers). Bluetooth can't say for
     * certain what a device is, so each result carries a [BluetoothPrinterDevice.confidence]; use
     * [extraNameKeywords] to also accept printers whose name doesn't look like one.
     * On Android 12+ this requires the BLUETOOTH_CONNECT runtime permission;
     * if it hasn't been granted, an empty list is returned.
     */
    @SuppressLint("MissingPermission")
    fun getBondedBluetoothPrinters(
        filter: PrinterFilter = PrinterFilter.ALL,
        extraNameKeywords: List<String> = emptyList()
    ): List<BluetoothPrinterDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        return try {
            adapter.bondedDevices
                .map {
                    val (confidence, reason) = PrinterClassifier.classify(it, extraNameKeywords)
                    BluetoothPrinterDevice(it.name, it.address, confidence, reason)
                }
                .filter {
                    when (filter) {
                        PrinterFilter.ALL -> true
                        PrinterFilter.LIKELY_PRINTERS -> it.confidence != PrinterConfidence.UNKNOWN
                        PrinterFilter.STRICT -> it.confidence == PrinterConfidence.CONFIDENT
                    }
                }
                .sortedWith(compareBy({ it.confidence.ordinal }, { it.name ?: "" }))
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission", e)
            emptyList()
        }
    }

    /**
     * Connects to the printer described by [params] - Bluetooth (default), Wi-Fi/LAN or USB, see
     * [PrinterConnectionType]. Any existing connection is closed first. Returns true on success.
     */
    fun connectPrinter(params: ConnectPrinterParams): Boolean = when (params.type) {
        PrinterConnectionType.BLUETOOTH -> connectBluetooth(params)
        PrinterConnectionType.NETWORK -> connectNetwork(params)
        PrinterConnectionType.USB -> connectUsb(params)
    }

    /**
     * Opens a raw TCP socket to a Wi-Fi/LAN printer at [ConnectPrinterParams.address]:[ConnectPrinterParams.port]
     * (default 9100). The phone must be on the same network as the printer. Returns true on success.
     */
    private fun connectNetwork(params: ConnectPrinterParams): Boolean {
        disconnectPrinter()
        return try {
            val tcp = Socket()
            tcp.tcpNoDelay = true
            tcp.connect(InetSocketAddress(params.address, params.port), params.timeoutMs)
            transport = NetworkTransport(tcp)
            connectedName = params.address
            activeParams = params
            saveLastConnection(params)
            true
        } catch (e: Exception) {
            Log.e(TAG, "network connect failed for ${params.address}:${params.port}", e)
            closeSocketQuietly()
            false
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

    private val usbManager: UsbManager? by lazy { context.getSystemService(Context.USB_SERVICE) as? UsbManager }

    /** USB printers currently plugged in (USB printer class). Needs a phone with USB host / OTG. */
    fun getUsbPrinters(): List<UsbPrinterDevice> =
        usbManager?.deviceList?.values.orEmpty()
            .filter { device -> (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_PRINTER } }
            .map { UsbPrinterDevice(it.productName, it.vendorId, it.productId, it.deviceName) }

    /** True if the user already granted this app access to the USB printer. */
    fun hasUsbPermission(vendorId: Int, productId: Int): Boolean {
        val device = findUsbDevice(vendorId, productId, null) ?: return false
        return usbManager?.hasPermission(device) == true
    }

    /**
     * Asks the user for permission to use a USB printer (a system dialog). [onResult] is called on the main
     * thread with whether it was granted; if already granted it is called right away with true.
     * Connect with [connectPrinter] ([PrinterConnectionType.USB]) after it returns true.
     */
    fun requestUsbPermission(vendorId: Int, productId: Int, onResult: (Boolean) -> Unit) {
        val manager = usbManager
        val device = findUsbDevice(vendorId, productId, null)
        if (manager == null || device == null) { onResult(false); return }
        if (manager.hasPermission(device)) { onResult(true); return }

        val action = "${context.packageName}.printerkit.USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receivedContext: Context, intent: Intent) {
                try { context.unregisterReceiver(this) } catch (_: Exception) {}
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        // The system fills in the result extras, so the PendingIntent must be mutable on Android 12+.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags)
        manager.requestPermission(device, pending)
    }

    private fun findUsbDevice(vendorId: Int?, productId: Int?, deviceName: String?): UsbDevice? {
        val devices = usbManager?.deviceList?.values ?: return null
        return if (vendorId != null && productId != null)
            devices.firstOrNull { it.vendorId == vendorId && it.productId == productId }
        else devices.firstOrNull { it.deviceName == deviceName }
    }

    /** Opens the bulk-OUT endpoint of a USB printer (needs permission from [requestUsbPermission]). */
    private fun connectUsb(params: ConnectPrinterParams): Boolean {
        disconnectPrinter()
        return try {
            val manager = usbManager ?: return false
            val device = findUsbDevice(params.vendorId, params.productId, params.address) ?: run {
                Log.e(TAG, "USB printer not found (vid=${params.vendorId}, pid=${params.productId})"); return false
            }
            if (!manager.hasPermission(device)) {
                Log.e(TAG, "No USB permission for ${device.deviceName}; call requestUsbPermission() first"); return false
            }
            // Prefer the printer-class interface, otherwise any interface that has a bulk OUT endpoint.
            val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
            val iface = interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER && bulkOut(it) != null }
                ?: interfaces.firstOrNull { bulkOut(it) != null } ?: run {
                Log.e(TAG, "No bulk OUT endpoint on USB device"); return false
            }
            val connection = manager.openDevice(device) ?: return false
            if (!connection.claimInterface(iface, true)) { connection.close(); return false }
            val inEp = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
            transport = UsbTransport(connection, iface, bulkOut(iface)!!, inEp)
            connectedUsbName = device.productName
            connectedName = device.productName
            activeParams = params.copy(vendorId = device.vendorId, productId = device.productId)
            saveLastConnection(activeParams!!)
            true
        } catch (e: Exception) {
            Log.e(TAG, "USB connect failed", e)
            closeSocketQuietly()
            false
        }
    }

    private fun bulkOut(iface: android.hardware.usb.UsbInterface) = (0 until iface.endpointCount)
        .map { iface.getEndpoint(it) }
        .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }

    @SuppressLint("MissingPermission")
    private fun connectBluetooth(params: ConnectPrinterParams): Boolean {
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

        if (tryOpenSocket(device, params) { device.createRfcommSocketToServiceRecord(SPP_UUID) }) return true
        Log.w(TAG, "SDP-based connect failed for ${params.address}; trying direct RFCOMM channel fallback")
        return tryOpenSocket(device, params) { createFallbackRfcommSocket(device) }
    }

    /** Opens [openSocket] and connects it; on success stores it as the active connection. */
    private fun tryOpenSocket(device: BluetoothDevice, params: ConnectPrinterParams, openSocket: () -> BluetoothSocket): Boolean {
        return try {
            val newSocket = openSocket()
            newSocket.connect()
            transport = BluetoothTransport(newSocket)
            connectedDevice = device
            connectedName = device.name
            activeParams = params
            saveLastConnection(params)
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
     * to be called once when a fresh process starts (e.g. [PrinterService]'s
     * creation) so the app doesn't need the user to re-pick the printer every time it
     * was fully killed and reopened. Returns true if connected (already, or just now).
     */
    fun autoConnectIfAvailable(): Boolean {
        if (isConnectedPrinter()) return true
        val address = prefs.getString(KEY_LAST_ADDRESS, null) ?: return false
        val type = prefs.getString(KEY_LAST_TYPE, null)
            ?.let { runCatching { PrinterConnectionType.valueOf(it) }.getOrNull() }
            ?: PrinterConnectionType.BLUETOOTH
        val port = prefs.getInt(KEY_LAST_PORT, ConnectPrinterParams.DEFAULT_NETWORK_PORT)
        val vid = prefs.getInt(KEY_LAST_VID, -1).takeIf { it >= 0 }
        val pid = prefs.getInt(KEY_LAST_PID, -1).takeIf { it >= 0 }
        return connectPrinter(ConnectPrinterParams(address, type = type, port = port, vendorId = vid, productId = pid))
    }

    /** Closes the current printer connection, if any, and forgets it for [autoConnectIfAvailable]. Safe to call when already disconnected. */
    fun disconnectPrinter() {
        closeSocketQuietly()
        prefs.edit().remove(KEY_LAST_ADDRESS).remove(KEY_LAST_TYPE).remove(KEY_LAST_PORT).remove(KEY_LAST_VID).remove(KEY_LAST_PID).apply()
    }

    private fun saveLastConnection(params: ConnectPrinterParams) {
        prefs.edit()
            .putString(KEY_LAST_ADDRESS, params.address)
            .putString(KEY_LAST_TYPE, params.type.name)
            .putInt(KEY_LAST_PORT, params.port)
            .putInt(KEY_LAST_VID, params.vendorId ?: -1)
            .putInt(KEY_LAST_PID, params.productId ?: -1)
            .apply()
    }


    /**
     * True if a printer is connected and accepts bytes right now (sends the ESC/POS "initialize" command).
     * This catches a printer that is switched off or out of range while the connection still looks open.
     * It does not check paper or cover - use [getPrinterStatus] for that. Blocking I/O.
     */
    fun isPrinterReady(): Boolean {
        val t = transport ?: return false
        if (!t.isConnected) return false
        return try {
            t.outputStream.write(ESC_INIT)
            t.outputStream.flush()
            true
        } catch (e: IOException) {
            Log.w(TAG, "isPrinterReady: write failed (${e.message})")
            false
        }
    }

    /**
     * Asks the printer for its real-time status (ESC/POS `DLE EOT`): online, paper out / near end, cover
     * open, error. Many cheap printers ignore these requests - then [PrinterStatus.responded] is false and
     * the fields are null, which means "unknown", not "ok". Blocking I/O, waits up to [timeoutMs] per query.
     */
    fun getPrinterStatus(timeoutMs: Int = 800): PrinterStatus {
        val t = transport
        if (t == null || !t.isConnected) return PrinterStatus(connected = false, responded = false)
        return try {
            val scratch = ByteArray(64)
            while (t.read(scratch, 0) > 0) { /* drop stale bytes from earlier traffic */ }

            fun query(n: Int): Int? {
                t.outputStream.write(byteArrayOf(0x10, 0x04, n.toByte()))
                t.outputStream.flush()
                val one = ByteArray(1)
                return if (t.read(one, timeoutMs) == 1) one[0].toInt() and 0xFF else null
            }

            val printer = query(1) ?: return PrinterStatus(connected = true, responded = false)
            val offline = query(2)
            val paper = query(4)
            PrinterStatus(
                connected = true,
                responded = true,
                online = printer and 0x08 == 0,
                coverOpen = offline?.let { it and 0x04 != 0 },
                hasError = offline?.let { it and 0x40 != 0 },
                paperOut = paper?.let { it and 0x60 != 0 } ?: offline?.let { it and 0x20 != 0 },
                paperNearEnd = paper?.let { it and 0x0C != 0 }
            )
        } catch (e: IOException) {
            Log.w(TAG, "getPrinterStatus failed (${e.message})")
            PrinterStatus(connected = false, responded = false)
        }
    }

    /** Prints a short ASCII test page (connection type, address, time) to confirm the setup works. */
    fun printTestPage() {
        val params = activeParams
        val printer = getConnectedPrinter()
        val text = buildString {
            append("--------------------------------\n")
            append("      PRINTER TEST PAGE\n")
            append("--------------------------------\n")
            append("Connection: ${params?.type ?: "unknown"}\n")
            append("Printer   : ${printer?.name ?: "-"}\n")
            append("Address   : ${printer?.address ?: "-"}\n")
            append("Time      : ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n")
            append("--------------------------------\n")
            append("If you can read this, printing works.")
        }
        printText(PrintTextParams(text, feedLines = 3))
    }

    /** True if a printer socket is currently open. */
    fun isConnectedPrinter(): Boolean = transport?.isConnected == true

    /** The currently connected printer, or null if none. */
    @SuppressLint("MissingPermission")
    fun getConnectedPrinter(): BluetoothPrinterDevice? {
        if (!isConnectedPrinter()) return null
        val params = activeParams ?: return null
        if (params.type == PrinterConnectionType.NETWORK) {
            // No device name over TCP; the address field carries the host.
            return BluetoothPrinterDevice(null, params.address)
        }
        if (params.type == PrinterConnectionType.USB) {
            return BluetoothPrinterDevice(connectedUsbName, "${params.vendorId}:${params.productId}")
        }
        val device = connectedDevice ?: return null
        return BluetoothPrinterDevice(device.name, device.address)
    }

    /**
     * Sends raw text to the printer using its built-in font (ASCII only - not
     * suitable for Bangla or other non-Latin scripts, use [printImageBitmap] for that).
     */
    fun printText(params: PrintTextParams) {
        writeWithRetry {
            val out = requireTransport().outputStream
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
                val out = requireTransport().outputStream
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
     * If [HtmlToPdfParams.outputPath] points inside the public Downloads/Documents/Pictures
     * folder, a raw [File] write there would fail on Android 10+ (scoped storage). This is
     * detected automatically: the PDF is rendered to a private temp file first, then moved into
     * that public folder via [FileSaver]/MediaStore, and [onResult] gets back the real,
     * already-saved location - a `content://` Uri on Android 10+, an absolute path below that.
     *
     * See [HtmlToPdfParams] for what its fields control.
     */
    fun htmlToPdf(params: HtmlToPdfParams, onResult: (String?) -> Unit) {
        val requestedPath = params.outputPath
        val publicLocation = requestedPath?.let(::publicLocationFor)
        val outputPath = if (publicLocation != null) {
            File(context.cacheDir, "html_${System.currentTimeMillis()}.pdf").absolutePath
        } else {
            requestedPath ?: File(context.cacheDir, "html_${System.currentTimeMillis()}.pdf").absolutePath
        }
        HtmlToPdf.convert(
            context, params.html, outputPath, params.pageWidthDp, params.heightDp, params.minPageHeightDp
        ) { file ->
            if (file == null || publicLocation == null) {
                onResult(file?.absolutePath)
                return@convert
            }
            Thread {
                val result = FileSaver.save(
                    context,
                    SaveFileParams(
                        sourcePath = file.absolutePath,
                        fileName = File(requestedPath).name,
                        location = publicLocation,
                        overwrite = true,
                        deleteSource = true
                    )
                )
                mainHandler.post { onResult(if (result.success) result.path ?: result.uri else null) }
            }.start()
        }
    }

    /** Null unless [path] falls inside the public Downloads/Documents/Pictures folder; which [FileLocation] it is otherwise. */
    private fun publicLocationFor(path: String): FileLocation? {
        val dirs = FileSaver.directoryPaths(context)
        return when {
            path.startsWith(dirs.downloads) -> FileLocation.DOWNLOADS
            path.startsWith(dirs.documents) -> FileLocation.DOCUMENTS
            path.startsWith(dirs.pictures) -> FileLocation.PICTURES
            else -> null
        }
    }

    /** Real, absolute paths for common storage locations on this device. See [DirectoryPaths]. */
    fun getDirectoryPaths(): DirectoryPaths = FileSaver.directoryPaths(context)

    /**
     * Converts [HtmlToImageParams.html] to ONE PNG/JPEG image: [htmlToPdf], then the PDF page is rendered with
     * [pdfToImage] on a background thread and the temporary PDF is removed. [onResult] (the image's absolute path, or
     * null on failure) arrives on the main thread, like [htmlToPdf]. A very tall page needs a very large bitmap; if
     * the device runs out of memory the result is null (and the app keeps running).
     */
    fun htmlToImage(params: HtmlToImageParams, onResult: (String?) -> Unit) {
        val tempPdf = File(context.cacheDir, "h2i_${System.nanoTime()}.pdf")
        htmlToPdf(
            HtmlToPdfParams(
                html = params.html,
                outputPath = tempPdf.absolutePath,
                pageWidthDp = params.pageWidthDp,
                heightDp = params.heightDp,
                minPageHeightDp = params.minPageHeightDp
            )
        ) { pdfPath ->
            if (pdfPath == null) {
                onResult(null)
                return@htmlToPdf
            }
            Thread {
                var result: String? = null
                try {
                    val rendered = File(
                        pdfToImage(
                            PdfToImageParams(
                                pdfPath = pdfPath, imageType = params.imageType, page = 0,
                                targetWidthPx = params.targetWidthPx
                            )
                        )
                    )
                    val target = params.outputPath?.let { File(it) }
                    result = if (target == null) rendered.absolutePath else {
                        rendered.copyTo(target, overwrite = true)
                        rendered.delete()
                        target.absolutePath
                    }
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "htmlToImage: page too large for memory", e)
                } catch (e: Exception) {
                    Log.e(TAG, "htmlToImage failed", e)
                } finally {
                    File(pdfPath).delete()
                }
                mainHandler.post { onResult(result) }
            }.start()
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

    /**
     * Saves (copies) an existing file - e.g. a PDF from [htmlToPdf] or an image from [pdfToImage] -
     * to the location described by [params]. Never throws: check [SaveFileResult.success].
     * Blocking I/O, call from a background thread.
     */
    fun saveFile(params: SaveFileParams): SaveFileResult = FileSaver.save(context, params)

    /**
     * Deletes one file that [saveFile] (or anything else) wrote. [RemoveFileParams.path] is an absolute path or the
     * `content://` Uri that [saveFile] returned. Deletes a single file only - never a folder, never a pattern - and
     * cannot be undone. Never throws: check [RemoveFileResult.success]. For a MediaStore Uri (Downloads/Documents on
     * Android 10+) only files this app created can be deleted.
     */
    fun removeFile(params: RemoveFileParams): RemoveFileResult = FileSaver.remove(context, params)

    /**
     * Lists the files and folders directly inside one folder: [ReadDirParams.path] is an absolute path, or a
     * `content://` Uri of a folder picked with the system folder picker. Not recursive. Read-only. Folders come first,
     * then A-Z. Each entry's `path` can be given to [removeFile] (a file) or to [readDir] again (a folder). Never
     * throws: check [ReadDirResult.success]. A public folder such as Downloads may not be readable on Android 10+;
     * then the result explains that.
     */
    fun readDir(params: ReadDirParams): ReadDirResult = FileSaver.readDir(context, params)

    /**
     * Reads ONE file - an absolute path, or the `content://` Uri `saveFile` returned - and gives its content back in the
     * shape asked for by [ReadFileParams.format]: base64, a `data:` URI, text, hex or raw bytes. Read-only. A file over
     * [ReadFileParams.maxBytes] (default 10 MB; 1 MB for raw bytes) is refused rather than loaded. Never throws: check
     * [ReadFileResult.success]. Blocking I/O: call it from a background thread.
     */
    fun readFile(params: ReadFileParams): ReadFileResult = FileSaver.read(context, params)

    /**
     * Opens [path] with whatever app the user has installed that can handle its type
     * (e.g. a PDF viewer) - the system shows its own app chooser if more than one matches.
     * [mimeType] is inferred from the file extension when not given (falls back to the generic
     * "any type" mime type for a `content://` [path], since there is no file extension to read). Returns false
     * if the file doesn't exist (for an absolute path) or no app on the device can open it.
     *
     * [path] is either an absolute file path, or the `content://` Uri [saveFile] returned for
     * a public-folder destination (Downloads/Documents/Pictures on Android 10+, or a SAF
     * folder) - that Uri is not a real filesystem path, so it is launched directly instead of
     * going through [FileProvider]/[File].
     *
     * For an absolute path, requires the FileProvider this library declares in its manifest to
     * merge into the consuming app's manifest untouched (the default) - see AndroidManifest.xml.
     */
    fun openFile(path: String, mimeType: String? = null): Boolean {
        val uri: Uri
        val resolvedMimeType: String
        if (path.startsWith("content://")) {
            uri = Uri.parse(path)
            resolvedMimeType = mimeType ?: context.contentResolver.getType(uri) ?: "*/*"
        } else {
            val file = File(path)
            if (!file.exists()) return false
            uri = FileProvider.getUriForFile(context, "${context.packageName}.printerkit.fileprovider", file)
            resolvedMimeType = mimeType
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: "*/*"
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, resolvedMimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No app found to open $path", e)
            false
        }
    }

    private fun requireTransport(): PrinterTransport {
        return transport ?: throw IllegalStateException("Printer not connected. Call connectPrinter() first.")
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
            val params = activeParams
            Log.w(TAG, "Write to printer failed (${e.message}); reconnecting and retrying once", e)
            val name = getConnectedPrinterNameOrNull()
            closeSocketQuietly()
            if (params == null || !connectPrinter(params)) {
                onConnectionLost?.let { listener -> mainHandler.post { listener(name) } }
                throw e
            }
            action()
        }
    }

    /** Type-safe [UsbManager.EXTRA_DEVICE] extraction across API levels. */
    private fun getUsbDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
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
            transport?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing printer socket", e)
        } finally {
            transport = null
            connectedDevice = null
            connectedUsbName = null
            connectedName = null
            activeParams = null
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
        private const val TAG = "PosPrinter"
        private const val PREFS_NAME = "bluetooth_printer_prefs"
        private const val KEY_LAST_ADDRESS = "last_connected_address"
        private const val KEY_LAST_TYPE = "last_connected_type"
        private const val KEY_LAST_PORT = "last_connected_port"
        private const val KEY_LAST_VID = "last_connected_vendor_id"
        private const val KEY_LAST_PID = "last_connected_product_id"
        private val ESC_INIT = byteArrayOf(0x1B, 0x40) // ESC @ : initialize printer
        private const val RECONNECT_SETTLE_MS = 200L
    }
}
