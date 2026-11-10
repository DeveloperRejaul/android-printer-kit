package com.rezaul.printerkit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.rezaul.printerkit.model.ConnectPrinterParams
import com.rezaul.printerkit.model.PrinterConnectionType

/**
 * Foreground service that owns the [PosPrinter] connection so it survives the
 * app being swiped away from Recents. A [PosPrinter] held directly by an
 * Activity dies with the app process the moment the task is removed; a *foreground*
 * service's process is kept alive by the system as long as it's showing its
 * notification, so binding the connection to this service instead keeps the printer
 * connected in the background.
 *
 * Usage: bind to this service (see [LocalBinder]), then call [connectAndKeepAlive]
 * instead of [PosPrinter.connectPrinter] directly - everything else
 * ([PosPrinter.printText], [PosPrinter.printHtml], etc.) still runs
 * through [printer] as before. Call [disconnect] to drop both the connection and
 * the foreground/notification state.
 */
class PrinterService : Service() {

    private val binder = LocalBinder()
    val printer: PosPrinter by lazy { PosPrinter(applicationContext) }

    /**
     * Title of the foreground notification. Set it via [ConnectPrinterParams.notificationTitle]
     * or assign it directly (e.g. right after binding, to also cover auto-reconnect on startup).
     */
    var notificationTitle: String = ConnectPrinterParams.DEFAULT_NOTIFICATION_TITLE

    /** Title of the notification shown when the printer disconnects on its own (see [ConnectPrinterParams.disconnectedNotificationTitle]). */
    var disconnectedNotificationTitle: String = ConnectPrinterParams.DEFAULT_DISCONNECTED_TITLE

    /**
     * Connection type of the current (or most recently connected) printer, captured right after a
     * successful connect - [PosPrinter.getConnectedPrinterType] already goes back to null by the time
     * [showDisconnected] runs, since the connection is torn down before [PosPrinter.onConnectionLost]
     * fires. Drives which icon [buildNotification]/[showDisconnected] use.
     */
    private var lastConnectionType: PrinterConnectionType = PrinterConnectionType.BLUETOOTH

    private val watchdogHandler = Handler(Looper.getMainLooper())

    // Some OEM notification shades (observed on Samsung One UI) let a determined/forceful swipe
    // dismiss a notification despite FLAG_ONGOING_EVENT/FLAG_NO_CLEAR, even though the foreground
    // service itself keeps running underneath - the system doesn't restore it on its own. This
    // watchdog polls our own notification (no special permission needed - NotificationManager can
    // always see this app's own active notifications) and re-posts it if it's missing while still
    // connected, so "connected" stays effectively non-removable and only ever goes away via a real
    // disconnect (see [showDisconnected]).
    private val notificationWatchdog = object : Runnable {
        override fun run() {
            if (printer.isConnectedPrinter() && !isNotificationActive(NOTIFICATION_ID)) {
                startForeground(NOTIFICATION_ID, buildNotification(printer.getConnectedPrinter()?.name))
            }
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun isNotificationActive(id: Int): Boolean {
        // getActiveNotifications() needs API 23; below that, trust the OS's own non-dismissal
        // guarantees and skip the check (nothing to restore it with anyway below M).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == id }
    }

    inner class LocalBinder : Binder() {
        fun getService(): PrinterService = this@PrinterService
    }

    override fun onCreate() {
        super.onCreate()
        // The printer dropped (switched off, out of range, Bluetooth turned off): the "connected"
        // notification would now be a lie, so replace it with a dismissible "disconnected" one.
        printer.onConnectionLost = { name -> showDisconnected(name) }
        watchdogHandler.postDelayed(notificationWatchdog, WATCHDOG_INTERVAL_MS)
        // Runs once when this service process is first created (e.g. the app was
        // fully killed and just reopened) - reconnects to whichever printer was last
        // connected, if any, so the user doesn't have to re-pick it every time.
        // connectPrinter() does blocking I/O, so this can't run on the main thread.
        Thread {
            if (printer.autoConnectIfAvailable()) {
                val name = printer.getConnectedPrinter()?.name
                lastConnectionType = printer.getConnectedPrinterType() ?: PrinterConnectionType.BLUETOOTH
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    cancelDisconnectedNotification()
                    startForeground(NOTIFICATION_ID, buildNotification(name))
                }
            }
        }.start()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        watchdogHandler.removeCallbacks(notificationWatchdog)
        super.onDestroy()
    }

    /** Connects [params]'s address and, on success, promotes this service to the foreground so the connection outlives the app's task. */
    fun connectAndKeepAlive(params: ConnectPrinterParams): Boolean {
        notificationTitle = params.notificationTitle
        disconnectedNotificationTitle = params.disconnectedNotificationTitle
        val ok = printer.connectPrinter(params)
        if (ok) {
            lastConnectionType = printer.getConnectedPrinterType() ?: params.type
            cancelDisconnectedNotification()
            startForeground(NOTIFICATION_ID, buildNotification(printer.getConnectedPrinter()?.name))
        }
        return ok
    }

    /** Disconnects the printer and stops the foreground service (removing the notification). */
    fun disconnect() {
        printer.disconnectPrinter()
        stopForeground(STOP_FOREGROUND_REMOVE)
        cancelDisconnectedNotification()
        stopSelf()
    }

    /** Removes the "connected" notification and posts a normal, dismissible "disconnected" one. */
    private fun showDisconnected(printerName: String?) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val manager = getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(disconnectedNotificationTitle)
            .setContentText(printerName ?: "Printer")
            .setSmallIcon(iconFor(lastConnectionType))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            manager.notify(DISCONNECTED_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted: nothing to show, the foreground one is already gone.
        }
        stopSelf()
    }

    private fun cancelDisconnectedNotification() {
        getSystemService(NotificationManager::class.java).cancel(DISCONNECTED_NOTIFICATION_ID)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Printer connection", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(printerName: String?): Notification {
        ensureChannel(getSystemService(NotificationManager::class.java))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(printerName ?: "Printer")
            .setSmallIcon(iconFor(lastConnectionType))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Status-bar icon matching [type] - Android has no public USB/network equivalent of [android.R.drawable.stat_sys_data_bluetooth]. */
    private fun iconFor(type: PrinterConnectionType): Int = when (type) {
        PrinterConnectionType.BLUETOOTH -> android.R.drawable.stat_sys_data_bluetooth
        PrinterConnectionType.USB -> R.drawable.printerkit_ic_usb
        PrinterConnectionType.NETWORK -> R.drawable.printerkit_ic_network
    }

    companion object {
        private const val CHANNEL_ID = "bluetooth_printer_connection"
        private const val NOTIFICATION_ID = 1001
        private const val DISCONNECTED_NOTIFICATION_ID = 1002
        private const val WATCHDOG_INTERVAL_MS = 2000L
    }
}
