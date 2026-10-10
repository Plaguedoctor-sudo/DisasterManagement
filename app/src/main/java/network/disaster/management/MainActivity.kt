package network.disaster.management

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.disaster.management.crypto.EnvelopeFormat
import network.disaster.management.crypto.HybridEngine
import network.disaster.management.crypto.NativeMemoryLock
import network.disaster.management.model.CivilGroup
import network.disaster.management.model.FeedMessage
import network.disaster.management.model.FileManifest
import network.disaster.management.model.MessageType
import network.disaster.management.routing.TriPoolRouter
import network.disaster.management.storage.LowWearCryptoShredder
import network.disaster.management.storage.MediaChunkingEngine
import network.disaster.management.system.GpsBeaconHelper
import network.disaster.management.system.OemBatteryManager
import network.disaster.management.transport.MeshForegroundService
import network.disaster.management.transport.acoustic.FastFskModem
import network.disaster.management.ui.GroupAdapter
import network.disaster.management.ui.GroupChatActivity
import network.disaster.management.ui.MessageAdapter
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var shredder: LowWearCryptoShredder
    private lateinit var router: TriPoolRouter
    private lateinit var batteryManager: OemBatteryManager
    private lateinit var mediaChunker: MediaChunkingEngine
    private lateinit var gpsHelper: GpsBeaconHelper
    private val memoryLock = NativeMemoryLock()
    private val hybridEngine = HybridEngine()
    private val acousticModem = FastFskModem()
    private val secureRandom = SecureRandom()

    private var sessionKeyBufferAddress: Long = 0L
    private val localRecipientTag = ByteArray(16).apply {
        for (i in indices) this[i] = (i + 1).toByte()
    }

    // Adapters
    private lateinit var broadcastAdapter: MessageAdapter
    private lateinit var groupAdapter: GroupAdapter

    // Selected media state
    private var pendingManifest: FileManifest? = null
    private var pendingPackages: List<MediaChunkingEngine.ChunkPackage>? = null
    private var pendingUri: Uri? = null

    private val selectFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { onMediaFileSelected(it) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        updatePermissionBanner(!allGranted)
        if (allGranted || hasCriticalPermissions()) {
            startMeshServicesSafely()
            Toast.makeText(this, "Mesh active: Ready for Wi-Fi hotspot & BLE communication", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Native memory pinning (mlock) for session key security
        sessionKeyBufferAddress = memoryLock.allocateLockedMemory(4096)

        shredder = LowWearCryptoShredder(this)
        router = TriPoolRouter(shredder, localRecipientTag)
        mediaChunker = MediaChunkingEngine(this, shredder)
        batteryManager = OemBatteryManager(this)
        gpsHelper = GpsBeaconHelper(this)

        setupPermissionBanner()
        setupTabs()
        setupBroadcastFeed()
        setupGroupsTab()
        setupMediaTab()
        setupRelaysTab()

        findViewById<TextView>(R.id.tvStatus).text = "Status: Initializing Mesh Transceivers..."

        checkAndRequestAllPermissions()
        startPeriodicRefresh()
    }

    private fun setupTabs() {
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val tabBroadcast = findViewById<View>(R.id.layoutTabBroadcast)
        val tabGroups = findViewById<View>(R.id.layoutTabGroups)
        val tabMedia = findViewById<View>(R.id.layoutTabMedia)
        val tabRelays = findViewById<View>(R.id.layoutTabRelays)

        tabLayout.addTab(tabLayout.newTab().setText("Feed"))
        tabLayout.addTab(tabLayout.newTab().setText("Groups"))
        tabLayout.addTab(tabLayout.newTab().setText("Media Share"))
        tabLayout.addTab(tabLayout.newTab().setText("Relays"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tabBroadcast.visibility = View.GONE
                tabGroups.visibility = View.GONE
                tabMedia.visibility = View.GONE
                tabRelays.visibility = View.GONE

                when (tab?.position) {
                    0 -> {
                        tabBroadcast.visibility = View.VISIBLE
                        loadBroadcastMessages()
                    }
                    1 -> {
                        tabGroups.visibility = View.VISIBLE
                        loadGroups()
                    }
                    2 -> tabMedia.visibility = View.VISIBLE
                    3 -> {
                        tabRelays.visibility = View.VISIBLE
                        updateStorageQuotaDisplay()
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupBroadcastFeed() {
        val rvBroadcast = findViewById<RecyclerView>(R.id.rvBroadcastFeed)
        val etInput = findViewById<EditText>(R.id.etBroadcastInput)
        val btnSend = findViewById<Button>(R.id.btnBroadcastSend)
        val btnDistress = findViewById<Button>(R.id.btnQuickDistress)

        val btnQuickSafe = findViewById<Button>(R.id.btnQuickSafe)
        val btnQuickTrapped = findViewById<Button>(R.id.btnQuickTrapped)
        val btnQuickSupplies = findViewById<Button>(R.id.btnQuickSupplies)
        val btnQuickHazard = findViewById<Button>(R.id.btnQuickHazard)

        broadcastAdapter = MessageAdapter(emptyList()) { msg ->
            openAttachment(msg)
        }
        rvBroadcast.layoutManager = LinearLayoutManager(this)
        rvBroadcast.adapter = broadcastAdapter

        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                etInput.text.clear()
                broadcastReport(text)
            }
        }

        btnDistress.setOnClickListener {
            broadcastDistressAlert()
        }

        btnQuickSafe.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val coords = gpsHelper.getFastestCoordinates()?.toBeaconString() ?: "LOC:UNKNOWN"
                broadcastReport("🟢 STATUS: SAFE — Civilian check-in confirmed [$coords]")
            }
        }

        btnQuickTrapped.setOnClickListener {
            broadcastDistressAlert("🔴 CRITICAL: TRAPPED / MEDICAL AID REQUIRED")
        }

        btnQuickSupplies.setOnClickListener {
            showQuickSuppliesDialog()
        }

        btnQuickHazard.setOnClickListener {
            showQuickHazardDialog()
        }

        loadBroadcastMessages()
    }

    private fun showQuickSuppliesDialog() {
        val options = arrayOf(
            "💧 Clean Drinking Water Needed",
            "🩹 First Aid / Trauma Supplies",
            "🥫 Food / Non-Perishable Rations",
            "⛺ Shelter / Blankets / Thermal Gear",
            "🔋 Backup Power / Batteries / Radio",
            "🍼 Infant Care / Baby Formula"
        )
        AlertDialog.Builder(this)
            .setTitle("Broadcast Resource Need")
            .setItems(options) { _, which ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val coords = gpsHelper.getFastestCoordinates()?.toBeaconString() ?: "LOC:UNKNOWN"
                    broadcastReport("🟡 RESOURCE NEED: ${options[which]} [$coords]")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showQuickHazardDialog() {
        val options = arrayOf(
            "🚧 Road Blocked / Debris / Fallen Trees",
            "⚡ Downed Power Lines / Electrocution Risk",
            "🔥 Active Fire / Smoke Hazard",
            "🌊 Flood / Rising Water Level",
            "🏚️ Structural Collapse / Unstable Building",
            "☣️ Chemical / Gas Leak Odor"
        )
        AlertDialog.Builder(this)
            .setTitle("Broadcast Hazard Alert")
            .setItems(options) { _, which ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val coords = gpsHelper.getFastestCoordinates()?.toBeaconString() ?: "LOC:UNKNOWN"
                    broadcastReport("⚠️ HAZARD ALERT: ${options[which]} [$coords]")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadBroadcastMessages() {
        lifecycleScope.launch(Dispatchers.IO) {
            val messages = shredder.getMessages(null)
            withContext(Dispatchers.Main) {
                broadcastAdapter.updateMessages(messages)
            }
        }
    }

    private fun broadcastReport(text: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val senderId = "Civilian-" + (Build.MODEL.takeLast(4).ifEmpty { "ME" })
            val msgId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            val msg = FeedMessage(
                id = msgId,
                groupId = "GROUP_PUBLIC_BROADCAST",
                senderName = senderId,
                content = text,
                type = MessageType.TEXT,
                timestamp = now,
                isEmergency = false
            )
            shredder.insertMessage(msg)

            try {
                val json = org.json.JSONObject().apply {
                    put("id", msgId)
                    put("groupId", "GROUP_PUBLIC_BROADCAST")
                    put("senderName", senderId)
                    put("content", text)
                    put("type", MessageType.TEXT.name)
                    put("timestamp", now)
                    put("isEmergency", false)
                }.toString()
                val inputBytes = json.toByteArray(Charsets.UTF_8)
                val groupKey = ByteArray(32) { 0x01 } // Shared Public Broadcast Group Key
                val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
                val aad = "DISASTER-MESH-V1".toByteArray(Charsets.UTF_8)
                val (ciphertext, macTag) = hybridEngine.encryptPayloadTier1(groupKey, nonce, aad, inputBytes)

                val bundleId = ByteArray(32).also { secureRandom.nextBytes(it) }
                val x25519PubBytes = ByteArray(32).also { secureRandom.nextBytes(it) }

                val diff = batteryManager.getOptimalPoWDifficulty(isEmergency = false)
                val envelope = EnvelopeFormat.Tier1Envelope(
                    hopLimit = 7.toByte(),
                    creationEpoch = now,
                    ttlExpiry = now + (24 * 3600 * 1000L),
                    powDifficulty = diff,
                    powNonce = 0,
                    bundleId = bundleId,
                    rollingRecipientTag = localRecipientTag,
                    ephemeralX25519Pub = x25519PubBytes,
                    mlKemCiphertext = ByteArray(1088),
                    nonce = nonce,
                    bucketLength = ciphertext.size,
                    encryptedBody = ciphertext,
                    macTag = macTag
                )
                val rawSerialized = EnvelopeFormat.serializeTier1(envelope)
                val solvedNonce = EnvelopeFormat.solvePoW(rawSerialized, diff)
                envelope.powNonce = solvedNonce
                val finalSerialized = EnvelopeFormat.serializeTier1(envelope)

                val verdict = router.processIngressPacket(finalSerialized)
                network.disaster.management.transport.WifiMeshSupervisor.shared?.broadcastPacket(finalSerialized)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Report Broadcasted | $verdict (Pool B)", Toast.LENGTH_SHORT).show()
                    loadBroadcastMessages()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Transmission error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun broadcastDistressAlert(customText: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            val coords = gpsHelper.getFastestCoordinates()
            val coordStr = coords?.toBeaconString() ?: "LOC:UNKNOWN"
            val emergencyText = if (customText != null) {
                "$customText [$coordStr]"
            } else {
                "DISTRESS: CIVIL LIFE-SAFETY RELAY NEEDED [$coordStr]"
            }
            val emergencyAlert = emergencyText.toByteArray(Charsets.UTF_8)

            val shortId = ByteArray(8).also { secureRandom.nextBytes(it) }
            val x25519Pub = ByteArray(32).also { secureRandom.nextBytes(it) }
            val nonce = ByteArray(8).also { secureRandom.nextBytes(it) }
            val polyTag = ByteArray(16).also { secureRandom.nextBytes(it) }

            val env = EnvelopeFormat.EmergencyEnvelope(
                hopLimit = 15.toByte(),
                ttlExpirySeconds = 3600,
                shortBundleId = shortId,
                ephemeralX25519Pub = x25519Pub,
                nonce = nonce,
                ciphertext = emergencyAlert,
                poly1305Tag = polyTag
            )
            val serialized = EnvelopeFormat.serializeEmergency(env)

            // Store in Pool A (Emergency 50MB quota)
            val verdict = router.processIngressPacket(serialized)

            // Broadcast immediately to connected Wi-Fi hotspot peers
            network.disaster.management.transport.WifiMeshSupervisor.shared?.broadcastPacket(serialized)

            // Save message into local feed
            val msg = FeedMessage(
                id = UUID.randomUUID().toString(),
                groupId = "GROUP_PUBLIC_BROADCAST",
                senderName = "EMERGENCY BEACON",
                content = emergencyText,
                type = MessageType.EMERGENCY,
                timestamp = System.currentTimeMillis(),
                isEmergency = true
            )
            shredder.insertMessage(msg)

            // Transmit near-ultrasound acoustic burst (18.5kHz - 19.5kHz)
            acousticModem.transmitEmergencyBurst(serialized.take(32).toByteArray())

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "Distress Alert Broadcasted ($coordStr) via Wi-Fi, BLE & Ultrasonic ($verdict)",
                    Toast.LENGTH_LONG
                ).show()
                loadBroadcastMessages()
            }
        }
    }

    private fun setupGroupsTab() {
        val rvGroups = findViewById<RecyclerView>(R.id.rvGroups)
        val btnCreateGroup = findViewById<Button>(R.id.btnCreateGroup)
        val btnJoinGroupQr = findViewById<Button>(R.id.btnJoinGroupQr)

        groupAdapter = GroupAdapter(emptyList()) { group ->
            val intent = Intent(this, GroupChatActivity::class.java).apply {
                putExtra("EXTRA_GROUP_ID", group.id)
            }
            startActivity(intent)
        }
        rvGroups.layoutManager = LinearLayoutManager(this)
        rvGroups.adapter = groupAdapter

        btnCreateGroup.setOnClickListener {
            showCreateGroupDialog()
        }

        btnJoinGroupQr.setOnClickListener {
            showJoinGroupDialog()
        }

        loadGroups()
    }

    private fun showJoinGroupDialog() {
        val et = EditText(this).apply {
            hint = "Paste DISASTER_GRP:... or scanned QR payload"
            setPadding(32, 32, 32, 32)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("Join Civilian Mesh Group")
            .setMessage("Enter or scan the group invite QR string to import the channel key:")
            .setView(et)
            .setPositiveButton("Import Group") { _, _ ->
                val payload = et.text.toString().trim()
                if (payload.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val imported = shredder.importGroupFromQr(payload)
                        withContext(Dispatchers.Main) {
                            if (imported != null) {
                                Toast.makeText(this@MainActivity, "Joined group: ${imported.name}", Toast.LENGTH_SHORT).show()
                                loadGroups()
                            } else {
                                Toast.makeText(this@MainActivity, "Invalid group QR code", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadGroups() {
        lifecycleScope.launch(Dispatchers.IO) {
            val groups = shredder.getAllGroups()
            withContext(Dispatchers.Main) {
                groupAdapter.updateGroups(groups)
            }
        }
    }

    private fun showCreateGroupDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_group, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val etGroupName = dialogView.findViewById<EditText>(R.id.etGroupName)
        val etGroupDesc = dialogView.findViewById<EditText>(R.id.etGroupDesc)
        val swBroadcastOnly = dialogView.findViewById<Switch>(R.id.swBroadcastOnly)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelGroup)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmCreateGroup)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            val name = etGroupName.text.toString().trim()
            val desc = etGroupDesc.text.toString().trim()
            val isBroadcast = swBroadcastOnly.isChecked

            if (name.isEmpty()) {
                Toast.makeText(this, "Group name is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val key = ByteArray(32).also { secureRandom.nextBytes(it) }
            val newGroup = CivilGroup(
                id = "GRP_" + UUID.randomUUID().toString().take(12),
                name = name,
                description = if (desc.isEmpty()) "Civilian coordination group" else desc,
                secretKey = key,
                isBroadcast = isBroadcast,
                createdEpoch = System.currentTimeMillis()
            )

            lifecycleScope.launch(Dispatchers.IO) {
                shredder.createGroup(newGroup)
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "Created group: $name", Toast.LENGTH_SHORT).show()
                    loadGroups()
                }
            }
        }

        dialog.show()
    }

    private fun setupMediaTab() {
        val btnSelect = findViewById<Button>(R.id.btnSelectMediaFile)
        val btnBroadcast = findViewById<Button>(R.id.btnBroadcastMediaChunks)

        btnSelect.setOnClickListener {
            selectFileLauncher.launch("*/*")
        }

        btnBroadcast.setOnClickListener {
            broadcastSelectedMedia()
        }
    }

    private fun onMediaFileSelected(uri: Uri) {
        pendingUri = uri
        val layoutInfo = findViewById<LinearLayout>(R.id.layoutSelectedFileInfo)
        val tvName = findViewById<TextView>(R.id.tvSelectedFileName)
        val tvDetails = findViewById<TextView>(R.id.tvSelectedFileDetails)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (manifest, packages) = mediaChunker.chunkFileFromUri(uri)
                pendingManifest = manifest
                pendingPackages = packages

                withContext(Dispatchers.Main) {
                    layoutInfo.visibility = View.VISIBLE
                    tvName.text = "File: ${manifest.fileName}"
                    val kb = manifest.fileSize / 1024.0
                    tvDetails.text = String.format("Size: %.1f KB | Chunks: %d x 4KB invariant frames | Type: %s", kb, packages.size, manifest.mimeType)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed reading file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun broadcastSelectedMedia() {
        val manifest = pendingManifest ?: return
        val packages = pendingPackages ?: return
        val uri = pendingUri ?: return

        val pb = findViewById<ProgressBar>(R.id.pbChunking)
        val btnBroadcast = findViewById<Button>(R.id.btnBroadcastMediaChunks)

        pb.visibility = View.VISIBLE
        pb.max = packages.size
        pb.progress = 0
        btnBroadcast.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Save locally to cache folder
                val mediaDir = File(filesDir, "mesh_media").apply { mkdirs() }
                val localFile = File(mediaDir, manifest.fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(localFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // Ingest chunks into DB & TriPoolRouter (Pool C)
                packages.forEachIndexed { index, pkg ->
                    shredder.saveFileChunk(pkg.fileId, pkg.chunkIndex, pkg.totalChunks, pkg.chunkData)
                    router.processIngressPacket(pkg.packet4k)
                    network.disaster.management.transport.WifiMeshSupervisor.shared?.broadcastPacket(pkg.packet4k)
                    withContext(Dispatchers.Main) {
                        pb.progress = index + 1
                    }
                }

                val type = when {
                    manifest.mimeType.startsWith("image/") -> MessageType.IMAGE
                    manifest.mimeType.startsWith("video/") -> MessageType.VIDEO
                    else -> MessageType.FILE
                }

                val senderId = "Node-" + (Build.MODEL.takeLast(4).ifEmpty { "ME" })
                val feedMsg = FeedMessage(
                    id = UUID.randomUUID().toString(),
                    groupId = "GROUP_PUBLIC_BROADCAST",
                    senderName = senderId,
                    content = "Air-Gapped Media: ${manifest.fileName} (${packages.size} chunks)",
                    type = type,
                    fileName = manifest.fileName,
                    fileSize = manifest.fileSize,
                    mimeType = manifest.mimeType,
                    localFilePath = localFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    isEmergency = false
                )
                shredder.insertMessage(feedMsg)

                withContext(Dispatchers.Main) {
                    pb.visibility = View.GONE
                    btnBroadcast.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        "Successfully broadcasted ${manifest.fileName} (${packages.size} chunks) to Pool C!",
                        Toast.LENGTH_LONG
                    ).show()
                    loadBroadcastMessages()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pb.visibility = View.GONE
                    btnBroadcast.isEnabled = true
                    Toast.makeText(this@MainActivity, "Broadcast error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupRelaysTab() {
        val swWifiMesh = findViewById<Switch>(R.id.swWifiMesh)
        val btnScanWifiHotspot = findViewById<Button>(R.id.btnScanWifiHotspot)
        val btnDirectIpConnect = findViewById<Button>(R.id.btnDirectIpConnect)

        swWifiMesh?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                network.disaster.management.transport.WifiMeshSupervisor.shared?.startMesh()
                Toast.makeText(this, "Wi-Fi Hotspot Mesh Enabled", Toast.LENGTH_SHORT).show()
            } else {
                network.disaster.management.transport.WifiMeshSupervisor.shared?.stopMesh()
                Toast.makeText(this, "Wi-Fi Hotspot Mesh Disabled", Toast.LENGTH_SHORT).show()
            }
        }

        btnScanWifiHotspot?.setOnClickListener {
            network.disaster.management.transport.WifiMeshSupervisor.shared?.triggerManualHotspotScan()
            Toast.makeText(this, "Broadcasting beacons & probing hotspot peers...", Toast.LENGTH_SHORT).show()
        }

        btnDirectIpConnect?.setOnClickListener {
            val etIp = EditText(this).apply {
                hint = "e.g. 192.168.43.1 or peer IP"
                setPadding(32, 32, 32, 32)
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFF888888.toInt())
            }
            AlertDialog.Builder(this)
                .setTitle("Direct Peer IP Connection")
                .setMessage("Enter the IP address of the second phone on the hotspot:")
                .setView(etIp)
                .setPositiveButton("Connect") { _, _ ->
                    val ip = etIp.text.toString().trim()
                    if (ip.isNotEmpty()) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val addr = java.net.InetAddress.getByName(ip)
                                network.disaster.management.transport.WifiMeshSupervisor.shared?.triggerManualHotspotScan()
                                val socket = java.net.Socket()
                                socket.connect(java.net.InetSocketAddress(addr, network.disaster.management.transport.WifiMeshSupervisor.SYNC_TCP_PORT), 3000)
                                socket.close()
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "Connected to peer $ip!", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "Could not reach $ip: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val swBle = findViewById<Switch>(R.id.swBle)
        val swMic = findViewById<Switch>(R.id.swMic)
        val swCam = findViewById<Switch>(R.id.swCam)
        val btnOpenSettings = findViewById<Button>(R.id.btnOpenSettings)
        val btnPanic = findViewById<Button>(R.id.btnPanic)

        swBle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ),
                        101
                    )
                } else {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(
                            Manifest.permission.BLUETOOTH,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ),
                        101
                    )
                }
            }
        }

        swMic.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 102)
            }
        }

        swCam.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 103)
                startActivity(Intent(this, network.disaster.management.ui.OpticalFountainActivity::class.java))
            }
        }

        btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnPanic.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Emergency Low-Wear Cryptographic Shred")
                .setMessage("This will immediately overwrite all 32-byte ephemeral keys, cryptographically shredding all stored bundles across Pool A, B, and C.")
                .setPositiveButton("PURGE ALL DATA") { _, _ ->
                    shredder.panicPhysicalPurge(this)
                    loadBroadcastMessages()
                    loadGroups()
                    Toast.makeText(this, "Memory & Storage Shredded", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        updateStorageQuotaDisplay()
    }

    private fun formatPoolSize(bytes: Long): String {
        return if (bytes < 1024 * 1024) {
            String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        } else {
            String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun updateStorageQuotaDisplay() {
        lifecycleScope.launch(Dispatchers.IO) {
            val (poolA, poolB, poolC) = shredder.getStorageUsageSummary()
            val pctA = ((poolA.toDouble() / LowWearCryptoShredder.POOL_A_LIMIT_BYTES) * 100).toInt().coerceIn(0, 100)
            val pctB = ((poolB.toDouble() / LowWearCryptoShredder.POOL_B_LIMIT_BYTES) * 100).toInt().coerceIn(0, 100)
            val pctC = ((poolC.toDouble() / LowWearCryptoShredder.POOL_C_LIMIT_BYTES) * 100).toInt().coerceIn(0, 100)

            withContext(Dispatchers.Main) {
                findViewById<TextView>(R.id.tvPoolAUsage)?.text = "Pool A (Emergency): ${formatPoolSize(poolA)} / 50 MB ($pctA%)"
                findViewById<ProgressBar>(R.id.pbPoolA)?.progress = pctA

                findViewById<TextView>(R.id.tvPoolBUsage)?.text = "Pool B (Text & Feed): ${formatPoolSize(poolB)} / 250 MB ($pctB%)"
                findViewById<ProgressBar>(R.id.pbPoolB)?.progress = pctB

                findViewById<TextView>(R.id.tvPoolCUsage)?.text = "Pool C (Bulk Media): ${formatPoolSize(poolC)} / 200 MB ($pctC%)"
                findViewById<ProgressBar>(R.id.pbPoolC)?.progress = pctC
            }
        }
    }

    private var activeMediaPlayer: android.media.MediaPlayer? = null

    private fun openAttachment(msg: FeedMessage) {
        android.util.Log.i("OpenAttachment", "openAttachment tapped: id=${msg.id}, name=${msg.fileName}, path=${msg.localFilePath}")
        val candidates = listOfNotNull(
            msg.localFilePath?.let { File(it) },
            msg.fileName?.let { File(File(cacheDir, "voice_memos"), it) },
            msg.fileName?.let { File(File(filesDir, "mesh_media"), it) },
            msg.fileName?.let { File(File(filesDir, "received_media"), it) }
        )
        val file = candidates.firstOrNull { it.exists() }
        if (file == null) {
            Toast.makeText(this, "File not found in local cache: ${msg.fileName}", Toast.LENGTH_SHORT).show()
            return
        }
        android.util.Log.i("OpenAttachment", "Found file at: ${file.absolutePath}, size=${file.length()}")

        if (msg.mimeType?.startsWith("audio/") == true || msg.fileName?.endsWith(".m4a") == true || msg.fileName?.endsWith(".mp3") == true) {
            try {
                if (activeMediaPlayer?.isPlaying == true) {
                    activeMediaPlayer?.stop()
                    activeMediaPlayer?.release()
                    activeMediaPlayer = null
                    Toast.makeText(this, "Audio playback stopped", Toast.LENGTH_SHORT).show()
                    return
                }
                activeMediaPlayer = android.media.MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                    setOnCompletionListener {
                        it.release()
                        activeMediaPlayer = null
                    }
                }
                Toast.makeText(this, "▶ Playing voice memo...", Toast.LENGTH_SHORT).show()
                return
            } catch (e: Exception) {
                android.util.Log.e("AudioPlayer", "Error playing audio", e)
                Toast.makeText(this, "Audio playback error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        try {
            val contentUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, msg.mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No external handler found for file type: ${msg.mimeType}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startPeriodicRefresh() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2500)
                val messages = shredder.getMessages(null)
                val wifiPeers = network.disaster.management.transport.WifiMeshSupervisor.shared?.getActivePeerCount() ?: 0
                val peerIps = network.disaster.management.transport.WifiMeshSupervisor.shared?.getActivePeerIps() ?: emptyList()
                val myIps = network.disaster.management.transport.WifiMeshSupervisor.shared?.getLocalIps() ?: emptyList()

                withContext(Dispatchers.Main) {
                    broadcastAdapter.updateMessages(messages)
                    updateStorageQuotaDisplay()

                    val statusMsg = if (wifiPeers > 0) {
                        "Status: Wi-Fi Hotspot Active ($wifiPeers Peer${if (wifiPeers > 1) "s" else ""}: ${peerIps.joinToString(", ")}) | Quotas Enforced"
                    } else if (myIps.isNotEmpty()) {
                        "Status: Wi-Fi Mesh Ready (${myIps.first()}) | BLE Active | Searching Hotspot..."
                    } else {
                        "Status: Mesh Active (BLE & Acoustic) | Tri-Pool Quotas Enforced"
                    }
                    findViewById<TextView>(R.id.tvStatus)?.text = statusMsg

                    val wifiDetails = if (wifiPeers > 0) {
                        "Connected Peers: ${peerIps.joinToString(", ")} | Port: 42425"
                    } else if (myIps.isNotEmpty()) {
                        "Listening on: ${myIps.joinToString(", ")}:42425 | Searching hotspot..."
                    } else {
                        "Wi-Fi Disconnected | Connect to Wi-Fi or Hotspot"
                    }
                    findViewById<TextView>(R.id.tvWifiMeshDetails)?.text = wifiDetails
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val missing = getMissingPermissions()
        if (missing.isEmpty()) {
            updatePermissionBanner(false)
            startMeshServicesSafely()
        } else {
            updatePermissionBanner(true)
        }
        loadBroadcastMessages()
        loadGroups()
        updateStorageQuotaDisplay()
    }

    private fun setupPermissionBanner() {
        findViewById<Button>(R.id.btnGrantPermissions)?.setOnClickListener {
            permissionLauncher.launch(getRequiredPermissions())
        }

        findViewById<Button>(R.id.btnOpenAppSettings)?.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun updatePermissionBanner(show: Boolean) {
        findViewById<View>(R.id.cardPermissionBanner)?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun checkAndRequestAllPermissions() {
        val missing = getMissingPermissions()
        if (missing.isEmpty()) {
            updatePermissionBanner(false)
            startMeshServicesSafely()
        } else {
            updatePermissionBanner(true)
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasCriticalPermissions(): Boolean {
        val fineLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fineLoc || coarseLoc
    }

    private fun getMissingPermissions(): List<String> {
        val perms = mutableListOf<String>()
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        perms.add(Manifest.permission.RECORD_AUDIO)
        perms.add(Manifest.permission.CAMERA)

        return perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        perms.add(Manifest.permission.RECORD_AUDIO)
        perms.add(Manifest.permission.CAMERA)
        return perms.toTypedArray()
    }

    private var meshServicesStarted = false
    private fun startMeshServicesSafely() {
        if (meshServicesStarted) return
        meshServicesStarted = true
        try {
            MeshForegroundService.start(this)
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        activeMediaPlayer?.release()
        activeMediaPlayer = null
        if (sessionKeyBufferAddress != 0L) {
            memoryLock.wipeAndFree(sessionKeyBufferAddress, 4096)
            sessionKeyBufferAddress = 0L
        }
    }
}
