package com.rezaul.printerkit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that owns the [BluetoothPrinter] connection so it survives the
 * app being swiped away from Recents. A [BluetoothPrinter] held directly by an
 * Activity dies with the app process the moment the task is removed; a *foreground*
 * service's process is kept alive by the system as long as it's showing its
 * notification, so binding the connection to this service instead keeps the printer
 * connected in the background.
 *
 * Usage: bind to this service (see [LocalBinder]), then call [connectAndKeepAlive]
 * instead of [BluetoothPrinter.connectPrinter] directly - everything else
 * ([BluetoothPrinter.printText], [BluetoothPrinter.printHtml], etc.) still runs
 * through [printer] as before. Call [disconnect] to drop both the connection and
 * the foreground/notification state.
 */
class BluetoothPrinterService : Service() {

    private val binder = LocalBinder()
    val printer: BluetoothPrinter by lazy { BluetoothPrinter(applicationContext) }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothPrinterService = this@BluetoothPrinterService
    }

    override fun onCreate() {
        super.onCreate()
        // Runs once when this service process is first created (e.g. the app was
        // fully killed and just reopened) - reconnects to whichever printer was last
        // connected, if any, so the user doesn't have to re-pick it every time.
        // connectPrinter() does blocking I/O, so this can't run on the main thread.
        Thread {
            if (printer.autoConnectIfAvailable()) {
                val name = printer.getConnectedPrinter()?.name
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    startForeground(NOTIFICATION_ID, buildNotification(name))
                }
            }
        }.start()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Connects [address] and, on success, promotes this service to the foreground so the connection outlives the app's task. */
    fun connectAndKeepAlive(address: String): Boolean {
        val ok = printer.connectPrinter(address)
        if (ok) {
            startForeground(NOTIFICATION_ID, buildNotification(printer.getConnectedPrinter()?.name))
        }
        return ok
    }

    /** Disconnects the printer and stops the foreground service (removing the notification). */
    fun disconnect() {
        printer.disconnectPrinter()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(printerName: String?): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "Printer connection", NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("প্রিন্টার সংযুক্ত")
            .setContentText(printerName ?: "Bluetooth printer")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "bluetooth_printer_connection"
        private const val NOTIFICATION_ID = 1001
    }
}
