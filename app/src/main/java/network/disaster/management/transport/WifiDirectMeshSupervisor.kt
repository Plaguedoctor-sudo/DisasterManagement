package network.disaster.management.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Build
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
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Autonomous Wi-Fi Direct (Wi-Fi P2P) Mesh Transceiver.
 * Enables zero-network device-to-device discovery and communication:
 * Two devices communicate directly even if neither is connected to a Wi-Fi router,
 * internet, or personal hotspot.
 */
class WifiDirectMeshSupervisor(
    private val context: Context,
    private val shredder: LowWearCryptoShredder,
    private val router: TriPoolRouter
) {
    companion object {
        private const val TAG = "WifiDirectMesh"

        @Volatile
        var shared: WifiDirectMeshSupervisor? = null

        const val SYNC_PORT = WifiMeshSupervisor.SYNC_TCP_PORT // 42425
        const val DEFAULT_GO_IP = "192.168.49.1"

        const val MSG_INVENTORY_OFFER: Byte = 0x01
        const val MSG_INVENTORY_WANT: Byte = 0x02
        const val MSG_BUNDLE_PAYLOAD: Byte = 0x03
        const val MSG_BLOOM_SUMMARY: Byte = 0x04
    }

    private val p2pManager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var isRunning = false
    private var isP2pEnabled = false
    private var isConnectingOrConnected = false

    private val activeSockets = ConcurrentHashMap<String, Socket>()
    private val discoveredPeers = ConcurrentHashMap<String, WifiP2pDevice>()
    private val rateLimiter = MeshRateLimiter(peerLimitPerSec = 20, channelLimitPerSec = 60)

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    private val _connectionStatus = MutableStateFlow("P2P Idle")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    fun getActivePeerCount(): Int = activeSockets.size

    private fun updatePeerStatus() {
        val count = activeSockets.size
        _peerCount.value = count
        if (count > 0) {
            _connectionStatus.value = "Wi-Fi Direct Connected ($count Peer${if (count > 1) "s" else ""})"
        } else if (isConnectingOrConnected) {
            _connectionStatus.value = "Wi-Fi Direct Group Active"
        } else {
            _connectionStatus.value = "Wi-Fi Direct Standby (${discoveredPeers.size} Discovered)"
        }
    }

    @SuppressLint("MissingPermission")
    fun startMesh() {
        if (isRunning || p2pManager == null) return
        isRunning = true
        shared = this

        try {
            p2pChannel = p2pManager.initialize(context, context.mainLooper) {
                Log.w(TAG, "Wi-Fi P2P Channel disconnected; reinitializing...")
                startMesh()
            }

            registerReceiver()
            startPeriodicDiscovery()
            Log.i(TAG, "Wi-Fi Direct Mesh Transceiver initialized and discovery started")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to initialize Wi-Fi Direct: ${e.message}")
        }
    }

    fun stopMesh() {
        isRunning = false
        if (shared === this) {
            shared = null
        }
        job.cancel()

        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        receiver = null

        for ((_, socket) in activeSockets) {
            try { socket.close() } catch (_: Exception) {}
        }
        activeSockets.clear()

        try {
            p2pChannel?.let { ch ->
                p2pManager?.cancelConnect(ch, null)
                p2pManager?.removeGroup(ch, null)
            }
        } catch (_: Exception) {}

        isConnectingOrConnected = false
        updatePeerStatus()
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        isP2pEnabled = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                        Log.i(TAG, "Wi-Fi P2P state changed: enabled=$isP2pEnabled")
                        if (isP2pEnabled && isRunning) {
                            triggerPeerDiscovery()
                        }
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        val ch = p2pChannel ?: return
                        try {
                            p2pManager?.requestPeers(ch) { peers ->
                                handleDiscoveredPeersList(peers)
                            }
                        } catch (_: Throwable) {}
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val ch = p2pChannel ?: return
                        val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                        }

                        if (networkInfo?.isConnected == true) {
                            try {
                                p2pManager?.requestConnectionInfo(ch) { info ->
                                    handleConnectionEstablished(info)
                                }
                            } catch (_: Throwable) {}
                        } else {
                            isConnectingOrConnected = false
                            updatePeerStatus()
                        }
                    }
                }
            }
        }

        try {
            context.registerReceiver(receiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register Wi-Fi Direct receiver", e)
        }
    }

    private fun startPeriodicDiscovery() {
        scope.launch {
            while (isActive && isRunning) {
                triggerPeerDiscovery()
                delay(20000L) // Scan for peers every 20s
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun triggerPeerDiscovery() {
        val ch = p2pChannel ?: return
        try {
            p2pManager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Wi-Fi Direct discoverPeers initiated")
                }
                override fun onFailure(reason: Int) {
                    Log.d(TAG, "Wi-Fi Direct discoverPeers failed: reason=$reason")
                }
            })
        } catch (_: Throwable) {}
    }

    @SuppressLint("MissingPermission")
    fun triggerPeerDiscoveryAndAutoConnect() {
        triggerPeerDiscovery()
        scope.launch {
            delay(1500L)
            // If any peers discovered and not connected, auto connect
            for ((_, peer) in discoveredPeers) {
                if (activeSockets.isEmpty()) {
                    connectToPeer(peer)
                    break
                }
            }
        }
    }

    private fun handleDiscoveredPeersList(peerList: WifiP2pDeviceList?) {
        if (peerList == null) return
        discoveredPeers.clear()
        for (device in peerList.deviceList) {
            val addr = device.deviceAddress ?: continue
            discoveredPeers[addr] = device
        }
        updatePeerStatus()

        // Auto-connect if discovered an available peer and not connected
        if (!isConnectingOrConnected && activeSockets.isEmpty()) {
            val candidate = peerList.deviceList.firstOrNull { it.status == WifiP2pDevice.AVAILABLE }
            if (candidate != null) {
                connectToPeer(candidate)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(device: WifiP2pDevice) {
        val ch = p2pChannel ?: return
        if (isConnectingOrConnected) return
        isConnectingOrConnected = true

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0 // Prefer client mode so peer can host, or vice-versa
        }

        try {
            Log.i(TAG, "Connecting to Wi-Fi Direct peer: ${device.deviceName} (${device.deviceAddress})...")
            p2pManager?.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Wi-Fi Direct connect negotiation started with ${device.deviceAddress}")
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Wi-Fi Direct connect failed: reason=$reason")
                    isConnectingOrConnected = false
                }
            })
        } catch (e: Throwable) {
            Log.w(TAG, "Exception connecting Wi-Fi Direct peer", e)
            isConnectingOrConnected = false
        }
    }

    @SuppressLint("MissingPermission")
    fun createAutonomousGroup() {
        val ch = p2pChannel ?: return
        try {
            p2pManager?.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Autonomous Wi-Fi Direct group created (acting as Group Owner)")
                    isConnectingOrConnected = true
                    updatePeerStatus()
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Failed to create autonomous Wi-Fi Direct group: reason=$reason")
                }
            })
        } catch (_: Throwable) {}
    }

    private fun handleConnectionEstablished(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) return
        isConnectingOrConnected = true
        Log.i(TAG, "Wi-Fi Direct Group Formed! isGroupOwner=${info.isGroupOwner}, GO IP=${info.groupOwnerAddress?.hostAddress}")

        if (info.isGroupOwner) {
            // Group Owner (192.168.49.1):
            // The WifiMeshSupervisor TCP server is already listening on port 42425 (0.0.0.0).
            // Any client connecting to 192.168.49.1:42425 will be accepted automatically!
            updatePeerStatus()
        } else {
            // Client node:
            // Group Owner is at info.groupOwnerAddress (typically 192.168.49.1).
            // Actively connect client TCP socket to Group Owner!
            val goHost = info.groupOwnerAddress?.hostAddress ?: DEFAULT_GO_IP
            scope.launch {
                delay(800L) // Wait briefly for Group Owner TCP socket listener
                connectTcpToGroupOwner(goHost)
            }
        }
    }

    private suspend fun connectTcpToGroupOwner(goHost: String) = withContext(Dispatchers.IO) {
        if (activeSockets.containsKey(goHost)) return@withContext

        for (attempt in 1..4) {
            if (!isActive || !isRunning) break
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.soTimeout = 0
                socket.connect(InetSocketAddress(goHost, SYNC_PORT), 4000)

                Log.i(TAG, "Connected Wi-Fi Direct TCP socket to Group Owner at $goHost:$SYNC_PORT")
                handleConnectedSocket(socket, goHost)
                return@withContext
            } catch (e: Exception) {
                Log.d(TAG, "Attempt $attempt to connect to GO $goHost failed: ${e.message}")
                delay(1200L)
            }
        }
    }

    private suspend fun handleConnectedSocket(socket: Socket, remoteIp: String) = withContext(Dispatchers.IO) {
        activeSockets[remoteIp] = socket
        updatePeerStatus()

        try {
            val input = socket.getInputStream()

            // 1. Initial Bloom filter inventory sync
            performBloomSync(socket)

            // 2. Stream live packets
            val readBuf = ByteArray(4096)
            while (isActive && isRunning && socket.isConnected && !socket.isClosed) {
                val opByte = input.read()
                if (opByte == -1) break

                when (opByte.toByte()) {
                    MSG_BLOOM_SUMMARY -> {
                        handleBloomSummary(socket, input)
                    }
                    MSG_BUNDLE_PAYLOAD -> {
                        if (readExact(input, readBuf)) {
                            if (rateLimiter.allowPacket(remoteIp, "BUNDLE")) {
                                router.processIngressPacket(readBuf.clone())
                            }
                        } else {
                            break
                        }
                    }
                    else -> {}
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
            activeSockets.remove(remoteIp, socket)
            updatePeerStatus()
        }
    }

    private fun writeFrameSafe(socket: Socket, opcode: Byte, data: ByteArray) {
        try {
            synchronized(socket) {
                if (!socket.isClosed && socket.isConnected) {
                    val out = socket.getOutputStream()
                    out.write(opcode.toInt())
                    out.write(data)
                    out.flush()
                }
            }
        } catch (_: Exception) {}
    }

    private fun performBloomSync(socket: Socket) {
        val localBundles = shredder.getAllBundleIds(80)
        val bloom = BundleBloomFilter.fromBundleIds(localBundles)

        val bloomBuf = ByteBuffer.allocate(8 + 8 + BundleBloomFilter.FILTER_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        bloomBuf.putLong(TimeConsensusManager.shared.getConsensusTimeMs())
        bloomBuf.putLong(TimeConsensusManager.shared.tickLamport())
        bloomBuf.put(bloom.filterBytes)
        writeFrameSafe(socket, MSG_BLOOM_SUMMARY, bloomBuf.array())
    }

    private fun handleBloomSummary(socket: Socket, input: InputStream) {
        val timeBuf = ByteArray(8)
        val lamportBuf = ByteArray(8)
        val peerFilter = ByteArray(BundleBloomFilter.FILTER_SIZE_BYTES)

        if (!readExact(input, timeBuf)) return
        if (!readExact(input, lamportBuf)) return
        if (!readExact(input, peerFilter)) return

        val peerTime = ByteBuffer.wrap(timeBuf).order(ByteOrder.BIG_ENDIAN).long
        val peerLamport = ByteBuffer.wrap(lamportBuf).order(ByteOrder.BIG_ENDIAN).long

        TimeConsensusManager.shared.recordPeerTimestamp(peerTime)
        TimeConsensusManager.shared.witnessLamport(peerLamport)

        val peerBloom = BundleBloomFilter(peerFilter)
        val localBundles = shredder.getAllBundleIds(80)
        val toSend = localBundles.filter { !peerBloom.mightContain(it) }.take(20)

        for (id in toSend) {
            val bundleBytes = shredder.retrieveBundle(id)
            if (bundleBytes != null) {
                val frame = if (bundleBytes.size == 4096) bundleBytes else bundleBytes.copyOf(4096)
                writeFrameSafe(socket, MSG_BUNDLE_PAYLOAD, frame)
            }
        }
    }

    fun broadcastPacket(packet: ByteArray) {
        scope.launch {
            val frame = if (packet.size == 4096) packet else packet.copyOf(4096)
            for ((ip, socket) in activeSockets) {
                try {
                    writeFrameSafe(socket, MSG_BUNDLE_PAYLOAD, frame)
                } catch (_: Exception) {
                    try { socket.close() } catch (_: Exception) {}
                    activeSockets.remove(ip, socket)
                    updatePeerStatus()
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
