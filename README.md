# PrinterKit

[![Release](https://img.shields.io/github/v/release/DeveloperRejaul/android-printer-kit)](https://github.com/DeveloperRejaul/android-printer-kit)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A professional Android library for talking to POS/thermal (ESC/POS) printers directly from your app — no third-party printer app required. It connects over Bluetooth Classic (SPP), Wi-Fi/LAN (TCP) or USB, keeps that connection alive in the background via a foreground service, and can print raw text, images, PDFs, and full **HTML content — including non-Latin scripts like Bangla** that ESC/POS printer fonts don't support natively.

## Why this exists

Most ESC/POS printer libraries only send raw text or a single pre-made image. They can't render real content — an actual HTML/CSS receipt layout, a PDF, or text in a script the printer's built-in font doesn't have (Bangla, Arabic, Hindi, etc.). PrinterKit solves that by rendering your HTML/PDF to a bitmap on-device (using Android's own `WebView` and `PdfRenderer` — no external rendering library) and sending it to the printer as a dithered ESC/POS raster image.

## Features

- **Three connection types** — Bluetooth Classic (SPP), Wi-Fi/LAN (raw TCP, port 9100) and USB (OTG) — behind one API; all printing code is identical for each (`PrinterConnectionType`).
- **Printer-only device list**: `getBondedBluetoothPrinters(PrinterFilter.LIKELY_PRINTERS)` returns only devices that look like printers; every device carries a confidence level.
- **Printer checks**: `isPrinterReady()`, `getPrinterStatus()` (paper out / cover open / offline) and `printTestPage()`.
- **Save files** to cache, app storage, public Downloads/Documents or a user-picked folder with `saveFile()` (`FileLocation`).
- **Bridge-friendly API** (for Flutter / React Native wrappers): only strings, numbers, booleans and enums cross the API; results have `toMap()`.
- **HTML → PDF → print** pipeline, so you can print an actual styled receipt/report, not just plain text.
- **PDF → image → print** pipeline using Android's built-in `PdfRenderer` (no external dependency).
- **Non-Latin text support** (Bangla and others) via image-based rendering, bypassing ESC/POS's font limitations.
- **Persistent connection**: printer stays connected even if the app is swiped away from Recents, via a foreground `PrinterService`.
- **Honest connection notification**: if the printer drops (switched off, out of range, Bluetooth turned off, USB unplugged), the ongoing "Printer connected" notification is replaced by a dismissible "Printer disconnected" one — it never keeps claiming a dead connection.
- **Auto-reconnect**: remembers the last connected printer and reconnects automatically the next time the app starts.
- **Resilient connect**: falls back to a direct RFCOMM channel when a printer's SDP service record is broken, and actively detects real disconnects (Android's ACL-disconnect broadcast, Bluetooth being switched off, USB being unplugged) instead of trusting a stale socket flag.
- **Banded raster printing**: images are sent in small, paced chunks — many cheap ESC/POS boards silently drop large single print commands, and banding avoids that.
- **Permission helpers**: `BluetoothPermissions` wraps the Android 12+ `BLUETOOTH_CONNECT` and Android 13+ `POST_NOTIFICATIONS` runtime permissions so you don't have to handle version checks yourself.

## Scope

This library speaks **ESC/POS** — the standard used by virtually all receipt/thermal POS printers — over:

| Connection | Notes |
|---|---|
| Bluetooth Classic (SPP) | Printer must be paired in Android's Bluetooth settings first. |
| Wi-Fi / LAN | Raw TCP, usually port 9100. Phone and printer must be on the same network. |
| USB | Needs a phone with USB host (OTG) and the user's USB permission. |

Not supported: Bluetooth LE-only printers, label printers that use TSPL/ZPL/CPCL (Zebra, TSC, ...), and inkjet/laser printers (PCL/PostScript). Each transport is a small `PrinterTransport` implementation, so more can be added without touching the printing code.

## Requirements

- Android **minSdk 21** (`compileSdk 36`).
- A phone/tablet with Bluetooth (for Bluetooth printers), Wi-Fi (for network printers) or USB host / OTG (for USB printers).
- An ESC/POS printer. Typical widths: **384 dots = 58 mm**, **576 dots = 80 mm** (`printerWidthDots`).

## Installation

### 1. Add the JitPack repository

In your root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. Add the dependency

```kotlin
dependencies {
    implementation("com.github.DeveloperRejaul:android-printer-kit:v0.1.0")
}
```

### 3. Permissions

Nothing to add manually — `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `INTERNET` (Wi-Fi/LAN printers), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS` and an optional `usb.host` feature are declared in the library's own manifest and merge automatically into your app. You still need to request the runtime `BLUETOOTH_CONNECT` (Android 12+) and `POST_NOTIFICATIONS` (Android 13+) permissions at runtime — see [`BluetoothPermissions`](#bluetoothpermissions) below. USB printers need no manifest permission: the user grants access per device through a system dialog ([`requestUsbPermission`](#connection-types)).

## Quick start

```kotlin
// 1. Bind PrinterService so the connection survives your app being
//    swiped from Recents (see "Persistent connection" below for why this matters).
val serviceIntent = Intent(this, PrinterService::class.java)
startService(serviceIntent)
bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

// 2. Once bound, list paired printers and connect to one
val printers = service.printer.getBondedBluetoothPrinters(PrinterFilter.LIKELY_PRINTERS) // only likely printers
service.connectAndKeepAlive(ConnectPrinterParams(address = printers.first().address))

// ...or connect to a Wi-Fi/LAN printer by IP:
service.connectAndKeepAlive(
    ConnectPrinterParams(address = "192.168.1.50", type = PrinterConnectionType.NETWORK, port = 9100)
)

// 3. Print
service.printer.printText(PrintTextParams(text = "Hello from PrinterKit"))

// or print a full HTML receipt (Bangla, styling, everything):
service.printer.printHtml(PrintHtmlParams(html = myReceiptHtml)) { success ->
    // called on a background thread
}
```

## API reference

### `PosPrinter`

The core class. Construct with `PosPrinter(context)`, or — recommended — use the one already owned by `PrinterService` (`service.printer`) so the connection persists in the background.

All connection and print calls do blocking I/O and must be called from a background thread, except where noted.

Every function that takes data (anything beyond a bare callback) takes a single `...Params` data class instead of positional arguments, so new fields can be added later without breaking existing call sites.

| Function | Description |
|---|---|
| `isBluetoothEnabled(): Boolean` | Whether the Bluetooth adapter is on (needs no runtime permission). |
| `getBondedBluetoothPrinters(filter: PrinterFilter = ALL, extraNameKeywords: List<String> = emptyList()): List<BluetoothPrinterDevice>` | Lists paired Bluetooth devices, best printer matches first, each with a `confidence`. Pass `PrinterFilter.LIKELY_PRINTERS` to hide devices that don't look like printers. See [Finding printers](#finding-printers). |
| `getUsbPrinters(): List<UsbPrinterDevice>` | USB printers currently plugged in (USB printer class). |
| `hasUsbPermission(vendorId, productId): Boolean` | Whether the user already granted access to that USB printer. |
| `requestUsbPermission(vendorId, productId, onResult: (Boolean) -> Unit)` | Shows the system USB permission dialog; `onResult` fires on the main thread. |
| `connectPrinter(params: ConnectPrinterParams): Boolean` | Connects over Bluetooth, Wi-Fi/LAN or USB depending on `params.type`. For Bluetooth: opens an RFCOMM/SPP socket, falling back to a direct RFCOMM channel if the SDP-based socket fails. Closes any existing connection first. Remembers the connection for `autoConnectIfAvailable()`. |
| `autoConnectIfAvailable(): Boolean` | Reconnects to the last successfully connected printer, if any. Called automatically by `PrinterService` on startup. |
| `disconnectPrinter()` | Closes the connection and forgets the remembered printer. |
| `onConnectionLost: ((printerName: String?) -> Unit)?` | Optional listener, always called on the **main thread**, when the connection drops *on its own* (not when you call `disconnectPrinter()`): printer off / out of range, Bluetooth turned off, USB unplugged, or a write that could not be recovered by the automatic reconnect. `PrinterService` uses it to swap its notification; set your own to update your UI. |
| `isConnectedPrinter(): Boolean` | Whether a printer connection is currently open. For Bluetooth it is kept accurate by Android's ACL-disconnect broadcast and Bluetooth-off events; for USB by the USB-detach event; a dropped Wi-Fi/LAN link is only noticed on the next write (which then reconnects once automatically). |
| `isPrinterReady(): Boolean` | Connected **and** the printer accepts bytes right now (sends ESC/POS initialize). |
| `getPrinterStatus(timeoutMs: Int = 800): PrinterStatus` | Online / paper out / paper near end / cover open / error via ESC/POS status requests. Printers that don't answer give `responded = false` with null fields (= unknown). |
| `printTestPage()` | Prints a short ASCII test page (connection type, printer, address, time). |
| `getConnectedPrinter(): BluetoothPrinterDevice?` | The currently connected printer, or `null`. For Wi-Fi/LAN `address` is the host and `name` is null; for USB `address` is `"vendorId:productId"`. |
| `printText(params: PrintTextParams)` | Sends raw text using the printer's built-in font. **ASCII only** — not for Bangla or other non-Latin scripts. Retries once (reconnect + resend) if the write fails. |
| `printImageBitmap(params: PrintImageBitmapParams)` | Prints a `Bitmap` as a dithered ESC/POS raster image, sent in paced bands for reliability on cheap boards. Retries once (reconnect + resend) if the write fails. |
| `printImageFile(params: PrintImageFileParams)` | Decodes an image file and prints it. |
| `printImageBase64(params: PrintImageBase64Params)` | Decodes a base64-encoded image and prints it. |
| `pdfToImage(params: PdfToImageParams): String` | Renders one PDF page to an image file (via Android's `PdfRenderer`) and returns its path. |
| `printPdf(params: PrintPdfParams)` | `pdfToImage` + print, in one call. |
| `htmlToPdf(params: HtmlToPdfParams, onResult: (String?) -> Unit)` | Renders HTML to a PDF file using an off-screen `WebView` (no external library). **Must be called from any thread — it hops to the main thread internally; `onResult` always fires on the main thread.** |
| `printHtml(params: PrintHtmlParams, onResult: (Boolean) -> Unit = {})` | Full pipeline: `htmlToPdf` → `printPdf`. `onResult` fires on a background thread. |
| `saveFile(params: SaveFileParams): SaveFileResult` | Copies a finished file (e.g. the PDF from `htmlToPdf`) to cache, app storage, public Downloads/Documents or a chosen folder. Never throws — check `result.success`. See [Saving files](#saving-files). |
| `openFile(path: String, mimeType: String? = null): Boolean` | Opens a file with an installed viewer app. |

**Params data classes:**

```kotlin
data class ConnectPrinterParams(
    val address: String,                // Bluetooth MAC, or IP/host for NETWORK
    val notificationTitle: String = "Printer connected", // foreground notification title (PrinterService)
    val type: PrinterConnectionType = PrinterConnectionType.BLUETOOTH, // BLUETOOTH | NETWORK | USB
    val port: Int = 9100,               // NETWORK only
    val timeoutMs: Int = 5000,          // NETWORK connect timeout
    val vendorId: Int? = null,          // USB only (from getUsbPrinters())
    val productId: Int? = null,         // USB only
    val disconnectedNotificationTitle: String = "Printer disconnected" // shown if the printer drops later
)

data class PrintTextParams(val text: String, val feedLines: Int = 3)

data class PrintImageBitmapParams(
    val bitmap: Bitmap,
    val printerWidthDots: Int = 384,
    val feedLines: Int = 3,
    val bandHeightDots: Int = 16, // lower this if a printer still drops/garbles large images
    val bandDelayMs: Long = 60L  // raise this if a printer still drops/garbles large images
)

data class PrintImageFileParams(
    val imagePath: String,
    val printerWidthDots: Int = 384,
    val feedLines: Int = 3,
    val bandHeightDots: Int = 16,
    val bandDelayMs: Long = 60L
)

data class PrintImageBase64Params(
    val base64: String,
    val printerWidthDots: Int = 384,
    val feedLines: Int = 3,
    val bandHeightDots: Int = 16,
    val bandDelayMs: Long = 60L
)

data class PdfToImageParams(
    val pdfPath: String,
    val imageType: PrinterImageType = PrinterImageType.PNG,
    val page: Int = 0,
    val targetWidthPx: Int? = null,
    val outputDir: String? = null // null -> the app's cache dir
)

data class PrintPdfParams(
    val pdfPath: String,
    val printerWidthDots: Int = 384,
    val page: Int = 0,
    val feedLines: Int = 3,
    val bandHeightDots: Int = 16,
    val bandDelayMs: Long = 60L
)

data class HtmlToPdfParams(
    val html: String,
    val outputPath: String? = null, // null -> a fresh file under the app's cache dir
    val pageWidthDp: Int = 412,
    val heightDp: Int? = null,
    val minPageHeightDp: Int = 1000
)

data class PrintHtmlParams(
    val html: String,
    val printerWidthDots: Int = 384,
    val pageWidthDp: Int = 412,
    val heightDp: Int? = null,
    val minPageHeightDp: Int = 1000,
    val bandHeightDots: Int = 16,
    val bandDelayMs: Long = 60L
)
```

**HTML sizing parameters** (`pageWidthDp`, `heightDp`, `minPageHeightDp`): `pageWidthDp` controls how large your HTML's content renders (like a CSS viewport width), independent of the final printed width (`printerWidthDots` downscales to that). Leave `heightDp` unset to auto-measure your HTML's real content height (recommended); set it to force an exact page height instead.

**Image banding parameters** (`bandHeightDots`, `bandDelayMs`): images are sent as separate raster commands per `bandHeightDots`-tall horizontal band, paced `bandDelayMs` apart, instead of one command for the whole image — cheap ESC/POS boards have a small receive buffer and can silently drop or garble an oversized/bursty command. If a printer still drops/garbles large images, lower `bandHeightDots` and/or raise `bandDelayMs` further.

### `PrinterService`

A foreground `Service` that owns a `PosPrinter` instance so the connection survives the app being swiped away from Recents. Bind to it and use `service.printer` for everything except connect/disconnect, which go through the service so it can promote/demote itself to foreground:

| Member | Description |
|---|---|
| `printer: PosPrinter` | The shared printer instance — use this for `printText`, `printHtml`, etc. |
| `connectAndKeepAlive(params: ConnectPrinterParams): Boolean` | Connects (any connection type) and, on success, starts the foreground notification that keeps the process (and connection) alive after the app leaves Recents. The notification title comes from `params.notificationTitle`. |
| `notificationTitle: String` | Foreground notification title; set it right after binding to also cover auto-reconnect on startup. |
| `disconnectedNotificationTitle: String` | Title of the notification shown when the printer disconnects on its own (default "Printer disconnected"; also set from `params.disconnectedNotificationTitle`). |
| `disconnect()` | Disconnects on purpose and drops the foreground/notification state (no "disconnected" notification). |
| `LocalBinder.getService(): PrinterService` | Retrieve the service instance from `onServiceConnected`. |

### `BluetoothPermissions`

Runtime permission helpers. Two independent grants are involved: `BLUETOOTH_CONNECT` (Android 12+) actually gates connecting/printing; `POST_NOTIFICATIONS` (Android 13+) only controls whether `PrinterService`'s "printer connected" notification is *visible* — the foreground service still runs, and the connection still survives the app being swiped from Recents, without it.

| Function | Description |
|---|---|
| `isGranted(context: Context): Boolean` | Whether `BLUETOOTH_CONNECT` specifically is granted (always `true` below Android 12) — the permission that actually gates connect/print. |
| `getRequiredPermissions(): Array<String>` | Every permission this device's Android version needs for the full experience — `BLUETOOTH_CONNECT` (12+) and `POST_NOTIFICATIONS` (13+) — so a single request covers both. |
| `request(activity: Activity, requestCode: Int = REQUEST_CODE)` | Shows the system permission dialog(s) for `getRequiredPermissions()` via the classic `ActivityCompat` API — for hosts not using `ActivityResultContracts`. |
| `isGrantResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray, expectedRequestCode: Int = REQUEST_CODE): Boolean` | Call from `onRequestPermissionsResult` (forward all three of its arguments) to read the outcome of `request()` — keys off `BLUETOOTH_CONNECT`'s result specifically, so a denied `POST_NOTIFICATIONS` alone doesn't count as failure. |

If your Activity is a `ComponentActivity`, prefer `registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())` with `BluetoothPermissions.getRequiredPermissions()` for the check — mixing both permission mechanisms in the same Activity is unreliable, and `RequestPermission()` (singular) only requests one permission, silently skipping the rest when `getRequiredPermissions()` returns more than one.

### Data types

All data classes and enums live in the `com.rezaul.printerkit.model` package — import them from there (e.g. `import com.rezaul.printerkit.model.PrintTextParams`).

```kotlin
data class BluetoothPrinterDevice(
    val name: String?,
    val address: String,
    val confidence: PrinterConfidence = UNKNOWN, // CONFIDENT | LIKELY | UNKNOWN
    val reason: String? = null                   // e.g. "Printer-like name + SPP service"
)

data class UsbPrinterDevice(val name: String?, val vendorId: Int, val productId: Int, val deviceName: String)

data class PrinterStatus(
    val connected: Boolean, val responded: Boolean,
    val online: Boolean? = null, val paperOut: Boolean? = null, val paperNearEnd: Boolean? = null,
    val coverOpen: Boolean? = null, val hasError: Boolean? = null // null = unknown
) { fun toMap(): Map<String, Any?> }

enum class PrinterConnectionType { BLUETOOTH, NETWORK, USB }
enum class PrinterFilter { ALL, LIKELY_PRINTERS, STRICT }
enum class PrinterImageType { PNG, JPEG }
enum class FileLocation { CACHE, APP_FILES, DOWNLOADS, DOCUMENTS, CUSTOM_FOLDER }

data class SaveFileParams(
    val sourcePath: String,
    val fileName: String? = null,        // defaults to the source file's name
    val location: FileLocation = FileLocation.DOWNLOADS,
    val customFolder: String? = null,    // required for CUSTOM_FOLDER: a content:// tree Uri string or a directory path
    val subFolder: String? = null,
    val mimeType: String? = null,        // guessed from the extension when null
    val overwrite: Boolean = false,
    val deleteSource: Boolean = false
)

data class SaveFileResult(
    val success: Boolean, val path: String? = null, val uri: String? = null,
    val fileName: String? = null, val error: String? = null
) { fun toMap(): Map<String, Any?> }
```

## Connection types

`ConnectPrinterParams.type` picks the transport. Everything else (`printText`, `printHtml`, ...) is the same for all three.

```kotlin
// Bluetooth (default) — printer must be paired first
printer.connectPrinter(ConnectPrinterParams(address = "AA:BB:CC:DD:EE:FF"))

// Wi-Fi / LAN
printer.connectPrinter(ConnectPrinterParams("192.168.1.50", type = PrinterConnectionType.NETWORK, port = 9100))

// USB — ask for permission first (system dialog), then connect
val usb = printer.getUsbPrinters().first()
printer.requestUsbPermission(usb.vendorId, usb.productId) { granted ->
    if (granted) Thread {
        printer.connectPrinter(
            ConnectPrinterParams("", type = PrinterConnectionType.USB, vendorId = usb.vendorId, productId = usb.productId)
        )
    }.start()
}
```

Notes:

- **Wi-Fi/LAN** needs the phone and printer on the same network and has no discovery — the user supplies the IP. A dropped link is only noticed on the next write, which reconnects once automatically.
- **USB** lists devices that expose the USB *printer* class (7); a printer that reports a vendor-specific class will not appear in `getUsbPrinters()`. USB permission is per device and is **lost when the app is reinstalled** (run `requestUsbPermission` again). Unplugging the printer is detected immediately.
- `connectPrinter` returns `false` instead of throwing; for USB it also returns `false` when permission is missing.

## Connection lost / notifications

When a connected printer drops on its own, `PosPrinter` clears the dead connection and calls `onConnectionLost`. `PrinterService` hooks into it: the ongoing **"Printer connected"** notification is removed and a normal, dismissible **"Printer disconnected — \<printer name\>"** notification is posted (the service then stops itself). Reconnecting removes it again. Triggers: ACL disconnect (printer off / out of range), Bluetooth turned off, USB unplugged, and a write failure that the single automatic reconnect could not fix. Calling `disconnect()` yourself shows no notification. Change the wording with `disconnectedNotificationTitle`.

## Finding printers

Bluetooth can't say for certain that a paired device is a printer (cheap POS printers often report a wrong class or none), so `getBondedBluetoothPrinters()` combines weak signals — Bluetooth class, SPP service, printer-like name — into a `confidence`:

- `CONFIDENT`: class says printer, or name and SPP service both match.
- `LIKELY`: name looks like a printer, or class is "imaging".
- `UNKNOWN`: no sign of a printer (BLE-only devices are also `UNKNOWN`).

`PrinterFilter.ALL` (default) returns every paired device, sorted best match first; `LIKELY_PRINTERS` returns `CONFIDENT` + `LIKELY`; `STRICT` only `CONFIDENT`. If a real printer has an unusual name and is hidden by a filter, pass `extraNameKeywords = listOf("part-of-its-name")`, or offer a "show all devices" option.

## Printer checks

```kotlin
if (!printer.isPrinterReady()) { /* switched off, out of range, or not connected */ }

val status = printer.getPrinterStatus()
if (status.responded && status.paperOut == true) { /* out of paper */ }

printer.printTestPage()
```

`getPrinterStatus()` relies on ESC/POS real-time status requests (`DLE EOT 1/2/4`). What to know:

- Many cheap printers ignore them — then `responded` is `false` and every field is `null` (unknown, **not** "ok").
- Right after connecting, the first request can time out (`responded = false`); calling it again a moment later works.
- Whether a field can ever be `true` depends on the printer's firmware. On the USB receipt printer used for testing, opening the cover reported **`online = false, paperOut = true`** and never `coverOpen = true` (the printer treats an open cover as "no paper" and does not report a separate cover bit). So read `paperOut = true` as "no paper *or* cover open", and don't rely on `coverOpen` unless you have verified it on your printer model.
- `online = false` is the most reliable "don't print now" signal.
- `isPrinterReady()` only proves the printer accepts bytes; it does not check paper or cover. And a print call returning normally only means the bytes were sent — an offline / out-of-paper printer may still accept them, so check `getPrinterStatus()` before printing when it matters.

## Saving files

```kotlin
val result = printer.saveFile(
    SaveFileParams(sourcePath = pdfPath, fileName = "invoice.pdf", location = FileLocation.DOWNLOADS, subFolder = "MyApp")
)
if (result.success) printer.openFile(result.path ?: result.uri!!)
```

- `CACHE` / `APP_FILES`: app-private folders (cache may be cleared by the system).
- `DOWNLOADS` / `DOCUMENTS`: public folders visible in the Files app. Android 10+ uses MediaStore (no permission needed); Android 9 and below needs `WRITE_EXTERNAL_STORAGE`.
- `CUSTOM_FOLDER`: a folder the user picked with the system folder picker (`content://` tree Uri string) or an absolute directory path.
- With `overwrite = false` an existing file is an error for plain paths and auto-renamed by the system for Downloads/Documents on Android 10+. `overwrite = true` can only replace files this app created.

## Using from Flutter / React Native

The API is shaped for thin bridge wrappers: parameters are strings, numbers, booleans and enums (enums map to their names); `Bitmap`/`Uri` objects never cross it; `PrinterStatus`, `SaveFileResult` and similar have `toMap()`. Print and connect calls block, so run them on a background thread. Keep enum names stable — wrappers send them as strings.

## Project structure

| File | Role |
|---|---|
| `PosPrinter.kt` | Public API: connect, print, status, save, USB permission. |
| `PrinterService.kt` | Foreground service that owns the connection and its notifications. |
| `PrinterTransport.kt` | Internal: `BluetoothTransport`, `NetworkTransport`, `UsbTransport` — the only part that knows how bytes reach the printer. |
| `PrinterClassifier.kt` | Internal: guesses whether a paired Bluetooth device is a printer. |
| `FileSaver.kt` | Internal: backs `saveFile()` (MediaStore / SAF / plain paths). |
| `HtmlToPdf.kt` | Off-screen `WebView` → PDF. |
| `BluetoothPermissions.kt` | Runtime permission helpers. |
| `model/` | All data classes and enums (`PrinterModels.kt`, `SaveModels.kt`). |

## Tested with

Verified on real hardware (Samsung SM-A525F, Android 14):

- Bluetooth: a "Mobile Printer" (ESC/POS) — device list and filter, connect, `isPrinterReady`, `getPrinterStatus` (reports paper out correctly), disconnect notification when Bluetooth is turned off.
- USB: a "USB Receipt Printer" (4070:33054) — discovery, permission dialog, connect, `isPrinterReady`, `getPrinterStatus`, disconnect notification when unplugged.
- `saveFile()` to public Downloads.

Not yet verified on hardware: Wi-Fi/LAN printing, `CUSTOM_FOLDER` saving, 80 mm paper, and `coverOpen` on a printer that reports it. Treat those paths as implemented but untested.

## Persistent connection

A `PosPrinter` held directly by an `Activity` disconnects the moment the app's process dies — including when the user swipes the app away from Recents. `PrinterService` avoids this: while a printer is connected, it runs as a **foreground service** with a small ongoing notification, which keeps its process (and the socket) alive independently of any Activity. If the printer disconnects on its own, that notification is replaced by a "Printer disconnected" one (see [Connection lost / notifications](#connection-lost--notifications)). Bind to it once at app startup (see [Quick start](#quick-start)) rather than constructing `PosPrinter` yourself.

## Auto-reconnect

Every successful `connectPrinter()` call remembers the printer — connection type, address/IP, port and USB ids (`SharedPreferences`, cleared on `disconnectPrinter()`). `PrinterService` calls `autoConnectIfAvailable()` once when it's first created, so a user re-opening the app after it was fully killed reconnects to their printer automatically instead of having to pick it again.

## Printing Bangla / other non-Latin text

ESC/POS printer firmware fonts only cover ASCII. `printText()` reflects that limitation directly. For anything with Bangla (or other non-Latin scripts), render it as an image instead — either build a `Bitmap` yourself and call `printImageBitmap()`, or write it as HTML/CSS and call `printHtml()`, which renders it with a real font via `WebView` and prints the result as a dithered raster image.

## License

Copyright 2026 DeveloperRejaul. Licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.
