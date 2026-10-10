package network.disaster.management.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class TransportCapabilityManager(private val context: Context) {

    fun isBleAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
            hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE) &&
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH) &&
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun isAcousticAvailable(): Boolean = hasPermission(Manifest.permission.RECORD_AUDIO)

    fun isOpticalAvailable(): Boolean = hasPermission(Manifest.permission.CAMERA)

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
