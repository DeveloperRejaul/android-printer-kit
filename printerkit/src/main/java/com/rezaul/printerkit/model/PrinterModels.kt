package com.rezaul.printerkit.model

import android.graphics.Bitmap

/**
 * A paired (bonded) Bluetooth printer. [confidence] and [reason] say how sure the library is that the
 * device is a printer (Bluetooth can't tell for certain) - see [PrinterFilter].
 */
data class BluetoothPrinterDevice(
    val name: String?,
    val address: String,
    val confidence: PrinterConfidence = PrinterConfidence.UNKNOWN,
    val reason: String? = null
) {
    val isLikelyPrinter: Boolean get() = confidence != PrinterConfidence.UNKNOWN
}

/** How sure the library is that a Bluetooth device is a printer. */
enum class PrinterConfidence {
    /** Reported as a printer by its Bluetooth class, or its name and SPP service both match. */
    CONFIDENT,

    /** Name looks like a printer, or its Bluetooth class is "imaging". */
    LIKELY,

    /** No sign it is a printer (or it is BLE-only, which this library can't use). */
    UNKNOWN
}

/** Which paired devices [PosPrinter.getBondedBluetoothPrinters] returns. */
enum class PrinterFilter {
    /** Every paired device. */
    ALL,

    /** [PrinterConfidence.CONFIDENT] and [PrinterConfidence.LIKELY] devices (recommended). */
    LIKELY_PRINTERS,

    /** Only [PrinterConfidence.CONFIDENT] devices. May hide real printers that report a wrong class/name. */
    STRICT
}

/** A USB printer currently plugged in (needs USB host / OTG). */
data class UsbPrinterDevice(
    val name: String?,
    val vendorId: Int,
    val productId: Int,
    val deviceName: String
)

/**
 * Result of [PosPrinter.getPrinterStatus]. [responded] is false when the printer is not connected or does
 * not answer ESC/POS status requests (many cheap models don't) - the other fields are then null (unknown).
 * [toMap] is bridge-friendly.
 */
data class PrinterStatus(
    val connected: Boolean,
    val responded: Boolean,
    val online: Boolean? = null,
    val paperOut: Boolean? = null,
    val paperNearEnd: Boolean? = null,
    val coverOpen: Boolean? = null,
    val hasError: Boolean? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "connected" to connected, "responded" to responded, "online" to online, "paperOut" to paperOut,
        "paperNearEnd" to paperNearEnd, "coverOpen" to coverOpen, "hasError" to hasError
    )
}

/**
 * Output image format for [PosPrinter.pdfToImage].
 */
enum class PrinterImageType { PNG, JPEG }

/** How the printer is reached. Plain enum so it maps to a string in Flutter / React Native bridges. */
enum class PrinterConnectionType {
    /** Paired Bluetooth Classic (SPP) printer. [ConnectPrinterParams.address] is its MAC address. */
    BLUETOOTH,

    /** Wi-Fi / LAN (Ethernet) printer over raw TCP. [ConnectPrinterParams.address] is its IP/host. */
    NETWORK,

    /** USB printer (needs OTG and the user's USB permission, see [PosPrinter.requestUsbPermission]). */
    USB
}

/**
 * Parameters for [PosPrinter.connectPrinter] / [PrinterService.connectAndKeepAlive].
 * - [address]: Bluetooth MAC address, or IP/host name for [PrinterConnectionType.NETWORK].
 * - [type]: defaults to [PrinterConnectionType.BLUETOOTH], so existing callers are unaffected.
 * - [port]: TCP port for NETWORK printers (raw ESC/POS printing is almost always 9100).
 * - [timeoutMs]: connect timeout for NETWORK printers.
 * - [vendorId]/[productId]: identify a USB printer (from [PosPrinter.getUsbPrinters]); for USB, if both are
 *   null, [address] is matched against the USB device name instead.
 * - [notificationTitle]: title of the foreground notification shown by [PrinterService] while connected.
 * - [disconnectedNotificationTitle]: title of the notification [PrinterService] shows if the printer later disconnects on its own.
 */
data class ConnectPrinterParams(
    val address: String,
    val notificationTitle: String = DEFAULT_NOTIFICATION_TITLE,
    val type: PrinterConnectionType = PrinterConnectionType.BLUETOOTH,
    val port: Int = DEFAULT_NETWORK_PORT,
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val disconnectedNotificationTitle: String = DEFAULT_DISCONNECTED_TITLE
) {
    companion object {
        const val DEFAULT_NOTIFICATION_TITLE = "Printer connected"
        const val DEFAULT_DISCONNECTED_TITLE = "Printer disconnected"
        const val DEFAULT_NETWORK_PORT = 9100
        const val DEFAULT_TIMEOUT_MS = 5000
    }
}

/** Parameters for [PosPrinter.printText]. */
data class PrintTextParams(val text: String, val feedLines: Int = 3)

/**
 * Parameters for [PosPrinter.printImageBitmap]. [bandHeightDots]/[bandDelayMs] control
 * how the image is paced to the printer - see [PosPrinter.printImageBitmap] for why.
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

/** Parameters for [PosPrinter.printImageFile]. See [PrintImageBitmapParams] for [bandHeightDots]/[bandDelayMs]. */
data class PrintImageFileParams(
    val imagePath: String,
    val printerWidthDots: Int = 384,
    val feedLines: Int = 3,
    val bandHeightDots: Int = 16,
    val bandDelayMs: Long = 60L
)

/** Parameters for [PosPrinter.printImageBase64]. See [PrintImageBitmapParams] for [bandHeightDots]/[bandDelayMs]. */
data class PrintImageBase64Params(
    val base64: String,
    val printerWidthDots: Int = 384,
    val feedLines: Int = 3,
    val bandHeightDots: Int = 16,
    val bandDelayMs: Long = 60L
)

/** Parameters for [PosPrinter.pdfToImage]. [outputDir] defaults to the app's cache dir when null. */
data class PdfToImageParams(
    val pdfPath: String,
    val imageType: PrinterImageType = PrinterImageType.PNG,
    val page: Int = 0,
    val targetWidthPx: Int? = null,
    val outputDir: String? = null
)

/** Parameters for [PosPrinter.printPdf]. See [PrintImageBitmapParams] for [bandHeightDots]/[bandDelayMs]. */
data class PrintPdfParams(
    val pdfPath: String,
    val printerWidthDots: Int = 384,
    val page: Int = 0,
    val feedLines: Int = 3,
    val bandHeightDots: Int = 16,
    val bandDelayMs: Long = 60L
)

/**
 * Parameters for [PosPrinter.htmlToPdf]. [outputPath] defaults to a fresh file
 * under the app's cache dir when null. See [PosPrinter.htmlToPdf] for what
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
 * Parameters for [PosPrinter.printHtml]. See [HtmlToPdfParams] for the HTML sizing
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
