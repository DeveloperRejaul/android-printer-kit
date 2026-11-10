package com.rezaul.printerkitapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rezaul.printerkitapp.ui.theme.PrinterKitTheme
import com.rezaul.printerkit.BluetoothPermissions
import com.rezaul.printerkit.PosPrinter
import com.rezaul.printerkit.PrinterService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import com.rezaul.printerkit.model.BluetoothPrinterDevice
import com.rezaul.printerkit.model.ConnectPrinterParams
import com.rezaul.printerkit.model.FileLocation
import com.rezaul.printerkit.model.PrinterConnectionType
import com.rezaul.printerkit.model.ReadDirParams
import com.rezaul.printerkit.model.ReadFileFormat
import com.rezaul.printerkit.model.ReadFileParams
import com.rezaul.printerkit.model.HtmlToImageParams
import com.rezaul.printerkit.model.PrinterImageType
import com.rezaul.printerkit.model.PrinterFilter
import com.rezaul.printerkit.model.RemoveFileParams
import com.rezaul.printerkit.model.PrintTextParams
import com.rezaul.printerkit.model.SaveFileParams
import com.rezaul.printerkit.model.PrintImageBitmapParams
import com.rezaul.printerkit.model.PrintImageFileParams
import com.rezaul.printerkit.model.PrintImageBase64Params
import com.rezaul.printerkit.model.PdfToImageParams
import com.rezaul.printerkit.model.PrintPdfParams
import com.rezaul.printerkit.model.HtmlToPdfParams
import com.rezaul.printerkit.model.PrintHtmlParams

class MainActivity : ComponentActivity() {

    // The Bluetooth POS printer connection lives in PrinterService (not here),
    // so it survives the app being swiped away from Recents - see PrinterService
    // for why a connection held directly by the Activity can't do that. This state holds
    // the bound service once it's ready; the Compose UI observes it.
    private var printerService by mutableStateOf<PrinterService?>(null)
    private val printerServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            printerService = (binder as PrinterService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            printerService = null
        }
    }

    // The permission logic itself (what's granted, what's required) lives in the
    // library's BluetoothPermissions - only the actual system dialog launch/result
    // plumbing stays here, via registerForActivityResult (ComponentActivity's modern,
    // conflict-free mechanism; BluetoothPermissions also exposes a classic
    // ActivityCompat.requestPermissions()/onRequestPermissionsResult pair in the
    // library for hosts that aren't a ComponentActivity, but mixing both mechanisms
    // in the same Activity is unreliable, so this screen sticks to one).
    //
    // getRequiredPermissions() can return more than one permission (BLUETOOTH_CONNECT
    // and, on Android 13+, POST_NOTIFICATIONS), so this uses RequestMultiplePermissions
    // rather than RequestPermission (single) - launching only the first would silently
    // never ask for the rest.
    private var onBluetoothPermissionResult: ((Boolean) -> Unit)? = null
    private val requestBluetoothPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // Only BLUETOOTH_CONNECT actually gates the printer's core features -
            // POST_NOTIFICATIONS only affects whether the "connected" notification is
            // visible, so its result (if present) doesn't fail this callback.
            val connectGranted = results[Manifest.permission.BLUETOOTH_CONNECT] ?: true
            onBluetoothPermissionResult?.invoke(connectGranted)
        }

    /** Ensures Bluetooth permission is granted (a no-op on Android < 12), then calls [onResult]. */
    private fun ensureBluetoothPermission(onResult: (Boolean) -> Unit) {
        val required = BluetoothPermissions.getRequiredPermissions()
        val alreadyGranted = required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (required.isEmpty() || alreadyGranted) {
            onResult(true)
            return
        }
        onBluetoothPermissionResult = onResult
        requestBluetoothPermissions.launch(required)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // startService (not just bindService) so the service - and any printer
        // connection made through it - keeps running even if the Activity unbinds
        // (e.g. the app is swiped away from Recents), not just while this screen is open.
        val serviceIntent = Intent(this, PrinterService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, printerServiceConnection, Context.BIND_AUTO_CREATE)

        enableEdgeToEdge()
        setContent {
            PrinterKitTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        val service = printerService
                        if (service == null) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else {
                            BluetoothPrinterTestScreen(
                                printerService = service,
                                ensurePermission = ::ensureBluetoothPermission,
                                showToast = { msg ->
                                    // Several TestButton actions call this from a
                                    // background thread (run() executes its block on
                                    // Dispatchers.IO) - Toast requires a thread with a
                                    // prepared Looper, so always hop to the main thread.
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                cacheDir = cacheDir
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only unbind - do NOT stop the service. It (and any live printer connection)
        // is meant to keep running independently of this Activity/task.
        unbindService(printerServiceConnection)
    }
}

/**
 * A one-screen manual test bench for every [PosPrinter] function:
 * pairing/connect, raw text printing, image printing, and the PDF -> image -> print pipeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothPrinterTestScreen(
    modifier: Modifier = Modifier,
    printerService: PrinterService,
    ensurePermission: ((Boolean) -> Unit) -> Unit,
    showToast: (String) -> Unit,
    cacheDir: File
) {
    // The actual connection lives in printerService (a foreground service), so it
    // survives the app being swiped from Recents - see PrinterService.
    // connectPrinter()/disconnectPrinter() go through the service; every other call
    // (printText, printHtml, etc.) uses this shared printer instance as before.
    val printer = printerService.printer
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<BluetoothPrinterDevice>>(emptyList()) }
    var connected by remember { mutableStateOf<BluetoothPrinterDevice?>(null) }
    var status by remember { mutableStateOf("Idle") }
    // Which button's action is currently running (also its label) - null when idle.
    var activeAction by remember { mutableStateOf<String?>(null) }
    var showPickerSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    fun run(label: String, onSuccess: () -> Unit = {}, block: () -> Unit) {
        activeAction = label
        status = "$label..."
        scope.launch(Dispatchers.IO) {
            try {
                block()
                withContext(Dispatchers.Main) {
                    status = "$label: OK"
                    onSuccess()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status = "$label FAILED: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) { activeAction = null }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Status: $status", fontWeight = FontWeight.Bold)
        Text("Connected: ${connected?.let { "${it.name ?: "Unknown"} (${it.address})" } ?: "-"}")

        Spacer(Modifier.height(12.dp))

        // Every button below is named after the exact PosPrinter/BluetoothPermissions function it calls.

        TestButton(label = "BluetoothPermissions.isGranted(context)", activeAction = activeAction) {
            run("BluetoothPermissions.isGranted(context)") {
                val granted = BluetoothPermissions.isGranted(context)
                showToast("isGranted() = $granted")
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "ensureBluetoothPermission()  — uses BluetoothPermissions.getRequiredPermissions()", activeAction = activeAction) {
            activeAction = "ensureBluetoothPermission()  — uses BluetoothPermissions.getRequiredPermissions()"
            ensurePermission { granted ->
                activeAction = null
                showToast(if (granted) "Permission granted" else "Permission denied")
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        TestButton(label = "getBondedBluetoothPrinters()", activeAction = activeAction) {
            activeAction = "getBondedBluetoothPrinters()"
            ensurePermission { granted ->
                activeAction = null
                if (granted) {
                    devices = printer.getBondedBluetoothPrinters()
                    showPickerSheet = true
                } else {
                    showToast("Bluetooth permission denied")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "getBondedBluetoothPrinters(LIKELY_PRINTERS)", activeAction = activeAction) {
            activeAction = "getBondedBluetoothPrinters(LIKELY_PRINTERS)"
            ensurePermission { granted ->
                activeAction = null
                if (granted) {
                    devices = printer.getBondedBluetoothPrinters(PrinterFilter.LIKELY_PRINTERS)
                    showPickerSheet = true
                } else {
                    showToast("Bluetooth permission denied")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "getUsbPrinters()", activeAction = activeAction) {
            run("getUsbPrinters()") {
                val usb = printer.getUsbPrinters()
                showToast("getUsbPrinters() = ${usb.joinToString { "${it.name ?: "USB"} (${it.vendorId}:${it.productId})" }.ifEmpty { "none" }}")
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "requestUsbPermission(first USB printer)", activeAction = activeAction) {
            val usb = printer.getUsbPrinters().firstOrNull()
            if (usb == null) {
                showToast("No USB printer found")
            } else {
                activeAction = "requestUsbPermission(first USB printer)"
                printer.requestUsbPermission(usb.vendorId, usb.productId) { granted ->
                    activeAction = null
                    showToast("USB permission = $granted")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "connectAndKeepAlive(USB)", activeAction = activeAction) {
            run("connectAndKeepAlive(USB)", onSuccess = { connected = printer.getConnectedPrinter() }) {
                val usb = printer.getUsbPrinters().firstOrNull() ?: throw IllegalStateException("No USB printer found")
                val ok = printerService.connectAndKeepAlive(
                    ConnectPrinterParams(
                        address = "",
                        type = PrinterConnectionType.USB,
                        vendorId = usb.vendorId,
                        productId = usb.productId
                    )
                )
                if (!ok) throw IllegalStateException("connect returned false (USB permission granted?)")
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "isPrinterReady()", activeAction = activeAction) {
            run("isPrinterReady()") {
                showToast("isPrinterReady() = ${printer.isPrinterReady()}")
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "getPrinterStatus()", activeAction = activeAction) {
            activeAction = "getPrinterStatus()"
            status = "getPrinterStatus()..."
            scope.launch(Dispatchers.IO) {
                val result = printer.getPrinterStatus()
                android.util.Log.i("PrinterKitTest", "getPrinterStatus() = ${result.toMap()}")
                withContext(Dispatchers.Main) {
                    status = "getPrinterStatus() = ${result.toMap()}"
                    activeAction = null
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "printTestPage()", activeAction = activeAction) {
            run("printTestPage()") { printer.printTestPage() }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "saveFile(test PDF -> Downloads/PrinterKit)", activeAction = activeAction) {
            run("saveFile(test PDF -> Downloads/PrinterKit)") {
                val pdf = generateTestPdf(cacheDir)
                val result = printer.saveFile(
                    SaveFileParams(
                        sourcePath = pdf.absolutePath,
                        fileName = "printerkit_test.pdf",
                        location = FileLocation.DOWNLOADS,
                        subFolder = "PrinterKit",
                        overwrite = true
                    )
                )
                if (!result.success) throw IllegalStateException(result.error)
                showToast("saveFile() -> ${result.path ?: result.uri}")
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "saveFile(DOWNLOADS) -> openFile", activeAction = activeAction) {
            run("saveFile(DOWNLOADS) -> openFile") {
                val pdf = generateTestPdf(cacheDir)
                val result = printer.saveFile(
                    SaveFileParams(
                        sourcePath = pdf.absolutePath,
                        fileName = "printerkit_open_test.pdf",
                        location = FileLocation.DOWNLOADS,
                        overwrite = true
                    )
                )
                if (!result.success) throw IllegalStateException(result.error)
                val target = result.path ?: result.uri ?: throw IllegalStateException("no path or uri")
                val opened = printer.openFile(target, "application/pdf")
                if (!opened) throw IllegalStateException("openFile($target) returned false")
                showToast("openFile() opened: $target")
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "saveFile(text + base64 content)", activeAction = activeAction) {
            run("saveFile(text + base64 content)") {
                val text = printer.saveFile(
                    SaveFileParams(
                        text = "name,qty\nতেল,2\nচাল,5\n", // Bangla on purpose: must survive as UTF-8
                        fileName = "printerkit_content.csv",
                        subFolder = "PrinterKit",
                        overwrite = true
                    )
                )
                if (!text.success) throw IllegalStateException("text: ${text.error}")
                // 1x1 transparent PNG, given as a data: URI to exercise the prefix handling too.
                val png = printer.saveFile(
                    SaveFileParams(
                        base64 = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
                        fileName = "printerkit_pixel.png",
                        subFolder = "PrinterKit",
                        overwrite = true
                    )
                )
                if (!png.success) throw IllegalStateException("base64: ${png.error}")
                // The same image into the Gallery (Pictures); a non-image there must be refused.
                val gallery = printer.saveFile(
                    SaveFileParams(
                        base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
                        fileName = "printerkit_pixel.png",
                        location = FileLocation.PICTURES,
                        subFolder = "PrinterKit",
                        overwrite = true
                    )
                )
                if (!gallery.success) throw IllegalStateException("PICTURES: ${gallery.error}")
                val notImage = printer.saveFile(
                    SaveFileParams(text = "x", fileName = "notes.txt", location = FileLocation.PICTURES)
                )
                if (notImage.success) throw IllegalStateException("a .txt in PICTURES should have been refused")
                // Giving two sources, or none, must be refused with a clear message.
                val both = printer.saveFile(SaveFileParams(text = "x", base64 = "eA==", fileName = "x.txt"))
                if (both.success) throw IllegalStateException("two sources should have been refused")
                showToast("saved text + png; two sources refused: ${both.error}")
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "saveFile -> removeFile", activeAction = activeAction) {
            run("saveFile -> removeFile") {
                // 1) a file in the app's own folder: path round trip
                val saved = printer.saveFile(
                    SaveFileParams(text = "delete me", fileName = "to_remove.txt", location = FileLocation.APP_FILES, overwrite = true)
                )
                if (!saved.success) throw IllegalStateException("save: ${saved.error}")
                val removedPath = printer.removeFile(RemoveFileParams(path = saved.path!!))
                if (!removedPath.success) throw IllegalStateException("remove(path): ${removedPath.error}")
                if (java.io.File(saved.path!!).exists()) throw IllegalStateException("file still exists after removeFile")
                // 2) a file in public Downloads: content:// Uri round trip (MediaStore)
                val pub = printer.saveFile(
                    SaveFileParams(text = "delete me too", fileName = "to_remove_public.txt", subFolder = "PrinterKit", overwrite = true)
                )
                if (!pub.success) throw IllegalStateException("save(public): ${pub.error}")
                val removedUri = printer.removeFile(RemoveFileParams(path = pub.path ?: pub.uri!!))
                if (!removedUri.success) throw IllegalStateException("remove(uri): ${removedUri.error}")
                // 3) things that must be refused
                val missing = printer.removeFile(RemoveFileParams(path = saved.path!!))
                val folder = printer.removeFile(RemoveFileParams(path = context.filesDir.absolutePath))
                val relative = printer.removeFile(RemoveFileParams(path = "to_remove.txt"))
                if (missing.success || folder.success || relative.success) throw IllegalStateException("a bad input was accepted")
                if (!context.filesDir.exists()) throw IllegalStateException("the folder was deleted!")
                showToast("removed path+uri; refused: missing / folder / relative")
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "readDir(path)", activeAction = activeAction) {
            run("readDir(path)") {
                val root = java.io.File(context.filesDir, "list_test")
                root.deleteRecursively()
                root.mkdirs()
                java.io.File(root, "b.txt").writeText("bb")
                java.io.File(root, "A.txt").writeText("a")
                java.io.File(root, "sub").mkdirs()
                java.io.File(root, "Zeta").mkdirs()
                try {
                    val r = printer.readDir(ReadDirParams(root.absolutePath))
                    if (!r.success) throw IllegalStateException("list: ${r.error}")
                    // Folders first (A-Z, ignoring case), then files (A-Z, ignoring case).
                    val expected = listOf("sub", "Zeta", "A.txt", "b.txt")
                    if (r.names != expected) throw IllegalStateException("wrong names/order: ${r.names}")
                    val a = r.entries.first { it.name == "A.txt" }
                    if (a.isDirectory || a.sizeBytes != 1L) throw IllegalStateException("bad entry for A.txt: $a")
                    if (!r.entries.first { it.name == "sub" }.isDirectory) throw IllegalStateException("sub not a folder")
                    // Things that must be refused:
                    val onFile = printer.readDir(ReadDirParams(java.io.File(root, "A.txt").absolutePath))
                    val missing = printer.readDir(ReadDirParams(java.io.File(root, "nope").absolutePath))
                    val relative = printer.readDir(ReadDirParams("list_test"))
                    if (onFile.success || missing.success || relative.success) throw IllegalStateException("a bad input was accepted")
                    showToast("listed ${r.names}; refused file / missing / relative")
                } finally {
                    root.deleteRecursively()
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "readFile(all formats) + htmlToImage", activeAction = activeAction) {
            run("readFile(all formats) + htmlToImage") {
                val f = java.io.File(context.cacheDir, "readfile_test.bin")
                val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0xFF.toByte(), 0x00, 0x7F)
                f.writeBytes(bytes)
                val bangla = java.io.File(context.cacheDir, "readfile_bangla.txt").apply { writeText("বাংলা লেখা ✓") }
                val utf16 = java.io.File(context.cacheDir, "readfile_utf16.txt").apply { writeText("héllo", Charsets.UTF_16) }
                try {
                    fun read(file: java.io.File, format: ReadFileFormat, encoding: String? = null, max: Long = 10_000_000L) =
                        printer.readFile(ReadFileParams(file.absolutePath, format, encoding, max))
                    val b64 = read(f, ReadFileFormat.BASE64)
                    if (!b64.success || !android.util.Base64.decode(b64.content, android.util.Base64.DEFAULT).contentEquals(bytes))
                        throw IllegalStateException("base64 round trip failed: ${b64.error}")
                    val uri = read(f, ReadFileFormat.DATA_URI)
                    if (uri.content?.startsWith("data:application/octet-stream;base64,") != true) throw IllegalStateException("dataUri: ${uri.content}")
                    val hex = read(f, ReadFileFormat.HEX)
                    if (hex.content != "504b0304ff007f") throw IllegalStateException("hex: ${hex.content}")
                    val raw = read(f, ReadFileFormat.BYTES)
                    if (raw.bytes != listOf(80, 75, 3, 4, 255, 0, 127)) throw IllegalStateException("bytes: ${raw.bytes}")
                    val txt = read(bangla, ReadFileFormat.TEXT)
                    if (txt.content != "বাংলা লেখা ✓") throw IllegalStateException("text: ${txt.content}")
                    val u16 = read(utf16, ReadFileFormat.TEXT, "UTF-16")
                    if (u16.content != "héllo") throw IllegalStateException("utf-16 text: ${u16.content}")
                    // Things that must be refused:
                    if (read(f, ReadFileFormat.BASE64, max = 3).success) throw IllegalStateException("over-limit file accepted")
                    if (read(f, ReadFileFormat.TEXT, "no-such-charset").success) throw IllegalStateException("bad encoding accepted")
                    if (printer.readFile(ReadFileParams("readfile_test.bin")).success) throw IllegalStateException("relative path accepted")
                    if (printer.readFile(ReadFileParams(context.cacheDir.absolutePath)).success) throw IllegalStateException("folder accepted")
                } finally {
                    f.delete(); bangla.delete(); utf16.delete()
                }
                // htmlToImage: result arrives on the main thread, so wait for it here on the IO thread.
                val latch = java.util.concurrent.CountDownLatch(1)
                var imagePath: String? = null
                val target = java.io.File(context.cacheDir, "h2i_named.png").absolutePath
                printer.htmlToImage(
                    HtmlToImageParams(
                        html = "<html><body style='font-size:28px'><h1>PrinterKit</h1><p>বাংলা টেস্ট</p></body></html>",
                        outputPath = target, imageType = PrinterImageType.PNG, targetWidthPx = 600
                    )
                ) { imagePath = it; latch.countDown() }
                if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) throw IllegalStateException("htmlToImage timed out")
                if (imagePath != target) throw IllegalStateException("asked for $target, got $imagePath")
                val head = printer.readFile(ReadFileParams(target, ReadFileFormat.HEX, maxBytes = 50_000_000L)).content.orEmpty()
                java.io.File(target).delete()
                if (!head.startsWith("89504e470d0a1a0a")) throw IllegalStateException("not a PNG: ${head.take(16)}")
                showToast("readFile: 5 formats + limits OK; htmlToImage wrote a real PNG")
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "disconnectPrinter()", activeAction = activeAction) {
            run("disconnectPrinter()", onSuccess = { connected = null }) { printerService.disconnect() }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "isConnectedPrinter()", activeAction = activeAction) {
            run("isConnectedPrinter()") {
                val isConnected = printer.isConnectedPrinter()
                showToast("isConnectedPrinter() = $isConnected")
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "getConnectedPrinter()", activeAction = activeAction) {
            run("getConnectedPrinter()") {
                val device = printer.getConnectedPrinter()
                showToast("getConnectedPrinter() = ${device?.let { "${it.name} (${it.address})" } ?: "null"}")
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        TestButton(label = "printText(text)", activeAction = activeAction) {
            run("printText(text)") {
                printer.printText(PrintTextParams(text = "PrinterKit\nBluetooth Printer Test\nprintText() OK"))
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "printImageBitmap(bitmap)", activeAction = activeAction) {
            run("printImageBitmap(bitmap)") {
                val bitmap = generateTestBitmap()
                printer.printImageBitmap(PrintImageBitmapParams(bitmap = bitmap))
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "printImageFile(imagePath)", activeAction = activeAction) {
            run("printImageFile(imagePath)") {
                val file = saveBitmapToFile(generateTestBitmap(), cacheDir)
                printer.printImageFile(PrintImageFileParams(imagePath = file.absolutePath))
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "printImageBase64(base64)", activeAction = activeAction) {
            run("printImageBase64(base64)") {
                val base64 = bitmapToBase64(generateTestBitmap())
                printer.printImageBase64(PrintImageBase64Params(base64 = base64))
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        TestButton(label = "pdfToImage(pdfPath, imageType)", activeAction = activeAction) {
            run("pdfToImage(pdfPath, imageType)") {
                val pdfFile = generateTestPdf(cacheDir)
                val imagePath = printer.pdfToImage(PdfToImageParams(pdfPath = pdfFile.absolutePath))
                showToast("pdfToImage() -> $imagePath")
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "printPdf(pdfPath)  — full pipeline", activeAction = activeAction) {
            run("printPdf(pdfPath)  — full pipeline") {
                val pdfFile = generateTestPdf(cacheDir)
                printer.printPdf(PrintPdfParams(pdfPath = pdfFile.absolutePath))
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // htmlToPdf() and printHtml() are callback-based (WebView needs the main
        // thread), so they're managed directly here instead of through run().

        TestButton(label = "htmlToPdf(html)", activeAction = activeAction) {
            activeAction = "htmlToPdf(html)"
            status = "htmlToPdf(html)..."
            printer.htmlToPdf(HtmlToPdfParams(html = sampleTestHtml())) { path ->
                activeAction = null
                status = if (path != null) "htmlToPdf(html): OK" else "htmlToPdf(html) FAILED"
                showToast("htmlToPdf() -> ${path ?: "null"}")
            }
        }

        Spacer(Modifier.height(8.dp))

        // outputPath points straight at the public Downloads folder - no saveFile() call here.
        // htmlToPdf() should detect that itself (via MediaStore/FileSaver) and hand back the
        // real, already-saved content:// Uri, which openFile() should then be able to open.
        TestButton(label = "htmlToPdf(outputPath=DOWNLOADS) -> openFile", activeAction = activeAction) {
            activeAction = "htmlToPdf(outputPath=DOWNLOADS) -> openFile"
            status = "htmlToPdf(outputPath=DOWNLOADS)..."
            val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ).absolutePath
            printer.htmlToPdf(
                HtmlToPdfParams(
                    html = sampleTestHtml(),
                    outputPath = "$downloads/printerkit_direct_downloads_test.pdf"
                )
            ) { path ->
                activeAction = null
                if (path == null) {
                    status = "htmlToPdf(outputPath=DOWNLOADS) FAILED"
                    return@htmlToPdf
                }
                val opened = printer.openFile(path, "application/pdf")
                status = if (opened) "openFile() opened: $path" else "openFile($path) returned false"
            }
        }

        Spacer(Modifier.height(8.dp))

        TestButton(label = "printHtml(html)  — full pipeline", activeAction = activeAction) {
            activeAction = "printHtml(html)  — full pipeline"
            status = "printHtml(html)..."
            printer.printHtml(PrintHtmlParams(html = sampleTestHtml())) { ok ->
                activeAction = null
                status = if (ok) "printHtml(html): OK" else "printHtml(html) FAILED"
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showPickerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPickerSheet = false },
            sheetState = sheetState
        ) {
            PrinterPickerSheetContent(
                devices = devices,
                busy = activeAction != null,
                onDeviceSelected = { device ->
                    run(
                        label = "Connect to ${device.address}",
                        onSuccess = {
                            connected = device
                            showPickerSheet = false
                        }
                    ) {
                        val ok = printerService.connectAndKeepAlive(ConnectPrinterParams(device.address))
                        if (!ok) throw IllegalStateException("connectPrinter() returned false")
                    }
                },
                onOpenBluetoothSettings = {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            )
        }
    }
}

/**
 * A test-bench button that shows its own small spinner while its action is running
 * (`activeAction == label`) and disables every button while any action is running.
 */
@Composable
private fun TestButton(
    label: String,
    activeAction: String?,
    onClick: () -> Unit
) {
    val isLoading = activeAction == label
    Button(
        enabled = activeAction == null,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(label)
    }
}

/**
 * Bottom sheet content: paired printer list, or an empty state with a shortcut
 * to the OS Bluetooth settings when nothing has been paired yet.
 */
@Composable
private fun PrinterPickerSheetContent(
    devices: List<BluetoothPrinterDevice>,
    busy: Boolean,
    onDeviceSelected: (BluetoothPrinterDevice) -> Unit,
    onOpenBluetoothSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = "প্রিন্টার সিলেক্ট করুন",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))

        if (devices.isEmpty()) {
            Text("কোনো paired প্রিন্টার পাওয়া যায়নি। আগে Android Bluetooth settings থেকে প্রিন্টার pair করুন।")
            Spacer(Modifier.height(12.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenBluetoothSettings
            ) {
                Text("Bluetooth Settings খুলুন")
            }
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(devices) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable(enabled = !busy) { onDeviceSelected(device) }
                    ) {
                        Text(
                            text = "${device.name ?: "Unknown"}\n${device.address}",
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * A sample receipt-style HTML/CSS report (Bangla content), for testing
 * [PosPrinter.htmlToPdf] and [PosPrinter.printHtml].
 */
private fun sampleTestHtml(): String = """
<!DOCTYPE html>
<html lang="bn">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>PrinterKit Test</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Poppins:wght@400;500;600&display=swap" rel="stylesheet">
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
            font-weight: bold;
        }

        body {
            font-family: 'Poppins', sans-serif;
            background-color: #ffffff;
            display: flex;
            justify-content: center;
            align-items: center;
            min-height: 100vh;
        }

        .container {
            background: white;
            width: 100%;
            max-width: 512px;
            padding: 48px 0x;
            display: flex;
            flex-direction: column;
            gap: 14px;
            align-items: center;
        }

        .divider-dashed {
            border: none;
            border-top: 0.2px dashed #263238;
            width: 100%;
            margin: 0;
        }

        .title {
            font-family: "Poppins", sans-serif;
            font-weight: 500;
            font-size: 35px;
            line-height: 100%;
            letter-spacing: 0;
            color: #000;
            margin: 0;
            text-align: left;
        }

        .row {
            width: 100%;
            color: #263238;
            font-size: 29px;
            line-height: 30px;
        }

        .label {
            font-weight: 500;
        }

        .value {
            font-weight: 400;
        }

        .thank-you-text {
            font-size: 29px;
            line-height: 28px;
            font-weight: 600;
            color: #263238;
            text-align: center;
            padding: 4px 8px;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="title">PrinterKit টেস্ট প্রিন্ট</div>
        <hr class="divider-dashed">

        <div class="row"><span class="label">তারিখ:</span> <span class="value">১৬ সেপ্টেম্বর, ২০২৬</span></div>
        <div class="row"><span class="label">সময়:</span> <span class="value">১১:৪২ AM</span></div>
        <hr class="divider-dashed">

        <div class="row"><span class="label">নাম:</span> <span class="value">মোঃ রফিকুল ইসলাম</span></div>
        <div class="row"><span class="label">ঠিকানা:</span> <span class="value">ঢাকা, সাভার</span></div>
        <hr class="divider-dashed">

        <div class="thank-you-text">ধন্যবাদ</div>
    </div>
</body>
</html>
""".trimIndent()

/** Saves [bitmap] as a PNG file in [cacheDir], for testing [PosPrinter.printImageFile]. */
private fun saveBitmapToFile(bitmap: Bitmap, cacheDir: File): File {
    val file = File(cacheDir, "bt_printer_test_image.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}

/** Encodes [bitmap] as a base64 PNG string, for testing [PosPrinter.printImageBase64]. */
private fun bitmapToBase64(bitmap: Bitmap): String {
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
}

/** Builds a simple black-and-white test bitmap, including Bangla text, for [PosPrinter.printImageBitmap]. */
private fun generateTestBitmap(): Bitmap {
    val width = 384
    val height = 260
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 26f
    }
    val titlePaint = Paint(paint).apply {
        textSize = 32f
        isFakeBoldText = true
    }

    canvas.drawText("PrinterKit", 20f, 50f, titlePaint)
    canvas.drawText("বাংলা টেস্ট প্রিন্ট", 20f, 100f, paint)
    canvas.drawText("Bluetooth Printer Test", 20f, 140f, paint)
    canvas.drawText("Print Image OK", 20f, 180f, paint)
    canvas.drawLine(20f, 200f, (width - 20).toFloat(), 200f, paint)

    return bitmap
}

/** Builds a simple one-page PDF, including Bangla text, for [PosPrinter.printPdf]. */
private fun generateTestPdf(cacheDir: File): File {
    val document = PdfDocument()
    val pageInfo = PdfDocument.PageInfo.Builder(320, 500, 1).create()
    val page = document.startPage(pageInfo)
    val canvas = page.canvas
    canvas.drawColor(Color.WHITE)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 18f
    }
    val titlePaint = Paint(paint).apply {
        textSize = 22f
        isFakeBoldText = true
    }

    canvas.drawText("PrinterKit", 20f, 40f, titlePaint)
    canvas.drawText("PDF -> Image -> Print Test", 20f, 80f, paint)
    canvas.drawText("বাংলা টেক্সট পরীক্ষা", 20f, 120f, paint)
    canvas.drawLine(20f, 140f, 300f, 140f, paint)
    canvas.drawText("End of test PDF", 20f, 170f, paint)

    document.finishPage(page)

    val file = File(cacheDir, "bt_printer_test.pdf")
    FileOutputStream(file).use { document.writeTo(it) }
    document.close()
    return file
}
