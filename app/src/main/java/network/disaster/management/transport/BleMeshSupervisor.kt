package network.disaster.management.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.*
import network.disaster.management.routing.TimeConsensusManager
import network.disaster.management.routing.TriPoolRouter
import network.disaster.management.storage.LowWearCryptoShredder
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BleMeshSupervisor(
    private val context: Context,
    private val shredder: LowWearCryptoShredder,
    private val router: TriPoolRouter
) {
    companion object {
        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000FEAA-0000-1000-8000-00805F9B34FB")
        val RFCOMM_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val L2CAP_PSM = 4097
        const val MSG_INVENTORY_OFFER: Byte = 0x01
        const val MSG_INVENTORY_WANT: Byte = 0x02
        const val MSG_BUNDLE_PAYLOAD: Byte = 0x03
        const val MSG_BLOOM_SUMMARY: Byte = 0x04
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val transportSupervisor = ResilientTransportSupervisor()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var isRunning = false

    private val peerCooldownMap = ConcurrentHashMap<String, Long>()
    private var serverSocket: BluetoothServerSocket? = null

    @SuppressLint("MissingPermission")
    fun startMesh() {
        if (isRunning || bluetoothAdapter == null) return
        try {
            if (!bluetoothAdapter.isEnabled) return
            isRunning = true

            advertiser = try { bluetoothAdapter.bluetoothLeAdvertiser } catch (_: Throwable) { null }
            scanner = try { bluetoothAdapter.bluetoothLeScanner } catch (_: Throwable) { null }

            startAdvertising()
            startServerListener()
            startDutyCycledScanning()
        } catch (_: Throwable) {}
    }

    @SuppressLint("MissingPermission")
    fun stopMesh() {
        isRunning = false
        job.cancel()

        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Throwable) {}

        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Throwable) {}

        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        try {
            val adv = advertiser ?: return
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
                .build()

            adv.startAdvertising(settings, data, advertiseCallback)
        } catch (_: Throwable) {}
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {}
        override fun onStartFailure(errorCode: Int) {}
    }

    @SuppressLint("MissingPermission")
    private fun startDutyCycledScanning() {
        scope.launch {
            while (isActive && isRunning) {
                try {
                    val sc = scanner
                    if (sc != null && bluetoothAdapter?.isEnabled == true) {
                        val filter = ScanFilter.Builder()
                            .setServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
                            .build()
                        val settings = ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                            .build()

                        sc.startScan(listOf(filter), settings, scanCallback)
                        delay(6000L) // Scan for 6 seconds
                        try { sc.stopScan(scanCallback) } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
                delay(20000L) // Sleep for 20 seconds to preserve battery
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                handleDiscoveredPeer(device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDiscoveredPeer(device: BluetoothDevice) {
        val address = try { device.address } catch (_: Throwable) { null } ?: return
        val lastSeen = peerCooldownMap[address] ?: 0L
        if (System.currentTimeMillis() - lastSeen < 60000L) {
            return // Skip devices contacted within last 60 seconds
        }
        peerCooldownMap[address] = System.currentTimeMillis()

        scope.launch {
            val connection = transportSupervisor.connectWithFailover(device, L2CAP_PSM)
            if (connection != null) {
                val (_, socket) = connection
                try {
                    syncWithPeer(socket)
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startServerListener() {
        scope.launch {
            try {
                serverSocket = bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(
                    "DisasterMeshSync",
                    RFCOMM_UUID
                )
                while (isActive && isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    launch {
                        try {
                            syncWithPeer(socket)
                        } finally {
                            try { socket.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Executes Epidemic sync protocol using rolling 256-bit Bloom filters and
     * decentralized TimeConsensusManager clock synchronization over Bluetooth socket.
     */
    private suspend fun syncWithPeer(socket: BluetoothSocket) = withContext(Dispatchers.IO) {
        val input: InputStream = socket.inputStream
        val output: OutputStream = socket.outputStream

        val localBundles = shredder.getAllBundleIds(60)
        val bloom = BundleBloomFilter.fromBundleIds(localBundles)

        // 1. Send compact 49-byte Bloom summary + consensus time + Lamport clock
        val bloomBuf = ByteBuffer.allocate(1 + 8 + 8 + BundleBloomFilter.FILTER_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        bloomBuf.put(MSG_BLOOM_SUMMARY)
        bloomBuf.putLong(TimeConsensusManager.shared.getConsensusTimeMs())
        bloomBuf.putLong(TimeConsensusManager.shared.tickLamport())
        bloomBuf.put(bloom.filterBytes)
        output.write(bloomBuf.array())
        output.flush()

        // 2. Read peer response
        val opByte = input.read()
        if (opByte == -1) return@withContext

        when (opByte.toByte()) {
            MSG_BLOOM_SUMMARY -> {
                // Peer responded with their 256-bit Bloom filter & clock
                val timeBuf = ByteArray(8)
                val lamportBuf = ByteArray(8)
                val peerFilter = ByteArray(BundleBloomFilter.FILTER_SIZE_BYTES)

                if (!readExact(input, timeBuf) || !readExact(input, lamportBuf) || !readExact(input, peerFilter)) {
                    return@withContext
                }

                val peerTime = ByteBuffer.wrap(timeBuf).order(ByteOrder.BIG_ENDIAN).long
                val peerLamport = ByteBuffer.wrap(lamportBuf).order(ByteOrder.BIG_ENDIAN).long
                TimeConsensusManager.shared.recordPeerTimestamp(peerTime)
                TimeConsensusManager.shared.witnessLamport(peerLamport)

                val peerBloom = BundleBloomFilter(peerFilter)
                val toSend = localBundles.filter { !peerBloom.mightContain(it) }.take(15)

                for (id in toSend) {
                    val bundleBytes = shredder.retrieveBundle(id)
                    if (bundleBytes != null) {
                        val frame = if (bundleBytes.size == 4096) bundleBytes else bundleBytes.copyOf(4096)
                        output.write(MSG_BUNDLE_PAYLOAD.toInt())
                        output.write(frame)
                        output.flush()
                    }
                }
            }

            MSG_INVENTORY_OFFER -> {
                // Backward-compatibility: Peer sent raw bundle ID inventory
                val countBuf = ByteArray(4)
                if (!readExact(input, countBuf)) return@withContext
                val peerCount = ByteBuffer.wrap(countBuf).int.coerceIn(0, 100)

                val wantedList = mutableListOf<ByteArray>()
                for (i in 0 until peerCount) {
                    val bundleId = ByteArray(32)
                    if (!readExact(input, bundleId)) return@withContext
                    if (shredder.retrieveBundle(bundleId) == null) {
                        wantedList.add(bundleId)
                    }
                }

                // Send our WANT list
                val wantBuf = ByteBuffer.allocate(5 + wantedList.size * 32).order(ByteOrder.BIG_ENDIAN)
                wantBuf.put(MSG_INVENTORY_WANT)
                wantBuf.putInt(wantedList.size)
                for (id in wantedList) {
                    val id32 = if (id.size == 32) id else id.copyOf(32)
                    wantBuf.put(id32)
                }
                output.write(wantBuf.array())
                output.flush()

                // Receive wanted bundles
                for (i in 0 until wantedList.size) {
                    val payloadOp = input.read()
                    if (payloadOp != MSG_BUNDLE_PAYLOAD.toInt()) break
                    val frame = ByteArray(4096)
                    if (!readExact(input, frame)) break
                    router.processIngressPacket(frame)
                }
            }

            MSG_INVENTORY_WANT -> {
                // Peer requested specific bundles
                val countBuf = ByteArray(4)
                if (!readExact(input, countBuf)) return@withContext
                val wantCount = ByteBuffer.wrap(countBuf).int.coerceIn(0, 100)

                for (i in 0 until wantCount) {
                    val reqId = ByteArray(32)
                    if (!readExact(input, reqId)) return@withContext
                    val bundleBytes = shredder.retrieveBundle(reqId)
                    if (bundleBytes != null) {
                        val frame = if (bundleBytes.size == 4096) bundleBytes else bundleBytes.copyOf(4096)
                        output.write(MSG_BUNDLE_PAYLOAD.toInt())
                        output.write(frame)
                        output.flush()
                    }
                }
            }

            MSG_BUNDLE_PAYLOAD -> {
                val frame = ByteArray(4096)
                if (readExact(input, frame)) {
                    router.processIngressPacket(frame)
                }
            }
        }
    }

    private fun readExact(input: InputStream, buffer: ByteArray): Boolean {
        var total = 0
        while (total < buffer.size) {
            val count = input.read(buffer, total, buffer.size - total)
            if (count == -1) return false
            total += count
        }
        return true
    }
}
