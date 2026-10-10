package network.disaster.management.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

class OemBatteryManager(private val context: Context) {

    fun scheduleHeartbeat() {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, network.disaster.management.transport.MeshForegroundService::class.java).apply {
                action = "ACTION_MESH_HEARTBEAT_WAKE"
            }
            val pi = PendingIntent.getService(
                context, 999, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val trigger = SystemClock.elapsedRealtime() + (10 * 60 * 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
        } catch (_: Exception) {}
    }

    fun openVendorPowerWhitelists(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        val i = Intent().apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        when {
            m.contains("xiaomi") -> i.component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            m.contains("samsung") -> i.component = ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
            m.contains("huawei") -> i.component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            else -> return false
        }
        return try { context.startActivity(i); true } catch (_: Exception) { false }
    }

    /**
     * Retrieves current battery percentage (0 to 100).
     */
    fun getBatteryPercentage(): Int {
        return try {
            val ifilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, ifilter)
            val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                (level * 100) / scale
            } else 100
        } catch (_: Exception) {
            100
        }
    }

    /**
     * Checks if device is in low battery state (< 15%) or Power Save mode.
     */
    fun isLowBattery(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val isPowerSave = pm?.isPowerSaveMode == true
        val percentage = getBatteryPercentage()
        return isPowerSave || percentage < 15
    }

    /**
     * Dynamically scales client Proof-of-Work difficulty based on battery level & message urgency:
     * - Emergency distress / priority alerts: 2 bits (instant propagation)
     * - Critical low battery (< 15%): 2 bits (preserves dying handset battery)
     * - Normal operation: 4 bits (mitigates flood spam)
     */
    fun getOptimalPoWDifficulty(isEmergency: Boolean = false): Int {
        if (isEmergency) return 2
        return if (isLowBattery()) 2 else 4
    }
}
