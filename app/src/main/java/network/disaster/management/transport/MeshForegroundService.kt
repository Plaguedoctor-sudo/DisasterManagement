package network.disaster.management.transport

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
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
    private var apkServer: ApkPropagationServer? = null
    private lateinit var batteryManager: OemBatteryManager
    private lateinit var shredder: LowWearCryptoShredder
    private lateinit var router: TriPoolRouter
    private lateinit var bleSupervisor: BleMeshSupervisor
    private lateinit var wifiSupervisor: WifiMeshSupervisor
    private lateinit var p2pSupervisor: WifiDirectMeshSupervisor
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
        wifiSupervisor = WifiMeshSupervisor(this, shredder, router)
        p2pSupervisor = WifiDirectMeshSupervisor(this, shredder, router)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Disaster:CoreExecutionWakeLock").apply {
            acquire(24 * 3600 * 1000L)
        }

        apkServer = ApkPropagationServer(this).also {
            ApkPropagationServer.shared = it
            it.start()
        }

        val channel = NotificationChannel("disaster_daemon", "Disaster Mesh Node Daemon", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notif = NotificationCompat.Builder(this, "disaster_daemon")
            .setContentTitle("Civil Disaster Telemetry Service")
            .setContentText("Mesh transceivers active (Wi-Fi Direct, Hotspot, BLE & Ultrasonic).")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                startForeground(1001, notif, fgsType)
            } else {
                startForeground(1001, notif)
            }
        } catch (_: Throwable) {
            try {
                startForeground(1001, notif)
            } catch (_: Throwable) {}
        }

        startAllTransceivers()

        // Acoustic background ultrasonic listener
        try {
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
        } catch (_: Throwable) {}

        // Heartbeat loop
        scope.launch {
            while (isActive) {
                batteryManager.scheduleHeartbeat()
                delay(60000L)
            }
        }
    }

    private fun startAllTransceivers() {
        // 1. Wi-Fi Hotspot / LAN mesh sync
        try {
            wifiSupervisor.startMesh()
        } catch (_: Throwable) {}

        // 2. Wi-Fi Direct (P2P zero-network) mesh
        try {
            p2pSupervisor.startMesh()
        } catch (_: Throwable) {}

        // 3. Autonomous BLE mesh discovery and epidemic sync
        try {
            bleSupervisor.startMesh()
        } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAllTransceivers()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        wifiSupervisor.stopMesh()
        p2pSupervisor.stopMesh()
        bleSupervisor.stopMesh()
        acousticModem.stopAcousticListener()
        apkServer?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(c: Context) {
            try {
                c.startForegroundService(Intent(c, MeshForegroundService::class.java))
            } catch (_: Throwable) {
                try {
                    c.startService(Intent(c, MeshForegroundService::class.java))
                } catch (_: Throwable) {}
            }
        }
        fun stop(c: Context) = c.stopService(Intent(c, MeshForegroundService::class.java))
    }
}
