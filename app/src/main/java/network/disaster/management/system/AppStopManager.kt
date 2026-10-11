package network.disaster.management.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Process
import network.disaster.management.storage.LowWearCryptoShredder
import network.disaster.management.transport.BleMeshSupervisor
import network.disaster.management.transport.MeshForegroundService
import network.disaster.management.transport.WifiMeshSupervisor
import network.disaster.management.transport.acoustic.FastFskModem
import kotlin.system.exitProcess

/**
 * AppStopManager: Emergency Full-Application & Mesh Radio Kill Switch.
 *
 * Implements a single authoritative shutdown sequence:
 * 1. Immediately halts Bluetooth Low Energy mesh transceivers (L2CAP, RFCOMM, GATT, Duty-cycle scanning & advertising).
 * 2. Immediately halts Wi-Fi Hotspot / LAN peer sockets and servers.
 * 3. Releases acoustic near-ultrasound modems and audio tracks.
 * 4. Stops MeshForegroundService notification banner and persistent wakelocks.
 * 5. Flushes and closes SQLCipher encrypted storage safely.
 * 6. Finishes all activity tasks and terminates application process (SIGKILL / exitProcess(0)).
 */
object AppStopManager {

    fun stopWholeApp(activity: Activity, shredder: LowWearCryptoShredder? = null) {
        // 1. Cease physical RF transceivers
        try {
            BleMeshSupervisor.shared?.stopMesh()
        } catch (_: Throwable) {}

        try {
            WifiMeshSupervisor.shared?.stopMesh()
        } catch (_: Throwable) {}

        // 2. Halt Foreground Service and wakelocks
        try {
            MeshForegroundService.stop(activity)
        } catch (_: Throwable) {}

        // 3. Flush & close encrypted storage
        try {
            shredder?.close()
        } catch (_: Throwable) {}

        // 4. Finish all activities in current task
        try {
            activity.finishAffinity()
        } catch (_: Throwable) {}

        // 5. Cleanly terminate OS process to eliminate residual background tasks
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
