package com.rezaul.printerkit

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Runtime permission helpers for Bluetooth printer access. Only Android 12+ (API 31)
 * needs a runtime grant (BLUETOOTH_CONNECT) - older versions only need the manifest
 * `<uses-permission>` (already declared by this library) and are always considered granted.
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

    /** The runtime permission(s) this device's Android version needs (empty on API < 31). */
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }
    }

    /**
     * Shows the system permission dialog if needed - a no-op if already granted or not
     * required on this Android version. Forward your Activity's
     * `onRequestPermissionsResult` to [isGrantResult] to get the outcome.
     */
    fun request(activity: Activity, requestCode: Int = REQUEST_CODE) {
        val required = getRequiredPermissions()
        if (required.isEmpty() || isGranted(activity)) return
        ActivityCompat.requestPermissions(activity, required, requestCode)
    }

    /**
     * Call from your Activity's `onRequestPermissionsResult` with the same arguments -
     * returns true if [requestCode] matches [expectedRequestCode] and the permission was granted.
     */
    fun isGrantResult(
        requestCode: Int,
        grantResults: IntArray,
        expectedRequestCode: Int = REQUEST_CODE
    ): Boolean {
        return requestCode == expectedRequestCode &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
    }
}
