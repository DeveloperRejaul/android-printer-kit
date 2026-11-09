package com.rezaul.printerkit

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import com.rezaul.printerkit.model.PrinterConfidence
import java.util.UUID

/**
 * Best-effort guess whether a paired Bluetooth device is a printer. Bluetooth gives no definite answer
 * (cheap POS printers often report a wrong class or none), so several weak signals are combined.
 */
internal object PrinterClassifier {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val CLASS_IMAGING = 0x0600
    private const val CLASS_IMAGING_PRINTER = 0x0680

    private val CONTAINS = listOf(
        "printer", "thermal", "receipt", "pos", "xprinter", "goojprt", "rongta", "epson",
        "bixolon", "zjiang", "sunmi", "citizen", "star micronics"
    )
    private val PREFIXES = listOf("xp-", "pt-", "mtp-", "mpt-", "rpp", "tm-", "tsp", "bt-printer")

    @SuppressLint("MissingPermission")
    fun classify(device: BluetoothDevice, extraKeywords: List<String>): Pair<PrinterConfidence, String> {
        if (device.type == BluetoothDevice.DEVICE_TYPE_LE) {
            return PrinterConfidence.UNKNOWN to "BLE only (not supported)"
        }
        val major = device.bluetoothClass?.majorDeviceClass ?: -1
        val deviceClass = device.bluetoothClass?.deviceClass ?: -1
        val classPrinter = deviceClass != -1 && (deviceClass and CLASS_IMAGING_PRINTER) == CLASS_IMAGING_PRINTER && major == CLASS_IMAGING
        val classImaging = major == CLASS_IMAGING

        val name = device.name?.lowercase().orEmpty()
        val nameMatch = name.isNotEmpty() && (
            CONTAINS.any { name.contains(it) } ||
                PREFIXES.any { name.startsWith(it) } ||
                extraKeywords.any { it.isNotBlank() && name.contains(it.lowercase()) }
            )
        val spp = device.uuids?.any { it.uuid == SPP_UUID } == true

        return when {
            classPrinter -> PrinterConfidence.CONFIDENT to "Bluetooth class: printer"
            nameMatch && spp -> PrinterConfidence.CONFIDENT to "Printer-like name + SPP service"
            nameMatch -> PrinterConfidence.LIKELY to "Printer-like name"
            classImaging -> PrinterConfidence.LIKELY to "Bluetooth class: imaging"
            else -> PrinterConfidence.UNKNOWN to (if (spp) "SPP service only" else "No printer signs")
        }
    }
}
