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
    }

    suspend fun connectWithFailover(device: BluetoothDevice, psm: Int): Pair<String, BluetoothSocket>? = withContext(Dispatchers.IO) {
        var l2cap: BluetoothSocket? = null
        try {
            l2cap = device.createInsecureL2capChannel(psm)
            l2cap.connect()
            return@withContext Pair("L2CAP", l2cap)
        } catch (_: Exception) {
            try { l2cap?.close() } catch (_: Exception) {}
        }

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
