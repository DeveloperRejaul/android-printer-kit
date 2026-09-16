package com.rezaul.printerkit

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Runtime permission helpers for Bluetooth printer access.
 *
 * Two independent runtime grants are involved, each gating a different thing:
 * - `BLUETOOTH_CONNECT` (Android 12+/API 31) - required to connect to or print on a
 *   paired device at all. [isGranted] checks specifically this one, since it's what
 *   actually gates [BluetoothPrinter]'s core operations.
 * - `POST_NOTIFICATIONS` (Android 13+/API 33) - required for [BluetoothPrinterService]'s
 *   "printer connected" notification to actually be *visible*. Without it, the
 *   foreground service still runs and the connection still survives the app being
 *   swiped from Recents (that protection doesn't depend on the notification being
 *   shown) - the user just won't see the ongoing notification.
 *
 * [getRequiredPermissions] bundles both (whichever apply on this Android version) so
 * a single [request] call sets up the full experience; [isGranted] deliberately stays
 * narrower since the printer still works without `POST_NOTIFICATIONS`.
 *
 * Uses the classic [ActivityCompat.requestPermissions]/`onRequestPermissionsResult` flow
 * rather than `ActivityResultContracts`, since that requires registering a launcher
 * before the Activity starts - this can instead be called on demand from anywhere
 * holding an Activity reference, including from inside this library.
 */
object BluetoothPermissions {

    /** Default request code for [request] - pass it through to [isGrantResult] to match results. */
    const val REQUEST_CODE = 4201

    /** True if the app can already talk to paired Bluetooth devices (connect/print). */
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * The runtime permission(s) this device's Android version needs for the full
     * experience (connecting/printing, plus a visible "printer connected"
     * notification) - empty on API < 31.
     */
    fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    /** True if every permission [getRequiredPermissions] lists is already granted. */
    private fun allRequiredGranted(context: Context): Boolean {
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Shows the system permission dialog(s) if needed - a no-op if everything in
     * [getRequiredPermissions] is already granted or none is required on this Android
     * version. Forward your Activity's `onRequestPermissionsResult` to [isGrantResult]
     * to get the outcome.
     */
    fun request(activity: Activity, requestCode: Int = REQUEST_CODE) {
        val required = getRequiredPermissions()
        if (required.isEmpty() || allRequiredGranted(activity)) return
        ActivityCompat.requestPermissions(activity, required, requestCode)
    }

    /**
     * Call from your Activity's `onRequestPermissionsResult` with the same three
     * arguments it received - returns true if [requestCode] matches [expectedRequestCode]
     * and the app can now use the printer's core features.
     *
     * Only `BLUETOOTH_CONNECT`'s result actually matters here: if it was part of this
     * request, its grant decides the result (whatever happened with `POST_NOTIFICATIONS`
     * is ignored - connect/print work without it, see the class doc). If
     * `BLUETOOTH_CONNECT` wasn't requested at all (e.g. only `POST_NOTIFICATIONS` was,
     * or this device is below API 31 where it's not needed), every requested permission
     * must be granted instead.
     */
    fun isGrantResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        expectedRequestCode: Int = REQUEST_CODE
    ): Boolean {
        if (requestCode != expectedRequestCode || grantResults.isEmpty()) return false
        val connectIndex = permissions.indexOf(Manifest.permission.BLUETOOTH_CONNECT)
        return if (connectIndex >= 0) {
            grantResults[connectIndex] == PackageManager.PERMISSION_GRANTED
        } else {
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        }
    }
}
