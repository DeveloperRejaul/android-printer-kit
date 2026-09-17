# PrinterKit

[![Release](https://img.shields.io/github/v/release/DeveloperRejaul/android-printer-kit)](https://github.com/DeveloperRejaul/android-printer-kit)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A professional Android library for talking to Bluetooth POS/thermal (ESC/POS) printers directly from your app — no third-party printer app required. It connects over Bluetooth Classic (SPP), keeps that connection alive in the background via a foreground service, and can print raw text, images, PDFs, and full **HTML content — including non-Latin scripts like Bangla** that ESC/POS printer fonts don't support natively.

## Why this exists

Most ESC/POS printer libraries only send raw text or a single pre-made image. They can't render real content — an actual HTML/CSS receipt layout, a PDF, or text in a script the printer's built-in font doesn't have (Bangla, Arabic, Hindi, etc.). PrinterKit solves that by rendering your HTML/PDF to a bitmap on-device (using Android's own `WebView` and `PdfRenderer` — no external rendering library) and sending it to the printer as a dithered ESC/POS raster image.

## Features

- **Direct Bluetooth Classic (SPP) connection** to any paired ESC/POS printer — no OS print dialog, no third-party printer app.
- **HTML → PDF → print** pipeline, so you can print an actual styled receipt/report, not just plain text.
- **PDF → image → print** pipeline using Android's built-in `PdfRenderer` (no external dependency).
- **Non-Latin text support** (Bangla and others) via image-based rendering, bypassing ESC/POS's font limitations.
- **Persistent connection**: printer stays connected even if the app is swiped away from Recents, via a foreground `BluetoothPrinterService`.
- **Auto-reconnect**: remembers the last connected printer and reconnects automatically the next time the app starts.
- **Resilient connect**: falls back to a direct RFCOMM channel when a printer's SDP service record is broken, and actively detects real disconnects (via Android's ACL-disconnect broadcast) instead of trusting a stale socket flag.
- **Banded raster printing**: images are sent in small, paced chunks — many cheap ESC/POS boards silently drop large single print commands, and banding avoids that.
- **Permission helpers**: `BluetoothPermissions` wraps the Android 12+ `BLUETOOTH_CONNECT` and Android 13+ `POST_NOTIFICATIONS` runtime permissions so you don't have to handle version checks yourself.

## Scope

This library speaks **Bluetooth Classic SPP + ESC/POS** — the standard used by virtually all budget Bluetooth receipt/thermal printers. It does **not** currently support Bluetooth LE printers, WiFi/network printers, USB-connected printers, or non-ESC/POS protocols (PCL/PostScript inkjet & laser printers). The library's structure keeps room for additional transports later, but only Bluetooth ESC/POS is implemented today.

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
    implementation("com.github.DeveloperRejaul:android-printer-kit:v0.0.1")
}
```

### 3. Permissions

Nothing to add manually — `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, and `POST_NOTIFICATIONS` are declared in the library's own manifest and merge automatically into your app. You still need to request the runtime `BLUETOOTH_CONNECT` (Android 12+) and `POST_NOTIFICATIONS` (Android 13+) permissions at runtime — see [Permissions](#permissions) below.

## Quick start

```kotlin
// 1. Bind BluetoothPrinterService so the connection survives your app being
//    swiped from Recents (see "Persistent connection" below for why this matters).
val serviceIntent = Intent(this, BluetoothPrinterService::class.java)
startService(serviceIntent)
bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

// 2. Once bound, list paired printers and connect to one
val printers = service.printer.getBondedBluetoothPrinters()
service.connectAndKeepAlive(ConnectPrinterParams(address = printers.first().address))

// 3. Print
service.printer.printText(PrintTextParams(text = "Hello from PrinterKit"))

// or print a full HTML receipt (Bangla, styling, everything):
service.printer.printHtml(PrintHtmlParams(html = myReceiptHtml)) { success ->
    // called on a background thread
}
```

## API reference

### `BluetoothPrinter`

The core class. Construct with `BluetoothPrinter(context)`, or — recommended — use the one already owned by `BluetoothPrinterService` (`service.printer`) so the connection persists in the background.

All connection and print calls do blocking I/O and must be called from a background thread, except where noted.

Every function that takes data (anything beyond a bare callback) takes a single `...Params` data class instead of positional arguments, so new fields can be added later without breaking existing call sites.

| Function | Description |
|---|---|
| `getBondedBluetoothPrinters(): List<BluetoothPrinterDevice>` | Lists Bluetooth devices already paired via Android's own Bluetooth settings. |
| `connectPrinter(params: ConnectPrinterParams): Boolean` | Opens an RFCOMM/SPP socket to the paired device, falling back to a direct RFCOMM channel if the standard SDP-based socket fails. Closes any existing connection first. Remembers the address for `autoConnectIfAvailable()`. |
| `autoConnectIfAvailable(): Boolean` | Reconnects to the last successfully connected printer, if any. Called automatically by `BluetoothPrinterService` on startup. |
| `disconnectPrinter()` | Closes the connection and forgets the remembered address. |
| `isConnectedPrinter(): Boolean` | Whether a printer socket is currently open. Kept accurate by listening for Android's ACL-disconnect broadcast, so it reflects a real physical disconnect immediately instead of a stale flag. |
| `getConnectedPrinter(): BluetoothPrinterDevice?` | The currently connected device, or `null`. |
| `printText(params: PrintTextParams)` | Sends raw text using the printer's built-in font. **ASCII only** — not for Bangla or other non-Latin scripts. Retries once (reconnect + resend) if the write fails. |
| `printImageBitmap(params: PrintImageBitmapParams)` | Prints a `Bitmap` as a dithered ESC/POS raster image, sent in paced bands for reliability on cheap boards. Retries once (reconnect + resend) if the write fails. |
| `printImageFile(params: PrintImageFileParams)` | Decodes an image file and prints it. |
| `printImageBase64(params: PrintImageBase64Params)` | Decodes a base64-encoded image and prints it. |
| `pdfToImage(params: PdfToImageParams): String` | Renders one PDF page to an image file (via Android's `PdfRenderer`) and returns its path. |
| `printPdf(params: PrintPdfParams)` | `pdfToImage` + print, in one call. |
| `htmlToPdf(params: HtmlToPdfParams, onResult: (String?) -> Unit)` | Renders HTML to a PDF file using an off-screen `WebView` (no external library). **Must be called from any thread — it hops to the main thread internally; `onResult` always fires on the main thread.** |
| `printHtml(params: PrintHtmlParams, onResult: (Boolean) -> Unit = {})` | Full pipeline: `htmlToPdf` → `printPdf`. `onResult` fires on a background thread. |

**Params data classes:**

```kotlin
data class ConnectPrinterParams(val address: String)

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

### `BluetoothPrinterService`

A foreground `Service` that owns a `BluetoothPrinter` instance so the connection survives the app being swiped away from Recents. Bind to it and use `service.printer` for everything except connect/disconnect, which go through the service so it can promote/demote itself to foreground:

| Member | Description |
|---|---|
| `printer: BluetoothPrinter` | The shared printer instance — use this for `printText`, `printHtml`, etc. |
| `connectAndKeepAlive(params: ConnectPrinterParams): Boolean` | Connects and, on success, starts the foreground notification that keeps the process (and connection) alive after the app leaves Recents. |
| `disconnect()` | Disconnects and drops the foreground/notification state. |
| `LocalBinder.getService(): BluetoothPrinterService` | Retrieve the service instance from `onServiceConnected`. |

### `BluetoothPermissions`

Runtime permission helpers. Two independent grants are involved: `BLUETOOTH_CONNECT` (Android 12+) actually gates connecting/printing; `POST_NOTIFICATIONS` (Android 13+) only controls whether `BluetoothPrinterService`'s "printer connected" notification is *visible* — the foreground service still runs, and the connection still survives the app being swiped from Recents, without it.

| Function | Description |
|---|---|
| `isGranted(context: Context): Boolean` | Whether `BLUETOOTH_CONNECT` specifically is granted (always `true` below Android 12) — the permission that actually gates connect/print. |
| `getRequiredPermissions(): Array<String>` | Every permission this device's Android version needs for the full experience — `BLUETOOTH_CONNECT` (12+) and `POST_NOTIFICATIONS` (13+) — so a single request covers both. |
| `request(activity: Activity, requestCode: Int = REQUEST_CODE)` | Shows the system permission dialog(s) for `getRequiredPermissions()` via the classic `ActivityCompat` API — for hosts not using `ActivityResultContracts`. |
| `isGrantResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray, expectedRequestCode: Int = REQUEST_CODE): Boolean` | Call from `onRequestPermissionsResult` (forward all three of its arguments) to read the outcome of `request()` — keys off `BLUETOOTH_CONNECT`'s result specifically, so a denied `POST_NOTIFICATIONS` alone doesn't count as failure. |

If your Activity is a `ComponentActivity`, prefer `registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())` with `BluetoothPermissions.getRequiredPermissions()` for the check — mixing both permission mechanisms in the same Activity is unreliable, and `RequestPermission()` (singular) only requests one permission, silently skipping the rest when `getRequiredPermissions()` returns more than one.

### Data types

```kotlin
data class BluetoothPrinterDevice(val name: String?, val address: String)

enum class PrinterImageType { PNG, JPEG }
```

## Persistent connection

A `BluetoothPrinter` held directly by an `Activity` disconnects the moment the app's process dies — including when the user swipes the app away from Recents. `BluetoothPrinterService` avoids this: while a printer is connected, it runs as a **foreground service** with a small ongoing notification, which keeps its process (and the socket) alive independently of any Activity. Bind to it once at app startup (see [Quick start](#quick-start)) rather than constructing `BluetoothPrinter` yourself.

## Auto-reconnect

Every successful `connectPrinter()` call remembers the device's address (`SharedPreferences`, cleared on `disconnectPrinter()`). `BluetoothPrinterService` calls `autoConnectIfAvailable()` once when it's first created, so a user re-opening the app after it was fully killed reconnects to their printer automatically instead of having to pick it again.

## Printing Bangla / other non-Latin text

ESC/POS printer firmware fonts only cover ASCII. `printText()` reflects that limitation directly. For anything with Bangla (or other non-Latin scripts), render it as an image instead — either build a `Bitmap` yourself and call `printImageBitmap()`, or write it as HTML/CSS and call `printHtml()`, which renders it with a real font via `WebView` and prints the result as a dithered raster image.

## License

Copyright 2026 DeveloperRejaul. Licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.
