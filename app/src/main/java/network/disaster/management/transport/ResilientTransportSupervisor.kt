package network.disaster.management.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.media.AudioRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class ResilientTransportSupervisor {
    companion object {
        val RFCOMM_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /**
         * Dispatches an invariant 4,096-byte packet concurrently across ALL active physical mediums:
         * Wi-Fi Mesh (LAN/Hotspot) and Bluetooth Mesh (L2CAP CoC / RFCOMM).
         */
        fun broadcastEverywhere(packet: ByteArray) {
            // 1. Wi-Fi Hotspot / LAN Mesh
            try {
                WifiMeshSupervisor.shared?.broadcastPacket(packet)
            } catch (_: Throwable) {}

            // 2. Wi-Fi Direct (P2P zero-network) Mesh
            try {
                WifiDirectMeshSupervisor.shared?.broadcastPacket(packet)
            } catch (_: Throwable) {}

            // 3. Bluetooth Mesh (L2CAP CoC / RFCOMM / GATT)
            try {
                BleMeshSupervisor.shared?.broadcastPacket(packet)
            } catch (_: Throwable) {}
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    suspend fun connectWithFailover(context: android.content.Context, device: BluetoothDevice, psm: Int): Pair<String, BluetoothSocket>? = withContext(Dispatchers.IO) {
        val hasBtConnect = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (!hasBtConnect) {
            return@withContext null
        }

        // 1. Attempt L2CAP Connection-Oriented Channel if dynamic PSM is provided (Android 10+)
        if (psm > 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            var l2cap: BluetoothSocket? = null
            try {
                l2cap = device.createInsecureL2capChannel(psm)
                l2cap.connect()
                return@withContext Pair("L2CAP", l2cap)
            } catch (_: Exception) {
                try { l2cap?.close() } catch (_: Exception) {}
            }
        }

        // 2. Fallback to RFCOMM socket
        var rfcomm: BluetoothSocket? = null
        try {
            rfcomm = device.createInsecureRfcommSocketToServiceRecord(RFCOMM_UUID)
            rfcomm.connect()
            Pair("RFCOMM", rfcomm)
        } catch (_: Exception) {
            try { rfcomm?.close() } catch (_: Exception) {}
            null
        }
    }

    suspend fun transmitPacket(socket: BluetoothSocket, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val out: OutputStream = socket.outputStream
            out.write(data)
            out.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun receiveExact(socket: BluetoothSocket, buffer: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val input: InputStream = socket.inputStream
            var readTotal = 0
            while (readTotal < buffer.size) {
                val count = input.read(buffer, readTotal, buffer.size - readTotal)
                if (count == -1) return@withContext false
                readTotal += count
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun executeAcousticSafe(recorder: AudioRecord, fallbackAction: () -> Unit) {
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                fallbackAction()
                return
            }
            recorder.startRecording()
        } catch (_: Exception) {
            fallbackAction()
        }
    }
}
