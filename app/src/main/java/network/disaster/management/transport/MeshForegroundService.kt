package network.disaster.management.transport

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import network.disaster.management.model.FeedMessage
import network.disaster.management.model.MessageType
import network.disaster.management.routing.TriPoolRouter
import network.disaster.management.storage.LowWearCryptoShredder
import network.disaster.management.system.OemBatteryManager
import network.disaster.management.transport.acoustic.FastFskModem
import java.util.UUID

class MeshForegroundService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var batteryManager: OemBatteryManager
    private lateinit var shredder: LowWearCryptoShredder
    private lateinit var router: TriPoolRouter
    private lateinit var bleSupervisor: BleMeshSupervisor
    private val acousticModem = FastFskModem()

    private val localRecipientTag = ByteArray(16).apply {
        for (i in indices) this[i] = (i + 1).toByte()
    }

    override fun onCreate() {
        super.onCreate()
        batteryManager = OemBatteryManager(this)
        shredder = LowWearCryptoShredder(this)
        router = TriPoolRouter(shredder, localRecipientTag)
        bleSupervisor = BleMeshSupervisor(this, shredder, router)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Disaster:CoreExecutionWakeLock").apply {
            acquire(24 * 3600 * 1000L)
        }

        val channel = NotificationChannel("disaster_daemon", "Disaster Mesh Node Daemon", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notif = NotificationCompat.Builder(this, "disaster_daemon")
            .setContentTitle("Civil Disaster Telemetry Service")
            .setContentText("Auxiliary mesh transceivers active (BLE Mesh & Ultrasonic Listener).")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        startForeground(1001, notif)

        // 1. Start autonomous BLE mesh discovery and epidemic sync
        bleSupervisor.startMesh()

        // 2. Start acoustic background ultrasonic listener
        acousticModem.startAcousticListener { distressBytes ->
            scope.launch(Dispatchers.IO) {
                val hexTag = distressBytes.joinToString("") { "%02X".format(it) }
                val msg = FeedMessage(
                    id = UUID.randomUUID().toString(),
                    groupId = "GROUP_PUBLIC_BROADCAST",
                    senderName = "ULTRASONIC-BEACON",
                    content = "ACOUSTIC DISTRESS SIGNAL DETECTED [Tag: $hexTag]",
                    type = MessageType.EMERGENCY,
                    timestamp = System.currentTimeMillis(),
                    isEmergency = true
                )
                shredder.insertMessage(msg)
            }
        }

        // 3. Heartbeat loop
        scope.launch {
            while (isActive) {
                batteryManager.scheduleHeartbeat()
                delay(60000L)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        bleSupervisor.stopMesh()
        acousticModem.stopAcousticListener()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(c: Context) = c.startForegroundService(Intent(c, MeshForegroundService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, MeshForegroundService::class.java))
    }
}
