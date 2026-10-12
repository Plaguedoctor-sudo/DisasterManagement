package network.disaster.management.transport

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import network.disaster.management.routing.TimeConsensusManager
import network.disaster.management.routing.TriPoolRouter
import network.disaster.management.storage.LowWearCryptoShredder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WifiMeshSupervisor(
    private val context: Context,
    private val shredder: LowWearCryptoShredder,
    private val router: TriPoolRouter
) {
    companion object {
        @Volatile
        var shared: WifiMeshSupervisor? = null

        const val DISCOVERY_UDP_PORT = 42424
        const val SYNC_TCP_PORT = 42425
        const val BEACON_MAGIC = "DISASTER_WIFI_BEACON"

        const val MSG_INVENTORY_OFFER: Byte = 0x01
        const val MSG_INVENTORY_WANT: Byte = 0x02
        const val MSG_BUNDLE_PAYLOAD: Byte = 0x03
        const val MSG_BLOOM_SUMMARY: Byte = 0x04
    }

    private val myNodeId: String = UUID.randomUUID().toString().take(8)

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var multicastLock: WifiManager.MulticastLock? = null
    private var udpDiscoverySocket: DatagramSocket? = null
    private var tcpServerSocket: ServerSocket? = null

    private var isRunning = false

    // Key: Remote IP address string -> Socket
    private val activeConnections = ConcurrentHashMap<String, Socket>()

    // Cooldown map for peer connection attempts: IP -> timestamp
    private val peerConnectCooldown = ConcurrentHashMap<String, Long>()
    private val rateLimiter = MeshRateLimiter(peerLimitPerSec = 20, channelLimitPerSec = 60)

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    private val _peerIps = MutableStateFlow<List<String>>(emptyList())
    val peerIps: StateFlow<List<String>> = _peerIps.asStateFlow()

    fun startMesh() {
        if (isRunning) return
        isRunning = true
        shared = this

        acquireMulticastLock()
        startTcpServer()
        startUdpBeaconListener()
        startUdpBeaconBroadcaster()
        startPeriodicSubnetScanner()
    }

    fun stopMesh() {
        isRunning = false
        if (shared === this) {
            shared = null
        }
        job.cancel()

        try { udpDiscoverySocket?.close() } catch (_: Exception) {}
        try { tcpServerSocket?.close() } catch (_: Exception) {}

        for ((_, socket) in activeConnections) {
            try { socket.close() } catch (_: Exception) {}
        }
        activeConnections.clear()
        updatePeerStatus()

        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("DisasterWifiMulticast")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }

    private fun startTcpServer() {
        scope.launch {
            try {
                tcpServerSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(SYNC_TCP_PORT))
                }

                while (isActive && isRunning) {
                    val socket = tcpServerSocket?.accept() ?: break
                    val remoteIp = socket.inetAddress.hostAddress ?: "unknown"
                    launch {
                        handlePeerConnection(socket, remoteIp)
                    }
                }
            } catch (e: Exception) {
                // If binding fails on standard port, fallback to any available port
                if (isActive && isRunning) {
                    try {
                        tcpServerSocket = ServerSocket(0)
                        while (isActive && isRunning) {
                            val socket = tcpServerSocket?.accept() ?: break
                            val remoteIp = socket.inetAddress.hostAddress ?: "unknown"
                            launch {
                                handlePeerConnection(socket, remoteIp)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun startUdpBeaconListener() {
        scope.launch {
            try {
                udpDiscoverySocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_UDP_PORT))
                }

                val buffer = ByteArray(1024)
                while (isActive && isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpDiscoverySocket?.receive(packet)

                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (text.startsWith(BEACON_MAGIC)) {
                        // Format: DISASTER_WIFI_BEACON:<nodeId>:<tcpPort>
                        val parts = text.split(":")
                        if (parts.size >= 3) {
                            val peerId = parts[1]
                            val peerPort = parts[2].toIntOrNull() ?: SYNC_TCP_PORT

                            if (peerId != myNodeId) {
                                val peerAddress = packet.address
                                val peerIp = peerAddress.hostAddress ?: continue
                                triggerConnectToPeer(peerAddress, peerPort)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun startUdpBeaconBroadcaster() {
        scope.launch {
            val beaconText = "$BEACON_MAGIC:$myNodeId:${getTcpListeningPort()}"
            val beaconBytes = beaconText.toByteArray(Charsets.UTF_8)

            while (isActive && isRunning) {
                sendDiscoveryBeacons(beaconBytes)
                delay(3000L) // Broadcast beacon every 3 seconds
            }
        }
    }

    private fun sendDiscoveryBeacons(beaconBytes: ByteArray) {
        val targets = getBroadcastAndGatewayTargets()
        for (target in targets) {
            try {
                val packet = DatagramPacket(beaconBytes, beaconBytes.size, target, DISCOVERY_UDP_PORT)
                udpDiscoverySocket?.send(packet)
            } catch (_: Exception) {}
        }
    }

    fun triggerManualHotspotScan() {
        scope.launch {
            // Send burst of discovery beacons immediately
            val beaconText = "$BEACON_MAGIC:$myNodeId:${getTcpListeningPort()}"
            val beaconBytes = beaconText.toByteArray(Charsets.UTF_8)
            sendDiscoveryBeacons(beaconBytes)

            // Direct TCP probe for common hotspot client & AP addresses
            probeHotspotSubnet()
        }
    }

    private fun startPeriodicSubnetScanner() {
        scope.launch {
            while (isActive && isRunning) {
                delay(12000L) // Scan subnet every 12 seconds
                probeHotspotSubnet()
            }
        }
    }

    private suspend fun probeHotspotSubnet() = withContext(Dispatchers.IO) {
        val localIps = getLocalIpAddresses()
        val candidateIps = mutableSetOf<String>()

        // 1. Gateway IP from DHCP
        getGatewayIp()?.let { candidateIps.add(it) }

        // 2. Default Android / Wi-Fi Direct / iOS Hotspot AP addresses
        candidateIps.add("192.168.43.1")
        candidateIps.add("192.168.49.1")
        candidateIps.add("172.20.10.1")
        candidateIps.add("192.168.137.1")

        // 3. ARP cache connected devices
        candidateIps.addAll(getArpConnectedIps())

        // 4. Dynamically scan subnet across any IPv4 network (Android Hotspot, Samsung, iPhone, Wi-Fi router)
        for (ip in localIps) {
            val host = ip.hostAddress ?: continue
            if (host != "127.0.0.1" && host.contains(".")) {
                val prefix = host.substringBeforeLast(".") + "."
                val myLastOctet = host.substringAfterLast(".").toIntOrNull() ?: -1
                for (i in 1..25) {
                    if (i != myLastOctet) {
                        candidateIps.add("$prefix$i")
                    }
                }
            }
        }

        // Filter out our own IPs and currently connected IPs
        val myHosts = localIps.mapNotNull { it.hostAddress }.toSet()
        candidateIps.removeAll(myHosts)
        candidateIps.removeAll(activeConnections.keys)

        for (cand in candidateIps) {
            if (!isActive || !isRunning) break
            launch {
                val lastAttempt = peerConnectCooldown[cand] ?: 0L
                if (System.currentTimeMillis() - lastAttempt > 15000L) {
                    peerConnectCooldown[cand] = System.currentTimeMillis()
                    try {
                        val addr = InetAddress.getByName(cand)
                        triggerConnectToPeer(addr, SYNC_TCP_PORT)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun triggerConnectToPeer(peerAddress: InetAddress, peerPort: Int) {
        val ip = peerAddress.hostAddress ?: return
        if (activeConnections.containsKey(ip)) return

        val lastAttempt = peerConnectCooldown[ip] ?: 0L
        if (System.currentTimeMillis() - lastAttempt < 5000L) return
        peerConnectCooldown[ip] = System.currentTimeMillis()

        scope.launch {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(peerAddress, peerPort), 2500)
                handlePeerConnection(socket, ip)
            } catch (_: Exception) {}
        }
    }

    private suspend fun handlePeerConnection(socket: Socket, remoteIp: String) = withContext(Dispatchers.IO) {
        val cleanIp = remoteIp.replace("^/+".toRegex(), "").replace("::ffff:", "").trim()
        if (!network.disaster.management.security.CitadelDefenseManager.isIngressPermitted(cleanIp)) {
            try { socket.close() } catch (_: Exception) {}
            return@withContext
        }
        if (network.disaster.management.security.NetworkQuarantineManager.isQuarantined(cleanIp)) {
            try { socket.close() } catch (_: Exception) {}
            return@withContext
        }

        val existing = activeConnections.put(remoteIp, socket)
        if (existing != null && existing !== socket) {
            try { existing.close() } catch (_: Exception) {}
        }
        updatePeerStatus()

        try {
            val input = socket.getInputStream()

            // 1. Initial Bloom filter inventory sync
            performBloomSync(socket)

            // 2. Keep socket alive and stream live packets in both directions
            val readBuf = ByteArray(4096)
            while (isActive && isRunning && !socket.isClosed) {
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
                    else -> {
                        // Unknown opcode or sync ping
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
            activeConnections.remove(remoteIp, socket)
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

        // Send 48-byte payload: consensus time (8) + Lamport clock (8) + Bloom filter (32)
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

        if (!readExact(input, timeBuf) || !readExact(input, lamportBuf) || !readExact(input, peerFilter)) {
            return
        }

        val peerTime = ByteBuffer.wrap(timeBuf).order(ByteOrder.BIG_ENDIAN).long
        val peerLamport = ByteBuffer.wrap(lamportBuf).order(ByteOrder.BIG_ENDIAN).long
        TimeConsensusManager.shared.recordPeerTimestamp(peerTime)
        TimeConsensusManager.shared.witnessLamport(peerLamport)

        val peerBloom = BundleBloomFilter(peerFilter)
        val localBundles = shredder.getAllBundleIds(80)
        val toSend = localBundles.filter { !peerBloom.mightContain(it) }.take(30)

        for (id in toSend) {
            val bundleBytes = shredder.retrieveBundle(id)
            if (bundleBytes != null) {
                val frame = if (bundleBytes.size == 4096) bundleBytes else bundleBytes.copyOf(4096)
                writeFrameSafe(socket, MSG_BUNDLE_PAYLOAD, frame)
            }
        }
    }

    /**
     * Instantly broadcasts a newly generated packet to all connected Wi-Fi hotspot peers.
     */
    fun broadcastPacket(frame: ByteArray) {
        if (activeConnections.isEmpty()) return
        scope.launch {
            val invariantFrame = if (frame.size == 4096) frame else frame.copyOf(4096)
            for ((ip, socket) in activeConnections) {
                try {
                    writeFrameSafe(socket, MSG_BUNDLE_PAYLOAD, invariantFrame)
                } catch (_: Exception) {
                    try { socket.close() } catch (_: Exception) {}
                    activeConnections.remove(ip, socket)
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

    private fun updatePeerStatus() {
        val list = activeConnections.keys().toList()
        _peerCount.value = list.size
        _peerIps.value = list
    }

    fun getActivePeerCount(): Int = activeConnections.size

    fun getActivePeerIps(): List<String> = activeConnections.keys().toList()

    fun getTcpListeningPort(): Int = tcpServerSocket?.localPort ?: SYNC_TCP_PORT

    fun getLocalIps(): List<String> = getLocalIpAddresses().mapNotNull { it.hostAddress }

    fun getLocalIpAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return list
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        list.add(addr)
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun getBroadcastAndGatewayTargets(): List<InetAddress> {
        val targets = mutableSetOf<InetAddress>()

        // 1. Universal LAN broadcast
        try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}

        // 2. Subnet specific broadcast addresses
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return targets.toList()
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (interfaceAddress in iface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        targets.add(broadcast)
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Hotspot AP default
        try { targets.add(InetAddress.getByName("192.168.43.1")) } catch (_: Exception) {}
        try { targets.add(InetAddress.getByName("192.168.43.255")) } catch (_: Exception) {}

        // 4. Gateway IP
        getGatewayIp()?.let {
            try { targets.add(InetAddress.getByName(it)) } catch (_: Exception) {}
        }

        return targets.toList()
    }

    private fun getGatewayIp(): String? {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wm?.dhcpInfo ?: return null
            if (dhcp.gateway != 0) {
                return String.format(
                    java.util.Locale.US, "%d.%d.%d.%d",
                    dhcp.gateway and 0xFF,
                    (dhcp.gateway shr 8) and 0xFF,
                    (dhcp.gateway shr 16) and 0xFF,
                    (dhcp.gateway shr 24) and 0xFF
                )
            }
        } catch (_: Exception) {}
        return null
    }

    private fun getArpConnectedIps(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val file = File("/proc/net/arp")
            if (file.exists()) {
                file.forEachLine { line ->
                    val tokens = line.split("\\s+".toRegex())
                    if (tokens.size >= 4 && tokens[0] != "IP" && tokens[2] != "0x0") {
                        ips.add(tokens[0])
                    }
                }
            }
        } catch (_: Exception) {}
        return ips
    }
}
