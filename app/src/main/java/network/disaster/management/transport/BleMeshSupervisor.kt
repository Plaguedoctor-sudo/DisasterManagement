package network.disaster.management.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import network.disaster.management.routing.TimeConsensusManager
import network.disaster.management.routing.TriPoolRouter
import network.disaster.management.storage.LowWearCryptoShredder
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BleMeshSupervisor(
    private val context: Context,
    private val shredder: LowWearCryptoShredder,
    private val router: TriPoolRouter
) {
    companion object {
        private const val TAG = "BleMeshSupervisor"

        @Volatile
        var shared: BleMeshSupervisor? = null

        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000FEAA-0000-1000-8000-00805F9B34FB")
        val CHAR_DATA_UUID: UUID = UUID.fromString("0000FEAB-0000-1000-8000-00805F9B34FB")
        val RFCOMM_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        const val MSG_INVENTORY_OFFER: Byte = 0x01
        const val MSG_INVENTORY_WANT: Byte = 0x02
        const val MSG_BUNDLE_PAYLOAD: Byte = 0x03
        const val MSG_BLOOM_SUMMARY: Byte = 0x04

        const val GATT_MAGIC: Short = 0xAA55.toShort()
        const val GATT_CHUNK_HEADER_SIZE = 11
        const val OP_GATT_BUNDLE: Byte = 0x01
        const val OP_GATT_BLOOM: Byte = 0x02
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val transportSupervisor = ResilientTransportSupervisor()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var isRunning = false
    var isStealthMode: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    fun setStealthMode(enabled: Boolean) {
        isStealthMode = enabled
        if (enabled) {
            try {
                advertiser?.stopAdvertising(advertiseCallback)
                Log.i(TAG, "RF RADIO SILENCE ENGAGED: BLE advertising halted. 0m RF footprint.")
            } catch (_: Throwable) {}
        } else {
            if (isRunning) {
                startAdvertising()
                Log.i(TAG, "ACTIVE MESH RESTORED: BLE advertising resumed.")
            }
        }
    }

    private val peerCooldownMap = ConcurrentHashMap<String, Long>()
    private val activeBleSockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val activeGattClients = ConcurrentHashMap<String, Pair<BluetoothGatt, Int>>() // Address -> (Gatt, MTU)
    private val rateLimiter = MeshRateLimiter(peerLimitPerSec = 20, channelLimitPerSec = 60)

    private var gattServer: BluetoothGattServer? = null
    private var rfcommServerSocket: BluetoothServerSocket? = null
    private var l2capServerSocket: BluetoothServerSocket? = null
    private var assignedL2capPsm: Int = 0

    private var scanJob: Job? = null
    private val gattSeqCounter = AtomicInteger(1)

    // Reassembly buffer for incoming GATT chunks: key = "${address}_${seqId}"
    private class GattAssembly(
        val seqId: Short,
        val opcode: Byte,
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = ConcurrentHashMap()
    )
    private val reassemblyMap = ConcurrentHashMap<String, GattAssembly>()

    private val _blePeerCount = MutableStateFlow(0)
    val blePeerCount: StateFlow<Int> = _blePeerCount.asStateFlow()

    fun getActivePeerCount(): Int {
        val uniquePeers = mutableSetOf<String>()
        uniquePeers.addAll(activeBleSockets.keys)
        uniquePeers.addAll(activeGattClients.keys)
        return uniquePeers.size
    }

    private fun updatePeerCount() {
        _blePeerCount.value = getActivePeerCount()
    }

    @SuppressLint("MissingPermission")
    fun startMesh() {
        if (isRunning || bluetoothAdapter == null) return
        try {
            if (!bluetoothAdapter.isEnabled) return
            isRunning = true
            shared = this

            advertiser = try { bluetoothAdapter.bluetoothLeAdvertiser } catch (_: Throwable) { null }
            scanner = try { bluetoothAdapter.bluetoothLeScanner } catch (_: Throwable) { null }

            startGattServer()
            startServerListeners()
            startAdvertising()
            startDutyCycledScanning()
            Log.i(TAG, "Disaster BLE Mesh started (GATT + L2CAP + RFCOMM)")
        } catch (e: Throwable) {
            Log.e(TAG, "Error starting BLE mesh", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopMesh() {
        isRunning = false
        if (shared === this) {
            shared = null
        }
        job.cancel()

        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Throwable) {}

        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Throwable) {}

        try {
            gattServer?.close()
        } catch (_: Throwable) {}

        try {
            rfcommServerSocket?.close()
        } catch (_: Throwable) {}

        try {
            l2capServerSocket?.close()
        } catch (_: Throwable) {}

        for ((_, sock) in activeBleSockets) {
            try { sock.close() } catch (_: Exception) {}
        }
        activeBleSockets.clear()

        for ((_, gattPair) in activeGattClients) {
            try { gattPair.first.close() } catch (_: Exception) {}
        }
        activeGattClients.clear()
        updatePeerCount()
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        try {
            val server = bluetoothManager?.openGattServer(context, gattServerCallback) ?: return
            val service = BluetoothGattService(MESH_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            val charData = BluetoothGattCharacteristic(
                CHAR_DATA_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                        BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
            )

            service.addCharacteristic(charData)
            server.addService(service)
            gattServer = server
            Log.i(TAG, "GATT Server registered with service $MESH_SERVICE_UUID and characteristic $CHAR_DATA_UUID")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start GATT Server: ${e.message}")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address ?: "UNKNOWN"
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT Server: Inbound client connected from $addr")
                updatePeerCount()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT Server: Inbound client disconnected from $addr")
                updatePeerCount()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                } catch (_: Exception) {}
            }
            value?.let { chunkData ->
                handleIncomingGattChunk(device, chunkData)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startServerListeners() {
        // 1. Android 10+ (API 29+) L2CAP Connection-Oriented Channels (CoC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val server = bluetoothAdapter?.listenUsingInsecureL2capChannel()
                l2capServerSocket = server
                assignedL2capPsm = server?.psm ?: 0
                Log.i(TAG, "L2CAP CoC Server listening on dynamic PSM: $assignedL2capPsm")

                scope.launch {
                    while (isActive && isRunning) {
                        val socket = try {
                            server?.accept() ?: break
                        } catch (_: Exception) {
                            break
                        }
                        val remoteAddress = try {
                            socket.remoteDevice?.address ?: "L2CAP_CLIENT_${System.currentTimeMillis()}"
                        } catch (_: Exception) {
                            "L2CAP_CLIENT_${System.currentTimeMillis()}"
                        }
                        launch {
                            handleConnectedSocket(socket, remoteAddress)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start L2CAP CoC listener", e)
            }
        }

        // 2. Bluetooth Classic RFCOMM fallback (for bonded devices)
        try {
            val server = bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(
                "DisasterMeshSync",
                RFCOMM_UUID
            )
            rfcommServerSocket = server
            scope.launch {
                while (isActive && isRunning) {
                    val socket = try {
                        server?.accept() ?: break
                    } catch (_: Exception) {
                        break
                    }
                    val remoteAddress = try {
                        socket.remoteDevice?.address ?: "RFCOMM_CLIENT_${System.currentTimeMillis()}"
                    } catch (_: Exception) {
                        "RFCOMM_CLIENT_${System.currentTimeMillis()}"
                    }
                    launch {
                        handleConnectedSocket(socket, remoteAddress)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start RFCOMM listener", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        try {
            val adv = advertiser ?: return
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()

            // Primary advertisement data (Service UUID)
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
                .build()

            // Scan response carries dynamic L2CAP PSM if available
            val scanResponseBuilder = AdvertiseData.Builder()
                .setIncludeDeviceName(false)

            if (assignedL2capPsm > 0) {
                val psmBytes = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(assignedL2capPsm.toShort()).array()
                scanResponseBuilder.addServiceData(ParcelUuid(MESH_SERVICE_UUID), psmBytes)
            }

            adv.startAdvertising(settings, data, scanResponseBuilder.build(), advertiseCallback)
            Log.i(TAG, "BLE advertising started with PSM $assignedL2capPsm")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to start advertising: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE Advertisement active")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE Advertisement start failed: errorCode=$errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDutyCycledScanning() {
        scanJob?.cancel()
        scanJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    val sc = scanner
                    if (sc != null && bluetoothAdapter?.isEnabled == true) {
                        val filter = ScanFilter.Builder()
                            .setServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
                            .build()
                        val settings = ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                            .setReportDelay(0)
                            .build()

                        sc.startScan(listOf(filter), settings, scanCallback)
                        delay(8000L) // 8s scan
                        try { sc.stopScan(scanCallback) } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
                delay(3000L) // 3s rest
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun triggerImmediateScanAndSync() {
        scope.launch {
            try {
                val sc = scanner ?: return@launch
                if (bluetoothAdapter?.isEnabled == true) {
                    val filter = ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
                        .build()
                    val settings = ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setReportDelay(0)
                        .build()

                    sc.startScan(listOf(filter), settings, scanCallback)
                    delay(5000L)
                    try { sc.stopScan(scanCallback) } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val scanRecord = result.scanRecord
            val psmData = scanRecord?.getServiceData(ParcelUuid(MESH_SERVICE_UUID))
            val peerPsm = if (psmData != null && psmData.size >= 2) {
                ByteBuffer.wrap(psmData).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            } else {
                0
            }
            handleDiscoveredPeer(device, peerPsm)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDiscoveredPeer(device: BluetoothDevice, peerPsm: Int) {
        val address = try { device.address } catch (_: Throwable) { null } ?: return
        if (activeBleSockets.containsKey(address) || activeGattClients.containsKey(address)) {
            return // Already connected
        }

        val lastSeen = peerCooldownMap[address] ?: 0L
        if (System.currentTimeMillis() - lastSeen < 6000L) {
            return
        }
        peerCooldownMap[address] = System.currentTimeMillis()

        scope.launch {
            // Priority 1: If peer advertises an explicit L2CAP PSM, try L2CAP CoC
            if (peerPsm > 0) {
                val connection = transportSupervisor.connectWithFailover(context, device, peerPsm)
                if (connection != null) {
                    val (_, socket) = connection
                    handleConnectedSocket(socket, address)
                    return@launch
                }
            }

            // Priority 2: Universal Zero-Pairing BLE GATT Client connection
            connectGattToPeer(device, address)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGattToPeer(device: BluetoothDevice, address: String) {
        try {
            Log.i(TAG, "Connecting GATT client to peer $address...")
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i(TAG, "GATT Client connected to $address, requesting MTU 512")
                        gatt.requestMtu(512)
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.i(TAG, "GATT Client disconnected from $address")
                        activeGattClients.remove(address)
                        updatePeerCount()
                        try { gatt.close() } catch (_: Exception) {}
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    val negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                    Log.i(TAG, "GATT MTU established: $negotiatedMtu for $address")
                    activeGattClients[address] = Pair(gatt, negotiatedMtu)
                    updatePeerCount()
                    gatt.discoverServices()
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "GATT Services discovered on $address! Triggering Bloom epidemic sync")
                        scope.launch {
                            sendBloomSummaryGatt(gatt)
                        }
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to connect GATT to $address", e)
        }
    }

    /**
     * Slices an arbitrary payload (4KB packet or Bloom summary) into GATT chunks and transmits
     * them over the GATT connection with pacing.
     */
    @SuppressLint("MissingPermission")
    private suspend fun sendGattData(gatt: BluetoothGatt, opcode: Byte, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val service = gatt.getService(MESH_SERVICE_UUID) ?: return@withContext false
        val charData = service.getCharacteristic(CHAR_DATA_UUID) ?: return@withContext false

        val peerEntry = activeGattClients[gatt.device.address]
        val mtu = peerEntry?.second ?: 23
        val maxChunkBody = (mtu - 3 - GATT_CHUNK_HEADER_SIZE).coerceIn(12, 480)
        val totalChunks = ((data.size + maxChunkBody - 1) / maxChunkBody).coerceAtLeast(1)
        val seqId = gattSeqCounter.incrementAndGet().toShort()

        for (i in 0 until totalChunks) {
            val start = i * maxChunkBody
            val end = minOf(start + maxChunkBody, data.size)
            val chunkLen = end - start

            val frame = ByteArray(GATT_CHUNK_HEADER_SIZE + chunkLen)
            val buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
            buf.putShort(GATT_MAGIC)
            buf.put(opcode)
            buf.putShort(seqId)
            buf.putShort(i.toShort())
            buf.putShort(totalChunks.toShort())
            buf.putShort(chunkLen.toShort())
            buf.put(data, start, chunkLen)

            val writeSuccess = writeGattChunk(gatt, charData, frame)
            if (!writeSuccess) {
                Log.w(TAG, "GATT write failed for chunk $i/$totalChunks to ${gatt.device.address}")
            }
            delay(12L) // 12ms pacing ensures BLE hardware buffers do not overflow
        }
        true
    }

    @SuppressLint("MissingPermission")
    private fun writeGattChunk(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val res = gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                res == BluetoothStatusCodes.SUCCESS
            } else {
                char.value = data
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                gatt.writeCharacteristic(char)
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun sendBloomSummaryGatt(gatt: BluetoothGatt) {
        val localBundles = try {
            shredder.getAllBundleIds(80)
        } catch (_: Exception) {
            emptyList()
        }
        val bloom = BundleBloomFilter.fromBundleIds(localBundles)

        val payload = ByteBuffer.allocate(8 + 8 + BundleBloomFilter.FILTER_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        payload.putLong(TimeConsensusManager.shared.getConsensusTimeMs())
        payload.putLong(TimeConsensusManager.shared.tickLamport())
        payload.put(bloom.filterBytes)

        sendGattData(gatt, OP_GATT_BLOOM, payload.array())
    }

    private fun handleIncomingGattChunk(device: BluetoothDevice, data: ByteArray) {
        if (data.size < GATT_CHUNK_HEADER_SIZE) return
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val magic = buf.short
        if (magic != GATT_MAGIC) return
        val opcode = buf.get()
        val seqId = buf.short
        val chunkIdx = buf.short.toInt() and 0xFFFF
        val totalChunks = buf.short.toInt() and 0xFFFF
        val chunkLen = buf.short.toInt() and 0xFFFF
        if (data.size < GATT_CHUNK_HEADER_SIZE + chunkLen) return

        val chunkBytes = ByteArray(chunkLen)
        buf.get(chunkBytes)

        val key = "${device.address}_$seqId"
        val assembly = reassemblyMap.computeIfAbsent(key) {
            GattAssembly(seqId, opcode, totalChunks)
        }
        assembly.chunks[chunkIdx] = chunkBytes

        if (assembly.chunks.size >= totalChunks) {
            reassemblyMap.remove(key)
            val totalBytes = assembly.chunks.entries.sortedBy { it.key }.fold(0) { acc, e -> acc + e.value.size }
            val fullPayload = ByteBuffer.allocate(totalBytes)
            assembly.chunks.entries.sortedBy { it.key }.forEach { fullPayload.put(it.value) }
            val completeData = fullPayload.array()

            when (opcode) {
                OP_GATT_BUNDLE -> {
                    val frame = if (completeData.size == 4096) completeData else completeData.copyOf(4096)
                    router.processIngressPacket(frame)
                    Log.i(TAG, "GATT: Ingress packet 4,096 bytes successfully delivered from ${device.address}")
                }
                OP_GATT_BLOOM -> {
                    handleIncomingGattBloom(device, completeData)
                }
            }
        }
    }

    private fun handleIncomingGattBloom(device: BluetoothDevice, data: ByteArray) {
        if (data.size < 8 + 8 + BundleBloomFilter.FILTER_SIZE_BYTES) return
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val peerTime = buf.long
        val peerLamport = buf.long
        val filterBytes = ByteArray(BundleBloomFilter.FILTER_SIZE_BYTES)
        buf.get(filterBytes)

        TimeConsensusManager.shared.recordPeerTimestamp(peerTime)
        TimeConsensusManager.shared.witnessLamport(peerLamport)

        val peerBloom = BundleBloomFilter(filterBytes)
        val localBundles = shredder.getAllBundleIds(80)
        val toSend = localBundles.filter { !peerBloom.mightContain(it) }.take(10)

        // Send missing bundles back to peer over GATT if connected
        val gattEntry = activeGattClients[device.address]
        if (gattEntry != null) {
            scope.launch {
                for (id in toSend) {
                    val bundleBytes = shredder.retrieveBundle(id)
                    if (bundleBytes != null) {
                        val frame = if (bundleBytes.size == 4096) bundleBytes else bundleBytes.copyOf(4096)
                        sendGattData(gattEntry.first, OP_GATT_BUNDLE, frame)
                    }
                }
            }
        }
    }

    /**
     * Registers an open Bluetooth socket (L2CAP or RFCOMM), runs initial epidemic sync,
     * and streams live packets continuously.
     */
    private suspend fun handleConnectedSocket(socket: BluetoothSocket, remoteAddress: String) = withContext(Dispatchers.IO) {
        val existing = activeBleSockets.put(remoteAddress, socket)
        if (existing != null && existing !== socket) {
            try { existing.close() } catch (_: Exception) {}
        }
        updatePeerCount()

        try {
            val input: InputStream = socket.inputStream

            // 1. Initial epidemic Bloom filter inventory sync
            performBloomSync(socket)

            // 2. Keep socket alive and stream live packets in both directions
            val readBuf = ByteArray(4096)
            while (isActive && isRunning && socket.isConnected) {
                val opByte = input.read()
                if (opByte == -1) break

                when (opByte.toByte()) {
                    MSG_BLOOM_SUMMARY -> {
                        handleBloomSummary(socket, input)
                    }
                    MSG_BUNDLE_PAYLOAD -> {
                        if (readExact(input, readBuf)) {
                            if (rateLimiter.allowPacket(remoteAddress, "BUNDLE")) {
                                router.processIngressPacket(readBuf.clone())
                            }
                        } else {
                            break
                        }
                    }
                    MSG_INVENTORY_OFFER -> {
                        handleLegacyInventoryOffer(socket, input)
                    }
                    MSG_INVENTORY_WANT -> {
                        handleLegacyInventoryWant(socket, input)
                    }
                    else -> {}
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
            activeBleSockets.remove(remoteAddress, socket)
            updatePeerCount()
        }
    }

    private fun writeFrameSafe(socket: BluetoothSocket, opcode: Byte, data: ByteArray): Boolean {
        return try {
            synchronized(socket) {
                if (socket.isConnected) {
                    val out: OutputStream = socket.outputStream
                    out.write(opcode.toInt())
                    out.write(data)
                    out.flush()
                    true
                } else false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Broadcasts an invariant 4,096-byte packet to all active Bluetooth peers immediately
     * across BOTH L2CAP socket streams and GATT connections.
     * In Stealth Mode, background broadcasts are suppressed to prevent hostile SIGINT triangulation.
     */
    fun broadcastPacket(packet: ByteArray) {
        if (isStealthMode) {
            Log.w(TAG, "RF RADIO SILENCE: Broadcast suppressed to prevent hostile SIGINT detection.")
            return
        }
        scope.launch {
            val frame = if (packet.size == 4096) packet else packet.copyOf(4096)
            var delivered = 0

            // 1. Push over active L2CAP/RFCOMM sockets
            for ((addr, socket) in activeBleSockets) {
                if (writeFrameSafe(socket, MSG_BUNDLE_PAYLOAD, frame)) {
                    delivered++
                } else {
                    activeBleSockets.remove(addr, socket)
                    try { socket.close() } catch (_: Exception) {}
                }
            }

            // 2. Push over active GATT Client connections
            for ((addr, gattPair) in activeGattClients) {
                try {
                    sendGattData(gattPair.first, OP_GATT_BUNDLE, frame)
                    delivered++
                } catch (e: Exception) {
                    activeGattClients.remove(addr)
                    try { gattPair.first.close() } catch (_: Exception) {}
                }
            }

            updatePeerCount()

            // If no peers currently connected, trigger immediate discovery burst
            if (delivered == 0) {
                triggerImmediateScanAndSync()
            }
        }
    }

    /**
     * Emits a Low-Probability-of-Interception (LPI) micro-burst transmission (~150ms window).
     * Used in hostile / post-apocalyptic environments where prolonged RF broadcasting
     * draws hostile direction-finding fire.
     */
    @SuppressLint("MissingPermission")
    fun burstTransmit(packet: ByteArray, burstDurationMs: Long = 150L) {
        scope.launch {
            try {
                if (isStealthMode) {
                    startAdvertising()
                }
                val frame = if (packet.size == 4096) packet else packet.copyOf(4096)
                for ((addr, socket) in activeBleSockets) {
                    writeFrameSafe(socket, MSG_BUNDLE_PAYLOAD, frame)
                }
                for ((addr, gattPair) in activeGattClients) {
                    try { sendGattData(gattPair.first, OP_GATT_BUNDLE, frame) } catch (_: Exception) {}
                }
                delay(burstDurationMs)
            } finally {
                if (isStealthMode) {
                    try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun performBloomSync(socket: BluetoothSocket) {
        val localBundles = shredder.getAllBundleIds(80)
        val bloom = BundleBloomFilter.fromBundleIds(localBundles)

        val payload = ByteBuffer.allocate(8 + 8 + BundleBloomFilter.FILTER_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        payload.putLong(TimeConsensusManager.shared.getConsensusTimeMs())
        payload.putLong(TimeConsensusManager.shared.tickLamport())
        payload.put(bloom.filterBytes)

        writeFrameSafe(socket, MSG_BLOOM_SUMMARY, payload.array())
    }

    private fun handleBloomSummary(socket: BluetoothSocket, input: InputStream) {
        val summaryBytes = ByteArray(8 + 8 + BundleBloomFilter.FILTER_SIZE_BYTES)
        if (!readExact(input, summaryBytes)) return

        val buf = ByteBuffer.wrap(summaryBytes).order(ByteOrder.BIG_ENDIAN)
        val peerTime = buf.long
        val peerLamport = buf.long
        val filterBytes = ByteArray(BundleBloomFilter.FILTER_SIZE_BYTES)
        buf.get(filterBytes)

        TimeConsensusManager.shared.recordPeerTimestamp(peerTime)
        TimeConsensusManager.shared.witnessLamport(peerLamport)

        val peerBloom = BundleBloomFilter(filterBytes)
        val localBundles = shredder.getAllBundleIds(80)
        val toSend = localBundles.filter { !peerBloom.mightContain(it) }.take(15)

        for (id in toSend) {
            val bundleBytes = shredder.retrieveBundle(id)
            if (bundleBytes != null) {
                val frame = if (bundleBytes.size == 4096) bundleBytes else bundleBytes.copyOf(4096)
                writeFrameSafe(socket, MSG_BUNDLE_PAYLOAD, frame)
            }
        }
    }

    private fun handleLegacyInventoryOffer(socket: BluetoothSocket, input: InputStream) {
        val countBuf = ByteArray(4)
        if (!readExact(input, countBuf)) return
        val peerCount = ByteBuffer.wrap(countBuf).int.coerceIn(0, 100)

        val wantedList = mutableListOf<ByteArray>()
        for (i in 0 until peerCount) {
            val bundleId = ByteArray(32)
            if (!readExact(input, bundleId)) return
            if (shredder.retrieveBundle(bundleId) == null) {
                wantedList.add(bundleId)
            }
        }

        val wantBuf = ByteBuffer.allocate(4 + wantedList.size * 32).order(ByteOrder.BIG_ENDIAN)
        wantBuf.putInt(wantedList.size)
        for (id in wantedList) {
            val id32 = if (id.size == 32) id else id.copyOf(32)
            wantBuf.put(id32)
        }
        writeFrameSafe(socket, MSG_INVENTORY_WANT, wantBuf.array())
    }

    private fun handleLegacyInventoryWant(socket: BluetoothSocket, input: InputStream) {
        val countBuf = ByteArray(4)
        if (!readExact(input, countBuf)) return
        val wantCount = ByteBuffer.wrap(countBuf).int.coerceIn(0, 100)

        for (i in 0 until wantCount) {
            val reqId = ByteArray(32)
            if (!readExact(input, reqId)) return
            val bundleBytes = shredder.retrieveBundle(reqId)
            if (bundleBytes != null) {
                val frame = if (bundleBytes.size == 4096) bundleBytes else bundleBytes.copyOf(4096)
                writeFrameSafe(socket, MSG_BUNDLE_PAYLOAD, frame)
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
