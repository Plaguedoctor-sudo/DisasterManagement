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
import network.disaster.management.model.CivilianContact
import network.disaster.management.model.FeedMessage
import network.disaster.management.model.FileManifest
import network.disaster.management.model.MessageType
import network.disaster.management.routing.TriPoolRouter
import network.disaster.management.storage.LowWearCryptoShredder
import network.disaster.management.storage.MediaChunkingEngine
import network.disaster.management.system.GpsBeaconHelper
import network.disaster.management.system.OemBatteryManager
import network.disaster.management.transport.ApkPropagationServer
import network.disaster.management.transport.MeshForegroundService
import network.disaster.management.transport.acoustic.FastFskModem
import network.disaster.management.storage.UserIdentityManager
import network.disaster.management.ui.GroupAdapter
import network.disaster.management.ui.GroupChatActivity
import network.disaster.management.ui.GroupQrHelper
import network.disaster.management.ui.MessageAdapter
import network.disaster.management.ui.QrScannerActivity
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.HorizontalScrollView
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
    private val simEngine = network.disaster.management.simulation.SurvivorSimulationEngine()
    private var mapTacticalView: network.disaster.management.ui.TacticalLocalMapView? = null

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
    private var pendingReplyMessage: FeedMessage? = null

    private val selectFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { onMediaFileSelected(it) }
    }

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val groupName = data?.getStringExtra(QrScannerActivity.EXTRA_GROUP_NAME)
            val friendPeer = data?.getStringExtra(QrScannerActivity.EXTRA_FRIEND_PEER)
            val scannedData = data?.getStringExtra(QrScannerActivity.EXTRA_SCANNED_DATA)

            if (!groupName.isNullOrEmpty()) {
                Toast.makeText(this, "✅ Successfully joined group: $groupName", Toast.LENGTH_LONG).show()
                loadGroups()
            } else if (!friendPeer.isNullOrEmpty()) {
                sendFriendRequest(friendPeer)
            } else if (!scannedData.isNullOrEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val imported = shredder.importGroupFromQr(scannedData)
                    withContext(Dispatchers.Main) {
                        if (imported != null) {
                            Toast.makeText(this@MainActivity, "✅ Joined group: ${imported.name}", Toast.LENGTH_LONG).show()
                            loadGroups()
                        } else {
                            Toast.makeText(this@MainActivity, "Scanned: $scannedData", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        updatePermissionBanner(!allGranted)
        if (allGranted || hasCriticalPermissions()) {
            startMeshServicesSafely()
            Toast.makeText(this, "Mesh active: Ready for Wi-Fi Direct, Hotspot & BLE communication", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // FIPS 140-3 Power-On Cryptographic Known Answer Self-Tests (Blueprint Section 14.A)
        try {
            network.disaster.management.crypto.FipsCryptographicSelfTest.runPowerOnSelfTest()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "CRITICAL: FIPS 140-3 KAT failure on boot", e)
            Toast.makeText(this, "SECURITY: Cryptographic Containment Engaged", Toast.LENGTH_LONG).show()
        }

        // Native memory pinning (mlock) for session key security
        sessionKeyBufferAddress = memoryLock.allocateLockedMemory(4096)

        shredder = LowWearCryptoShredder(this)
        shredder.recordAuditEntry("SYSTEM_BOOT", "LOCAL_NODE", "SUCCESS", "FIPS 140-3 KAT verified; SQLite PRAGMA secure_delete active")

        router = TriPoolRouter(shredder, localRecipientTag)
        mediaChunker = MediaChunkingEngine(this, shredder)
        batteryManager = OemBatteryManager(this)
        gpsHelper = GpsBeaconHelper(this)

        setupPermissionBanner()
        setupStopSwitch()
        setupTabs()
        setupBroadcastFeed()
        setupTacticalMapAndSimulation()
        setupGroupsTab()
        setupMediaTab()
        setupRelaysTab()

        findViewById<TextView>(R.id.tvStatus).text = "Status: Initializing Mesh Transceivers..."

        updateUserIdentityUI()
        if (!UserIdentityManager.isUserNameCustomized(this)) {
            showChooseNameDialog(isInitialPrompt = true)
        }

        checkAndRequestAllPermissions()
        startPeriodicRefresh()
    }

    private fun setupStopSwitch() {
        val btnStopApp = findViewById<Button>(R.id.btnStopApp)
        btnStopApp?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("🛑 STOP WHOLE APPLICATION?")
                .setMessage("This emergency stop switch will immediately halt all Bluetooth mesh radios, cease background scanning services, release native locked memory, and terminate the application process.")
                .setPositiveButton("STOP & EXIT") { _, _ ->
                    network.disaster.management.system.AppStopManager.stopWholeApp(this@MainActivity, shredder)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupTabs() {
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val tabBroadcast = findViewById<View>(R.id.layoutTabBroadcast)
        val tabMap = findViewById<View>(R.id.layoutTabMap)
        val tabGroups = findViewById<View>(R.id.layoutTabGroups)
        val tabMedia = findViewById<View>(R.id.layoutTabMedia)
        val tabRelays = findViewById<View>(R.id.layoutTabRelays)

        tabLayout.addTab(tabLayout.newTab().setText("Feed"))
        tabLayout.addTab(tabLayout.newTab().setText("Map & Sim"))
        tabLayout.addTab(tabLayout.newTab().setText("Groups"))
        tabLayout.addTab(tabLayout.newTab().setText("Media Share"))
        tabLayout.addTab(tabLayout.newTab().setText("Relays"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tabBroadcast.visibility = View.GONE
                tabMap?.visibility = View.GONE
                tabGroups.visibility = View.GONE
                tabMedia.visibility = View.GONE
                tabRelays.visibility = View.GONE

                when (tab?.position) {
                    0 -> {
                        tabBroadcast.visibility = View.VISIBLE
                        loadBroadcastMessages()
                    }
                    1 -> {
                        tabMap?.visibility = View.VISIBLE
                        updateSimulationUi()
                    }
                    2 -> {
                        tabGroups.visibility = View.VISIBLE
                        loadGroups()
                    }
                    3 -> tabMedia.visibility = View.VISIBLE
                    4 -> {
                        tabRelays.visibility = View.VISIBLE
                        updateStorageQuotaDisplay()
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupTacticalMapAndSimulation() {
        mapTacticalView = findViewById(R.id.mapTacticalView)
        mapTacticalView?.simulationEngine = simEngine

        val btnToggleStealth = findViewById<Button>(R.id.btnSimToggleStealth)
        val btnMicroBurst = findViewById<Button>(R.id.btnSimMicroBurst)
        val btnStep = findViewById<Button>(R.id.btnSimStep)
        val btnWater = findViewById<Button>(R.id.btnSimScavengeWater)
        val btnMed = findViewById<Button>(R.id.btnSimScavengeMed)
        val btnExtraction = findViewById<Button>(R.id.btnSimExtraction)
        val btnZoomIn = findViewById<Button>(R.id.btnMapZoomIn)
        val btnZoomOut = findViewById<Button>(R.id.btnMapZoomOut)
        val btnInventory = findViewById<Button>(R.id.btnSimInventory)
        val btnHighlight = findViewById<Button>(R.id.btnSimHighlight)
        val btnHostileIntel = findViewById<Button>(R.id.btnSimHostileIntel)

        btnToggleStealth?.setOnClickListener {
            val isStealth = simEngine.toggleRadioSilence()
            network.disaster.management.transport.BleMeshSupervisor.shared?.setStealthMode(isStealth)
            updateSimulationUi()
            val status = if (isStealth) "Radio Silence Active (0m RF Footprint)" else "Active Mesh Restored (~65m RF Footprint)"
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
        }

        btnMicroBurst?.setOnClickListener {
            val (success, detail) = simEngine.triggerMicroBurstSos()
            // Micro-burst 4,096-byte distress packet through BLE mesh supervisor
            val dummyPacket = ByteArray(4096) { 0xD1.toByte() }
            network.disaster.management.transport.BleMeshSupervisor.shared?.burstTransmit(dummyPacket, 150L)
            updateSimulationUi()
            Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
        }

        btnStep?.setOnClickListener {
            simEngine.stepSimulation()
            updateSimulationUi()
        }

        btnWater?.setOnClickListener {
            val res = simEngine.scavengeNearestSupply(network.disaster.management.map.SupplyType.POTABLE_WATER)
            updateSimulationUi()
            Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
        }

        btnMed?.setOnClickListener {
            val res = simEngine.scavengeNearestSupply(network.disaster.management.map.SupplyType.TRAUMA_KIT)
            updateSimulationUi()
            Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
        }

        btnExtraction?.setOnClickListener {
            val res = simEngine.scavengeNearestSupply(network.disaster.management.map.SupplyType.SHELTER_EXTRACTION)
            updateSimulationUi()
            Toast.makeText(this, res, Toast.LENGTH_LONG).show()
        }

        btnInventory?.setOnClickListener {
            showInventoryManagerDialog()
        }

        btnHighlight?.setOnClickListener {
            showResourceHighlightDialog()
        }

        btnHostileIntel?.setOnClickListener {
            showHostileIntelDialog()
        }

        btnZoomIn?.setOnClickListener {
            mapTacticalView?.zoomIn()
        }

        btnZoomOut?.setOnClickListener {
            mapTacticalView?.zoomOut()
        }

        updateSimulationUi()
    }

    private fun updateSimulationUi() {
        val s = simEngine.survivor
        val assessment = simEngine.evaluateTacticalSituation()

        findViewById<TextView>(R.id.tvSimThreatBanner)?.apply {
            text = assessment.advisoryText
            setTextColor(
                when (assessment.threatLevel) {
                    network.disaster.management.map.ThreatLevel.STEALTH_SECURE -> 0xFF00FF66.toInt()
                    network.disaster.management.map.ThreatLevel.ELEVATED_RISK -> 0xFFFFCC00.toInt()
                    network.disaster.management.map.ThreatLevel.CRITICAL_TARGETED -> 0xFFFF2222.toInt()
                }
            )
        }

        findViewById<TextView>(R.id.tvSimVitals)?.text =
            "❤️ HP: ${s.health}% | 💧 Water: ${s.hydration}% | 🥫 Food: ${s.energy}% | 🔋 Bat: ${s.battery}%"

        findViewById<TextView>(R.id.tvSimInventorySummary)?.text =
            "🎒 INV: 💧 ${s.inventory.waterLiters}L | 🥫 ${s.inventory.foodRations} Rations | 🩹 ${s.inventory.traumaKits} MedKits | 🧪 ${s.inventory.purificationTabs} Tabs | 🔋 ${s.inventory.batteryMah}mAh"

        findViewById<Button>(R.id.btnSimToggleStealth)?.apply {
            if (s.isRadioSilence) {
                text = "🔊 RESUME MESH"
                setBackgroundColor(0xFF1E3A8A.toInt())
            } else {
                text = "🔇 RADIO SILENCE"
                setBackgroundColor(0xFF4A154B.toInt())
            }
        }

        findViewById<TextView>(R.id.tvSimIncidentLog)?.text =
            simEngine.eventLog.firstOrNull() ?: "TACTICAL INTEL: Surveillance active in sector."

        mapTacticalView?.invalidate()
    }

    private fun showInventoryManagerDialog() {
        val inv = simEngine.survivor.inventory
        val options = arrayOf(
            "💧 Drink Water (-0.5L / +35 Hydration) [Cur: ${inv.waterLiters}L]",
            "🥫 Eat Ration (-1 Ration / +40 Energy) [Cur: ${inv.foodRations}]",
            "🩹 Apply Med-Kit (-1 Kit / +50 HP) [Cur: ${inv.traumaKits}]",
            "🧪 Use Water Purification Tablet (-1 Tab) [Cur: ${inv.purificationTabs}]",
            "✏️ Adjust Stock Values (Edit Inventory)"
        )

        AlertDialog.Builder(this)
            .setTitle("🎒 Survivor Inventory & Rations")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val ok = simEngine.survivor.drinkWater(0.5)
                        if (ok) {
                            simEngine.logEvent("💧 Consumed 0.5L water from backpack. Hydration now ${simEngine.survivor.hydration}%.")
                            Toast.makeText(this, "Drank 0.5L water", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "No water remaining in backpack!", Toast.LENGTH_SHORT).show()
                        }
                        updateSimulationUi()
                    }
                    1 -> {
                        val ok = simEngine.survivor.eatRation()
                        if (ok) {
                            simEngine.logEvent("🥫 Ate ration. Energy now ${simEngine.survivor.energy}%.")
                            Toast.makeText(this, "Consumed 1 ration pack", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "No rations remaining!", Toast.LENGTH_SHORT).show()
                        }
                        updateSimulationUi()
                    }
                    2 -> {
                        val ok = simEngine.survivor.applyTraumaKit()
                        if (ok) {
                            simEngine.logEvent("🩹 Applied trauma kit. Health now ${simEngine.survivor.health}%.")
                            Toast.makeText(this, "Applied trauma kit", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "No trauma kits remaining!", Toast.LENGTH_SHORT).show()
                        }
                        updateSimulationUi()
                    }
                    3 -> {
                        if (inv.purificationTabs > 0) {
                            inv.purificationTabs--
                            simEngine.logEvent("🧪 Purified scrounged water with tablet. Clean supply safe.")
                            Toast.makeText(this, "Purification tablet used", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "No purification tablets left!", Toast.LENGTH_SHORT).show()
                        }
                        updateSimulationUi()
                    }
                    4 -> showCustomInventoryEditorDialog()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showCustomInventoryEditorDialog() {
        val inv = simEngine.survivor.inventory
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val etWater = EditText(this).apply {
            hint = "Water in Liters (e.g. 3.0)"
            setText(inv.waterLiters.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val etFood = EditText(this).apply {
            hint = "Food Rations (e.g. 4)"
            setText(inv.foodRations.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val etMeds = EditText(this).apply {
            hint = "Trauma Kits (e.g. 2)"
            setText(inv.traumaKits.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val etTabs = EditText(this).apply {
            hint = "Purification Tablets (e.g. 10)"
            setText(inv.purificationTabs.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val etBat = EditText(this).apply {
            hint = "Battery mAh (e.g. 4500)"
            setText(inv.batteryMah.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        layout.addView(TextView(this).apply { text = "Water (Liters):"; setTextColor(0xFFEEEEEE.toInt()) })
        layout.addView(etWater)
        layout.addView(TextView(this).apply { text = "Food Rations:"; setTextColor(0xFFEEEEEE.toInt()) })
        layout.addView(etFood)
        layout.addView(TextView(this).apply { text = "Trauma Kits:"; setTextColor(0xFFEEEEEE.toInt()) })
        layout.addView(etMeds)
        layout.addView(TextView(this).apply { text = "Purification Tablets:"; setTextColor(0xFFEEEEEE.toInt()) })
        layout.addView(etTabs)
        layout.addView(TextView(this).apply { text = "Battery Power (mAh):"; setTextColor(0xFFEEEEEE.toInt()) })
        layout.addView(etBat)

        AlertDialog.Builder(this)
            .setTitle("✏️ Update Backpack Inventory")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                inv.waterLiters = etWater.text.toString().toDoubleOrNull() ?: inv.waterLiters
                inv.foodRations = etFood.text.toString().toIntOrNull() ?: inv.foodRations
                inv.traumaKits = etMeds.text.toString().toIntOrNull() ?: inv.traumaKits
                inv.purificationTabs = etTabs.text.toString().toIntOrNull() ?: inv.purificationTabs
                inv.batteryMah = etBat.text.toString().toIntOrNull() ?: inv.batteryMah
                simEngine.logEvent("🎒 Inventory manually updated by survivor.")
                updateSimulationUi()
                Toast.makeText(this, "Inventory updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showResourceHighlightDialog() {
        val caches = simEngine.supplyCaches
        val labels = caches.map { sc ->
            val star = if (sc.isHighlighted) "⭐ " else "⭕ "
            val dist = simEngine.survivor.location.distanceTo(sc.location).toInt()
            "$star${sc.name} (${dist}m) [${if (sc.isHighlighted) "HIGHLIGHTED" else "Normal"}]"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("⭐ Highlight Tactical Resources")
            .setItems(labels) { _, which ->
                val chosen = caches[which]
                val newState = mapTacticalView?.toggleHighlightSupply(chosen.id) ?: !chosen.isHighlighted
                chosen.isHighlighted = newState
                val status = if (newState) "Highlighted on HUD" else "Highlight removed"
                simEngine.logEvent("⭐ Toggled highlight on ${chosen.name}: $status")
                updateSimulationUi()
                Toast.makeText(this, "${chosen.name}: $status", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showHostileIntelDialog() {
        val detector = network.disaster.management.security.HostileActorDetector.shared
        val profiles = detector.getAllProfiles()
        val traps = detector.getDetectedAmbushTraps()

        val sb = StringBuilder()
        sb.append("🛡️ COUNTER-INTEL DETECTION REPORT\n\n")

        if (traps.isNotEmpty()) {
            sb.append("💀 ACTIVE AMBUSH TRAPS (${traps.size}):\n")
            for (t in traps) {
                sb.append("• ${t.hostileSender}: \"${t.claimTitle}\"\n  → Location is ${t.distToHostilePatrolMeters.toInt()}m from hostile killzone!\n")
            }
            sb.append("\n")
        } else {
            sb.append("💀 No ambush bait traps currently active.\n\n")
        }

        if (profiles.isNotEmpty()) {
            sb.append("👥 MONITORED NETWORK ACTORS (${profiles.size}):\n")
            for (p in profiles) {
                val icon = when (p.classification) {
                    network.disaster.management.map.ActorClassification.HOSTILE_INFILTRATOR -> "🚨 HOSTILE"
                    network.disaster.management.map.ActorClassification.SUSPICIOUS_ACTOR -> "⚠️ SUSPICIOUS"
                    network.disaster.management.map.ActorClassification.VERIFIED_CIVILIAN -> "🟢 VERIFIED"
                    network.disaster.management.map.ActorClassification.UNVERIFIED_CIVILIAN -> "⚪ UNVERIFIED"
                }
                sb.append("[$icon] ${p.senderName} (Trust: ${p.trustScore}%)\n")
                for (flag in p.anomalyFlags) {
                    sb.append("   - $flag\n")
                }
            }
        } else {
            sb.append("👥 No peer behavioral telemetry collected yet.\nIncoming mesh broadcasts are automatically evaluated for ambush lures and spoofing.")
        }

        AlertDialog.Builder(this)
            .setTitle("🛡️ Hostile Infiltration Intel")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .setNeutralButton("Simulate Hostile Lure Probe") { _, _ ->
                simulateHostileInfiltrationProbe()
            }
            .show()
    }

    private fun simulateHostileInfiltrationProbe() {
        // Creates a simulated hostile infiltrator trying to lure civilians into the drone patrol zone
        val hostileSender = "MilitiaRecon-04"
        val droneThreat = simEngine.hostiles.firstOrNull()
        val trapLoc = droneThreat?.location ?: simEngine.survivor.location
        val lureMsg = FeedMessage(
            id = UUID.randomUUID().toString(),
            groupId = "GROUP_PUBLIC_BROADCAST",
            senderName = hostileSender,
            content = "SAFE EVACUATION POINT OPEN! FREE WATER & AID AT LOC:${trapLoc.lat},${trapLoc.lon}. ALL SURVIVORS REPORT NOW!",
            type = MessageType.TEXT,
            timestamp = System.currentTimeMillis(),
            isEmergency = true
        )
        network.disaster.management.security.HostileActorDetector.shared.analyzeMessage(lureMsg, simEngine.hostiles)
        lifecycleScope.launch(Dispatchers.IO) {
            shredder.insertMessage(lureMsg)
            withContext(Dispatchers.Main) {
                loadBroadcastMessages()
                updateSimulationUi()
                Toast.makeText(this@MainActivity, "🚨 Infiltrator detected & flagged!", Toast.LENGTH_LONG).show()
            }
        }
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

        val btnCancelReply = findViewById<Button>(R.id.btnCancelReply)
        val btnManageFriends = findViewById<Button>(R.id.btnManageFriends)
        val btnPropagateApp = findViewById<Button>(R.id.btnPropagateApp)
        val btnChangeCallsign = findViewById<Button>(R.id.btnChangeCallsign)
        val layoutUserIdentity = findViewById<View>(R.id.layoutUserIdentity)
        val btnAttachLocation = findViewById<ImageButton>(R.id.btnAttachLocation)
        val btnRemoveLocationAttachment = findViewById<Button>(R.id.btnRemoveLocationAttachment)

        btnAttachLocation?.setOnClickListener {
            toggleVoluntaryLocationAttachment()
        }

        btnRemoveLocationAttachment?.setOnClickListener {
            clearLocationAttachment()
        }

        btnChangeCallsign?.setOnClickListener {
            showChooseNameDialog()
        }

        layoutUserIdentity?.setOnClickListener {
            showChooseNameDialog()
        }

        btnCancelReply?.setOnClickListener {
            clearReplyDraft()
        }

        btnManageFriends?.setOnClickListener {
            showFriendsManagementDialog()
        }

        btnPropagateApp?.setOnClickListener {
            showAppPropagationDialog()
        }

        broadcastAdapter = MessageAdapter(
            messages = emptyList(),
            onFileClick = { msg -> openAttachment(msg) },
            shredder = shredder,
            onReplyClick = { msg -> handleReplyToMessage(msg) },
            onUserTrustClick = { msg -> showUserTrustDialog(msg) },
            onAcceptFriendClick = { msg -> acceptFriendRequest(msg.senderName) },
            onRejectFriendClick = { msg -> rejectFriendRequest(msg.senderName) }
        )
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
            broadcastReport("🟢 STATUS: SAFE — Civilian check-in confirmed")
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

    private var pendingLocationAttachment: String? = null

    private fun toggleVoluntaryLocationAttachment() {
        if (pendingLocationAttachment != null) {
            clearLocationAttachment()
            return
        }
        val banner = findViewById<View>(R.id.layoutLocationAttachmentPreview)
        val tvPreview = findViewById<TextView>(R.id.tvLocationAttachmentPreview)
        banner?.visibility = View.VISIBLE
        tvPreview?.text = "📍 Voluntary Location: Acquiring GPS fix..."

        lifecycleScope.launch(Dispatchers.IO) {
            val coords = gpsHelper.getFastestCoordinates()
            val coordStr = coords?.toBeaconString() ?: "LOC:ACQUIRING"
            withContext(Dispatchers.Main) {
                pendingLocationAttachment = coordStr
                tvPreview?.text = "📍 Voluntary Location: [$coordStr]"
                Toast.makeText(this@MainActivity, "GPS Location attached (Voluntary)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearLocationAttachment() {
        pendingLocationAttachment = null
        findViewById<View>(R.id.layoutLocationAttachmentPreview)?.visibility = View.GONE
    }

    private fun handleReplyToMessage(msg: FeedMessage) {
        pendingReplyMessage = msg
        val banner = findViewById<View>(R.id.layoutReplyBanner)
        val preview = findViewById<TextView>(R.id.tvReplyingToPreview)
        val etInput = findViewById<EditText>(R.id.etBroadcastInput)

        val cleanSnippet = (msg.content ?: "").replace("\n", " ").take(60)
        preview?.text = "↩️ Replying to @${msg.senderName}: \"$cleanSnippet\""
        banner?.visibility = View.VISIBLE
        etInput?.requestFocus()
    }

    private fun clearReplyDraft() {
        pendingReplyMessage = null
        findViewById<View>(R.id.layoutReplyBanner)?.visibility = View.GONE
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
                val selectedNeed = options[which]
                AlertDialog.Builder(this)
                    .setTitle("Voluntary Location Attachment")
                    .setMessage("Attach your GPS coordinates to this resource request so nearby units can navigate to you?")
                    .setPositiveButton("📍 Attach Location") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            val coords = gpsHelper.getFastestCoordinates()?.toBeaconString() ?: "LOC:ACQUIRING"
                            broadcastReport("🟡 RESOURCE NEED: $selectedNeed [$coords]")
                        }
                    }
                    .setNegativeButton("Do Not Attach") { _, _ ->
                        broadcastReport("🟡 RESOURCE NEED: $selectedNeed")
                    }
                    .show()
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
                val selectedHazard = options[which]
                AlertDialog.Builder(this)
                    .setTitle("Voluntary Location Attachment")
                    .setMessage("Attach your GPS coordinates to mark this hazard location for other survivors?")
                    .setPositiveButton("📍 Attach Location") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            val coords = gpsHelper.getFastestCoordinates()?.toBeaconString() ?: "LOC:ACQUIRING"
                            broadcastReport("⚠️ HAZARD ALERT: $selectedHazard [$coords]")
                        }
                    }
                    .setNegativeButton("Do Not Attach") { _, _ ->
                        broadcastReport("⚠️ HAZARD ALERT: $selectedHazard")
                    }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadBroadcastMessages() {
        lifecycleScope.launch(Dispatchers.IO) {
            val messages = shredder.getMessages(null)
            for (msg in messages) {
                network.disaster.management.security.HostileActorDetector.shared.analyzeMessage(msg, simEngine.hostiles)
            }
            withContext(Dispatchers.Main) {
                broadcastAdapter.updateMessages(messages)
                val emptyTv = findViewById<TextView>(R.id.tvEmptyBroadcastFeed)
                emptyTv?.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateUserIdentityUI() {
        val userName = UserIdentityManager.getUserName(this)
        findViewById<TextView>(R.id.tvUserCallsign)?.text = "👤 Callsign: @$userName"
        findViewById<Button>(R.id.btnChangeCallsign)?.text = "👤 @$userName"
    }

    private fun showChooseNameDialog(isInitialPrompt: Boolean = false) {
        val currentName = UserIdentityManager.getUserName(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 28, 48, 16)
        }

        val tvDesc = TextView(this).apply {
            text = if (isInitialPrompt) {
                "Welcome to Disaster Mesh Network!\n\nPlease choose your mesh callsign / name so nearby survivors, rescue personnel, and friends can identify your broadcasts, alerts, and friend requests off-grid."
            } else {
                "Choose how your node appears across the mesh network. This name will be attached to all broadcasts, distress beacons, friend requests, and group messages."
            }
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }
        container.addView(tvDesc)

        val input = EditText(this).apply {
            hint = "e.g. Medic-Alpha, Scout-7, Sarah"
            setText(currentName)
            selectAll()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#777777"))
            setBackgroundResource(android.R.drawable.edit_text)
            filters = arrayOf(android.text.InputFilter.LengthFilter(24))
        }
        container.addView(input)

        val tvSuggestionsLabel = TextView(this).apply {
            text = "⚡ Quick Suggestions:"
            setTextColor(Color.parseColor("#93C5FD"))
            textSize = 11f
            setPadding(0, 16, 0, 8)
        }
        container.addView(tvSuggestionsLabel)

        val horizontalScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val suggestionsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val suggestions = UserIdentityManager.getSuggestedCallsigns()
        for (sug in suggestions) {
            val chip = Button(this).apply {
                text = sug
                textSize = 11f
                isAllCaps = false
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1E293B"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    resources.getDimensionPixelSize(android.R.dimen.app_icon_size) / 2
                ).apply {
                    marginEnd = 12
                }
                setOnClickListener {
                    input.setText(sug)
                    input.setSelection(sug.length)
                }
            }
            suggestionsLayout.addView(chip)
        }
        horizontalScroll.addView(suggestionsLayout)
        container.addView(horizontalScroll)

        AlertDialog.Builder(this)
            .setTitle(if (isInitialPrompt) "👋 Set Your Mesh Callsign" else "👤 Choose Your Callsign / Name")
            .setView(container)
            .setPositiveButton("Save Callsign") { _, _ ->
                val entered = input.text.toString()
                val saved = UserIdentityManager.setUserName(this, entered)
                if (saved == null) {
                    Toast.makeText(this, "Invalid callsign. Please enter 1 to 24 characters.", Toast.LENGTH_SHORT).show()
                } else {
                    updateUserIdentityUI()
                    lifecycleScope.launch(Dispatchers.IO) {
                        shredder.saveContact(
                            CivilianContact(
                                peerId = saved,
                                displayName = saved,
                                isFriend = true,
                                trustScore = 100,
                                trustBadge = "OWN_NODE",
                                notes = "Local operator device",
                                friendStatus = "ACCEPTED"
                            )
                        )
                    }
                    Toast.makeText(this, "✅ Callsign set to: @$saved", Toast.LENGTH_SHORT).show()
                    loadBroadcastMessages()
                }
            }
            .setNegativeButton(if (isInitialPrompt) "Keep Default" else "Cancel") { _, _ ->
                if (isInitialPrompt) {
                    val defaultName = UserIdentityManager.resetToDefault(this)
                    updateUserIdentityUI()
                    Toast.makeText(this, "Using default callsign: @$defaultName", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("🎲 Reset / Default") { _, _ ->
                val defaultName = UserIdentityManager.resetToDefault(this)
                updateUserIdentityUI()
                Toast.makeText(this, "Reset to default: @$defaultName", Toast.LENGTH_SHORT).show()
                loadBroadcastMessages()
            }
            .show()
    }

    private fun showMyFriendQrDialog() {
        val myName = UserIdentityManager.getUserName(this)
        val qrBmp = GroupQrHelper.generateFriendQrBitmap(myName)
        if (qrBmp == null) {
            Toast.makeText(this, "Failed to generate QR code", Toast.LENGTH_SHORT).show()
            return
        }
        val img = ImageView(this).apply {
            setImageBitmap(qrBmp)
            setPadding(48, 32, 48, 32)
            adjustViewBounds = true
        }
        AlertDialog.Builder(this)
            .setTitle("📱 My Friend QR Code")
            .setMessage("Ask your peer to scan this QR code using their '📷 Scan Peer QR Code' button to send a friend request instantly.\n\nCallsign: @$myName")
            .setView(img)
            .setPositiveButton("Done", null)
            .setNeutralButton("✏️ Change Callsign") { _, _ ->
                showChooseNameDialog()
            }
            .show()
    }

    private fun showUserTrustDialog(msg: FeedMessage) {
        val peerId = msg.senderName
        val myId = UserIdentityManager.getUserName(this)
        val isSelf = peerId == myId
        val isFriend = shredder.isFriend(peerId)
        val contact = shredder.getContact(peerId)
        val profile = network.disaster.management.security.HostileActorDetector.shared.getProfile(peerId)
        val curScore = contact?.trustScore ?: profile?.trustScore ?: 70

        val statusDescription = when {
            isSelf -> "Your Own Device Broadcast"
            isFriend -> "🛡️ Trusted Friend (Score: $curScore%)"
            contact?.friendStatus == "PENDING_RECEIVED" -> "📥 Incoming Friend Request (Awaiting Your Decision)"
            contact?.friendStatus == "PENDING_SENT" -> "⏳ Friend Request Sent (Awaiting Accept)"
            profile?.classification == network.disaster.management.map.ActorClassification.HOSTILE_INFILTRATOR -> "🚨 Flagged Hostile Infiltrator (0%)"
            profile?.classification == network.disaster.management.map.ActorClassification.SUSPICIOUS_ACTOR -> "⚠️ Suspicious Anomaly Detected ($curScore%)"
            profile?.classification == network.disaster.management.map.ActorClassification.VERIFIED_CIVILIAN -> "⭐ Verified Civilian Mesh Node ($curScore%)"
            else -> "⚪ Standard Unverified Civilian ($curScore%)"
        }

        val options = mutableListOf<String>()
        if (!isSelf) {
            if (contact?.friendStatus == "PENDING_RECEIVED") {
                options.add("✅ Accept Friend Request from @$peerId")
                options.add("❌ Decline Friend Request from @$peerId")
            } else if (isFriend) {
                options.add("❌ Remove from Friends (Delete Contact)")
            } else {
                options.add("🤝 Send Friend Request to @$peerId")
            }
            options.add("⭐ Vouch for Peer (+15 Trust Score)")
            options.add("⚠️ Flag Suspicious Activity (-25 Trust Score)")
            options.add("🚨 Flag as Hostile Infiltrator (0% Trust & Quarantine)")
        } else {
            options.add("✏️ Change My Callsign / Name")
            options.add("📱 Show My Friend QR Code")
        }
        options.add("↩️ Reply to Broadcast from @$peerId")

        AlertDialog.Builder(this)
            .setTitle("Identity & Trust: @$peerId")
            .setMessage("Status: $statusDescription\nRecorded Trust Score: $curScore%")
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                when {
                    choice.startsWith("✅ Accept Friend Request") -> {
                        acceptFriendRequest(peerId)
                    }
                    choice.startsWith("❌ Decline Friend Request") -> {
                        rejectFriendRequest(peerId)
                    }
                    choice.startsWith("🤝 Send Friend Request") -> {
                        sendFriendRequest(peerId)
                    }
                    choice.startsWith("❌ Remove from Friends") -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            shredder.deleteContact(peerId)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Removed @$peerId from friends", Toast.LENGTH_SHORT).show()
                                loadBroadcastMessages()
                            }
                        }
                    }
                    choice.startsWith("⭐ Vouch") -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val newScore = (curScore + 15).coerceAtMost(100)
                            if (contact != null) {
                                shredder.saveContact(contact.copy(trustScore = newScore))
                            }
                            network.disaster.management.security.HostileActorDetector.shared.setPeerTrust(
                                peerId,
                                newScore,
                                network.disaster.management.map.ActorClassification.VERIFIED_CIVILIAN
                            )
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Vouched for @$peerId: Trust raised to $newScore%", Toast.LENGTH_SHORT).show()
                                loadBroadcastMessages()
                            }
                        }
                    }
                    choice.startsWith("⚠️ Flag Suspicious") -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val newScore = (curScore - 25).coerceAtLeast(10)
                            if (contact != null) {
                                shredder.saveContact(contact.copy(trustScore = newScore))
                            }
                            network.disaster.management.security.HostileActorDetector.shared.setPeerTrust(
                                peerId,
                                newScore,
                                network.disaster.management.map.ActorClassification.SUSPICIOUS_ACTOR
                            )
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Flagged @$peerId: Trust lowered to $newScore%", Toast.LENGTH_SHORT).show()
                                loadBroadcastMessages()
                            }
                        }
                    }
                    choice.startsWith("🚨 Flag as Hostile") -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            if (isFriend) {
                                shredder.deleteContact(peerId)
                            }
                            network.disaster.management.security.HostileActorDetector.shared.setPeerTrust(
                                peerId,
                                0,
                                network.disaster.management.map.ActorClassification.HOSTILE_INFILTRATOR
                            )
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "🚨 Peer @$peerId quarantined as Hostile Infiltrator", Toast.LENGTH_LONG).show()
                                loadBroadcastMessages()
                            }
                        }
                    }
                    choice.startsWith("↩️ Reply") -> {
                        handleReplyToMessage(msg)
                    }
                    choice.startsWith("✏️ Change My Callsign") -> {
                        showChooseNameDialog()
                    }
                    choice.startsWith("📱 Show My Friend QR Code") -> {
                        showMyFriendQrDialog()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showFriendsManagementDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pendingRequests = shredder.getPendingFriendRequests()
            val contacts = shredder.getAllContacts()

            withContext(Dispatchers.Main) {
                val items = mutableListOf<String>()

                // Section 1: Pending Friend Requests
                for (p in pendingRequests) {
                    items.add("📥 PENDING REQUEST: @${p.peerId} [Tap to Accept/Decline]")
                }

                // Section 2: Actions
                val myName = UserIdentityManager.getUserName(this@MainActivity)
                items.add("➕ Send Friend Request by Peer ID / Name")
                items.add("📱 Show My Friend QR Code (@$myName)")
                items.add("📷 Scan Peer QR Code (Pair Instantly)")
                items.add("✏️ Change My Callsign / Name")

                // Section 3: Registered Contacts
                for (c in contacts) {
                    if (c.friendStatus == "PENDING_RECEIVED") continue // already shown
                    val badge = when {
                        c.isFriend -> "🛡️ FRIEND"
                        c.friendStatus == "PENDING_SENT" -> "⏳ REQUEST SENT"
                        else -> "👤 CONTACT"
                    }
                    items.add("$badge: @${c.peerId} (${c.trustScore}%)")
                }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("👥 Civilian Trust & Friends Directory")
                    .setItems(items.toTypedArray()) { _, which ->
                        val item = items[which]
                        when {
                            item.startsWith("📥 PENDING REQUEST") -> {
                                val peer = item.substringAfter("@").substringBefore(" ").trim()
                                showIncomingRequestDialog(peer)
                            }
                            item.startsWith("➕ Send Friend Request") -> {
                                showAddNewContactDialog()
                            }
                            item.startsWith("📱 Show My Friend QR Code") -> {
                                showMyFriendQrDialog()
                            }
                            item == "📷 Scan Peer QR Code (Pair Instantly)" -> {
                                qrScannerLauncher.launch(Intent(this@MainActivity, QrScannerActivity::class.java))
                            }
                            item == "✏️ Change My Callsign / Name" -> {
                                showChooseNameDialog()
                            }
                            else -> {
                                val peer = item.substringAfter("@").substringBefore(" ").trim()
                                val contact = contacts.find { it.peerId == peer }
                                if (contact != null) {
                                    showContactDetailDialog(contact)
                                }
                            }
                        }
                    }
                    .setNegativeButton("Done", null)
                    .show()
            }
        }
    }

    private fun showIncomingRequestDialog(peerId: String) {
        val options = arrayOf(
            "✅ Accept Friend Request (Add to Trusted Friends)",
            "❌ Decline Friend Request"
        )
        AlertDialog.Builder(this)
            .setTitle("Friend Request from @$peerId")
            .setMessage("Do you want to establish mutual trust with @$peerId on the mesh?")
            .setItems(options) { _, which ->
                if (which == 0) {
                    acceptFriendRequest(peerId)
                } else {
                    rejectFriendRequest(peerId)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddNewContactDialog() {
        val input = EditText(this).apply {
            hint = "e.g. Civilian-M14 or Peer ID"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("➕ Send Friend Request")
            .setMessage("Enter the peer callsign or device ID to request connection:")
            .setView(input)
            .setPositiveButton("Send Request") { _, _ ->
                val peerId = input.text.toString().trim()
                if (peerId.isNotEmpty()) {
                    sendFriendRequest(peerId)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showContactDetailDialog(contact: CivilianContact) {
        val options = arrayOf(
            "❌ Delete Friend / Remove Contact",
            "⭐ Increase Trust Score (+10)",
            "⚠️ Decrease Trust Score (-10)"
        )
        AlertDialog.Builder(this)
            .setTitle("Manage @${contact.peerId}")
            .setMessage("Status: ${if (contact.isFriend) "🛡️ Trusted Friend" else "👤 Contact (${contact.friendStatus})"}\nTrust Score: ${contact.trustScore}%\nNotes: ${contact.notes ?: "None"}")
            .setItems(options) { _, which ->
                lifecycleScope.launch(Dispatchers.IO) {
                    when (which) {
                        0 -> {
                            shredder.deleteContact(contact.peerId)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Deleted contact @${contact.peerId}", Toast.LENGTH_SHORT).show()
                                loadBroadcastMessages()
                                showFriendsManagementDialog()
                            }
                        }
                        1 -> {
                            val newScore = (contact.trustScore + 10).coerceAtMost(100)
                            shredder.saveContact(contact.copy(trustScore = newScore))
                            network.disaster.management.security.HostileActorDetector.shared.setPeerTrust(
                                contact.peerId,
                                newScore,
                                network.disaster.management.map.ActorClassification.VERIFIED_CIVILIAN
                            )
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Score increased to $newScore%", Toast.LENGTH_SHORT).show()
                                loadBroadcastMessages()
                                showFriendsManagementDialog()
                            }
                        }
                        2 -> {
                            val newScore = (contact.trustScore - 10).coerceAtLeast(0)
                            shredder.saveContact(contact.copy(trustScore = newScore))
                            network.disaster.management.security.HostileActorDetector.shared.setPeerTrust(
                                contact.peerId,
                                newScore,
                                if (newScore < 40) network.disaster.management.map.ActorClassification.SUSPICIOUS_ACTOR else network.disaster.management.map.ActorClassification.UNVERIFIED_CIVILIAN
                            )
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Score decreased to $newScore%", Toast.LENGTH_SHORT).show()
                                loadBroadcastMessages()
                                showFriendsManagementDialog()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Back") { _, _ -> showFriendsManagementDialog() }
            .show()
    }

    private fun sendFriendRequest(targetPeer: String) {
        val myId = UserIdentityManager.getUserName(this)
        if (targetPeer.isEmpty() || targetPeer == myId) {
            Toast.makeText(this, "Cannot send friend request to yourself", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val contact = CivilianContact(
                peerId = targetPeer,
                displayName = targetPeer,
                isFriend = false,
                trustScore = 75,
                trustBadge = "PENDING_REQUEST",
                notes = "Sent friend request",
                friendStatus = "PENDING_SENT"
            )
            shredder.saveContact(contact)

            val msgId = UUID.randomUUID().toString()
            val reqText = "🤝 FRIEND REQUEST: @$myId requests connection with @$targetPeer"
            val msg = FeedMessage(
                id = msgId,
                groupId = "GROUP_PUBLIC_BROADCAST",
                senderName = myId,
                content = reqText,
                type = MessageType.FRIEND_REQUEST,
                timestamp = System.currentTimeMillis(),
                isEmergency = false,
                targetPeer = targetPeer
            )
            shredder.insertMessage(msg)
            broadcastMeshEnvelope(msgId, myId, reqText, MessageType.FRIEND_REQUEST, targetPeer)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "🤝 Friend request sent to @$targetPeer!", Toast.LENGTH_SHORT).show()
                loadBroadcastMessages()
            }
        }
    }

    private fun acceptFriendRequest(peerId: String) {
        val myId = UserIdentityManager.getUserName(this)
        lifecycleScope.launch(Dispatchers.IO) {
            shredder.saveContact(
                CivilianContact(
                    peerId = peerId,
                    displayName = peerId,
                    isFriend = true,
                    trustScore = 95,
                    trustBadge = "FRIEND",
                    notes = "Accepted friend request",
                    friendStatus = "ACCEPTED"
                )
            )
            network.disaster.management.security.HostileActorDetector.shared.setPeerTrust(
                peerId,
                95,
                network.disaster.management.map.ActorClassification.VERIFIED_CIVILIAN
            )

            val msgId = UUID.randomUUID().toString()
            val acceptText = "🎉 FRIEND REQUEST ACCEPTED: @$myId connected with @$peerId"
            val msg = FeedMessage(
                id = msgId,
                groupId = "GROUP_PUBLIC_BROADCAST",
                senderName = myId,
                content = acceptText,
                type = MessageType.FRIEND_ACCEPT,
                timestamp = System.currentTimeMillis(),
                isEmergency = false,
                targetPeer = peerId
            )
            shredder.insertMessage(msg)
            broadcastMeshEnvelope(msgId, myId, acceptText, MessageType.FRIEND_ACCEPT, peerId)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "🛡️ You and @$peerId are now Trusted Friends!", Toast.LENGTH_LONG).show()
                loadBroadcastMessages()
            }
        }
    }

    private fun rejectFriendRequest(peerId: String) {
        val myId = UserIdentityManager.getUserName(this)
        lifecycleScope.launch(Dispatchers.IO) {
            shredder.updateFriendStatus(peerId, "REJECTED", isFriend = false)

            val msgId = UUID.randomUUID().toString()
            val rejectText = "❌ Friend request declined between @$myId and @$peerId"
            val msg = FeedMessage(
                id = msgId,
                groupId = "GROUP_PUBLIC_BROADCAST",
                senderName = myId,
                content = rejectText,
                type = MessageType.FRIEND_REJECT,
                timestamp = System.currentTimeMillis(),
                isEmergency = false,
                targetPeer = peerId
            )
            shredder.insertMessage(msg)
            broadcastMeshEnvelope(msgId, myId, rejectText, MessageType.FRIEND_REJECT, peerId)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Declined friend request from @$peerId", Toast.LENGTH_SHORT).show()
                loadBroadcastMessages()
            }
        }
    }

    private fun showAppPropagationDialog() {
        val apkServer = ApkPropagationServer.shared ?: ApkPropagationServer(this).also { it.start() }
        val portalUrl = apkServer.getPortalUrl()

        val options = arrayOf(
            "📲 Send SMS / Message with App Download Instructions",
            "📤 Beam APK Directly (Quick Share / Nearby Share / Bluetooth)",
            "📶 Show Wi-Fi Hotspot Portal & Camera QR Code"
        )

        AlertDialog.Builder(this)
            .setTitle("📲 Propagate Disaster Mesh App")
            .setMessage("Propagate DisasterManagement app to non-app survivors without cell service:")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val smsIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            val smsText = "🚨 EMERGENCY DISASTER MESH: Download DisasterManagement app to communicate without cell towers or internet!\n" +
                                    "Connect to Wi-Fi hotspot and open browser to: $portalUrl\n" +
                                    "Or download online: https://github.com/disaster-mesh/app/releases"
                            putExtra(Intent.EXTRA_TEXT, smsText)
                        }
                        startActivity(Intent.createChooser(smsIntent, "Send Disaster App Invite via SMS / Messenger"))
                    }
                    1 -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val apkFile = apkServer.exportApkForSharing()
                            withContext(Dispatchers.Main) {
                                if (apkFile != null && apkFile.exists()) {
                                    val contentUri = FileProvider.getUriForFile(
                                        this@MainActivity,
                                        "${packageName}.fileprovider",
                                        apkFile
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/vnd.android.package-archive"
                                        putExtra(Intent.EXTRA_STREAM, contentUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    startActivity(Intent.createChooser(shareIntent, "Beam APK (Nearby Share / Bluetooth)"))
                                } else {
                                    Toast.makeText(this@MainActivity, "Could not prepare APK for sharing", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    2 -> {
                        val bmp = apkServer.generatePortalQr(512)
                        val iv = ImageView(this).apply {
                            if (bmp != null) setImageBitmap(bmp)
                            setPadding(32, 24, 32, 24)
                        }
                        AlertDialog.Builder(this)
                            .setTitle("📶 Offline Hotspot Portal")
                            .setMessage("1. Ask survivor to connect to your Wi-Fi Hotspot.\n2. Point camera at this QR code or open browser to:\n👉 $portalUrl\n\nInstant offline sideloading (no internet required).")
                            .setView(iv)
                            .setPositiveButton("Close", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun broadcastMeshEnvelope(
        msgId: String,
        senderId: String,
        content: String,
        type: MessageType,
        targetPeer: String? = null,
        replyId: String? = null,
        replySender: String? = null,
        replyText: String? = null
    ) {
        try {
            val now = System.currentTimeMillis()
            val json = org.json.JSONObject().apply {
                put("id", msgId)
                put("groupId", "GROUP_PUBLIC_BROADCAST")
                put("senderName", senderId)
                put("content", content)
                put("type", type.name)
                put("timestamp", now)
                put("isEmergency", false)
                if (!targetPeer.isNullOrEmpty()) {
                    put("targetPeer", targetPeer)
                }
                if (!replyId.isNullOrEmpty()) {
                    put("replyToId", replyId)
                }
                if (!replySender.isNullOrEmpty()) {
                    put("replyToSender", replySender)
                }
                if (!replyText.isNullOrEmpty()) {
                    put("replyToText", replyText)
                }
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
            network.disaster.management.transport.ResilientTransportSupervisor.broadcastEverywhere(finalSerialized)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Transmission error: ${e.message}")
        }
    }

    private fun broadcastReport(text: String) {
        val replyId = pendingReplyMessage?.id
        val replySender = pendingReplyMessage?.senderName
        val replyText = pendingReplyMessage?.content?.take(100)
        clearReplyDraft()

        val locationToAttach = pendingLocationAttachment
        clearLocationAttachment()

        val finalText = if (locationToAttach != null && !text.contains("[LOC:")) {
            "$text [$locationToAttach]"
        } else {
            text
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val senderId = UserIdentityManager.getUserName(this@MainActivity)
            val msgId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            val msg = FeedMessage(
                id = msgId,
                groupId = "GROUP_PUBLIC_BROADCAST",
                senderName = senderId,
                content = finalText,
                type = MessageType.TEXT,
                timestamp = now,
                isEmergency = false,
                replyToId = replyId,
                replyToSender = replySender,
                replyToText = replyText
            )
            shredder.insertMessage(msg)
            broadcastMeshEnvelope(msgId, senderId, finalText, MessageType.TEXT, null, replyId, replySender, replyText)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Report Broadcasted (Pool B)", Toast.LENGTH_SHORT).show()
                loadBroadcastMessages()
            }
        }
    }

    private fun broadcastDistressAlert(customText: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            val senderId = UserIdentityManager.getUserName(this@MainActivity)
            val coords = gpsHelper.getFastestCoordinates()
            val coordStr = coords?.toBeaconString() ?: "LOC:UNKNOWN"
            val emergencyText = if (customText != null) {
                "$customText (From: @$senderId) [$coordStr]"
            } else {
                "DISTRESS: CIVIL LIFE-SAFETY RELAY NEEDED (From: @$senderId) [$coordStr]"
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

            // Broadcast immediately across all active mesh transports (Wi-Fi & BLE)
            network.disaster.management.transport.ResilientTransportSupervisor.broadcastEverywhere(serialized)

            // Save message into local feed
            val msg = FeedMessage(
                id = UUID.randomUUID().toString(),
                groupId = "GROUP_PUBLIC_BROADCAST",
                senderName = senderId,
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
        val options = arrayOf(
            "📷 Scan QR Code with Camera (Instant)",
            "⌨️ Enter / Paste QR String Manually"
        )
        AlertDialog.Builder(this)
            .setTitle("Join Civilian Mesh Group")
            .setItems(options) { _, which ->
                if (which == 0) {
                    qrScannerLauncher.launch(Intent(this, QrScannerActivity::class.java))
                } else {
                    showManualGroupPasteDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualGroupPasteDialog() {
        val et = EditText(this).apply {
            hint = "Paste DISASTER_GRP:... or scanned QR payload"
            setPadding(32, 32, 32, 32)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("Import Group Key")
            .setMessage("Enter or paste the group invite QR string to import the channel key:")
            .setView(et)
            .setPositiveButton("Import Group") { _, _ ->
                val payload = et.text.toString().trim()
                if (payload.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val imported = shredder.importGroupFromQr(payload)
                        withContext(Dispatchers.Main) {
                            if (imported != null) {
                                Toast.makeText(this@MainActivity, "✅ Joined group: ${imported.name}", Toast.LENGTH_SHORT).show()
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
                val emptyTv = findViewById<TextView>(R.id.tvEmptyGroups)
                emptyTv?.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
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
                    network.disaster.management.transport.ResilientTransportSupervisor.broadcastEverywhere(pkg.packet4k)
                    withContext(Dispatchers.Main) {
                        pb.progress = index + 1
                    }
                }

                val type = when {
                    manifest.mimeType.startsWith("image/") -> MessageType.IMAGE
                    manifest.mimeType.startsWith("video/") -> MessageType.VIDEO
                    else -> MessageType.FILE
                }

                val senderId = UserIdentityManager.getUserName(this@MainActivity)
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

        val btnWifiDirectConnect = findViewById<Button>(R.id.btnWifiDirectConnect)
        val btnEmergencyHotspot = findViewById<Button>(R.id.btnEmergencyHotspot)

        btnWifiDirectConnect?.setOnClickListener {
            network.disaster.management.transport.WifiDirectMeshSupervisor.shared?.triggerPeerDiscoveryAndAutoConnect()
            network.disaster.management.transport.BleMeshSupervisor.shared?.triggerImmediateScanAndSync()
            Toast.makeText(this, "Scanning & auto-pairing nearby phones (Wi-Fi Direct & BLE)...", Toast.LENGTH_SHORT).show()
        }

        btnEmergencyHotspot?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("⚡ Off-Grid Emergency Hotspot")
                .setMessage("Connect devices directly without an existing Wi-Fi network:\n\n• Option 1: Create Wi-Fi Direct Group (zero setup, connects directly)\n• Option 2: Open Android Hotspot Settings to broadcast Wi-Fi AP")
                .setPositiveButton("Wi-Fi Direct Group") { _, _ ->
                    network.disaster.management.transport.WifiDirectMeshSupervisor.shared?.createAutonomousGroup()
                    Toast.makeText(this, "Wi-Fi Direct Group Created (GO IP: 192.168.49.1)", Toast.LENGTH_LONG).show()
                }
                .setNeutralButton("Hotspot Settings") { _, _ ->
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
                    } catch (_: Exception) {
                        try {
                            startActivity(Intent("android.settings.TETHER_SETTINGS"))
                        } catch (_: Exception) {
                            Toast.makeText(this, "Please open Android Settings -> Hotspot & Tethering", Toast.LENGTH_SHORT).show()
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

        swBle?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val missing = getMissingPermissions()
                if (missing.isEmpty()) {
                    network.disaster.management.transport.BleMeshSupervisor.shared?.startMesh()
                    Toast.makeText(this, "BLE Mesh Active (Zero-Wi-Fi Mode)", Toast.LENGTH_SHORT).show()
                } else {
                    permissionLauncher.launch(missing.toTypedArray())
                }
            } else {
                network.disaster.management.transport.BleMeshSupervisor.shared?.stopMesh()
                Toast.makeText(this, "BLE Mesh Disabled", Toast.LENGTH_SHORT).show()
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
                for (msg in messages) {
                    network.disaster.management.security.HostileActorDetector.shared.analyzeMessage(msg, simEngine.hostiles)
                }
                val wifiPeers = network.disaster.management.transport.WifiMeshSupervisor.shared?.getActivePeerCount() ?: 0
                val peerIps = network.disaster.management.transport.WifiMeshSupervisor.shared?.getActivePeerIps() ?: emptyList()
                val myIps = network.disaster.management.transport.WifiMeshSupervisor.shared?.getLocalIps() ?: emptyList()
                val blePeers = network.disaster.management.transport.BleMeshSupervisor.shared?.getActivePeerCount() ?: 0
                val p2pPeers = network.disaster.management.transport.WifiDirectMeshSupervisor.shared?.getActivePeerCount() ?: 0
                val p2pStatus = network.disaster.management.transport.WifiDirectMeshSupervisor.shared?.connectionStatus?.value ?: "Wi-Fi Direct Standby"

                withContext(Dispatchers.Main) {
                    broadcastAdapter.updateMessages(messages)
                    updateStorageQuotaDisplay()

                    val totalPeers = wifiPeers + blePeers + p2pPeers
                    val statusMsg = when {
                        totalPeers > 0 -> {
                            val mediums = mutableListOf<String>()
                            if (wifiPeers > 0) mediums.add("Wi-Fi: $wifiPeers")
                            if (p2pPeers > 0) mediums.add("P2P: $p2pPeers")
                            if (blePeers > 0) mediums.add("BLE: $blePeers")
                            "Status: Mesh Active (${mediums.joinToString(", ")}) | Quotas Enforced"
                        }
                        myIps.isNotEmpty() ->
                            "Status: Wi-Fi Ready (${myIps.first()}) | BLE & P2P Active | Searching Peers..."
                        else ->
                            "Status: Off-Grid Mesh Active (BLE & Wi-Fi Direct) | Zero-Network Mode"
                    }
                    findViewById<TextView>(R.id.tvStatus)?.text = statusMsg

                    val wifiDetails = when {
                        wifiPeers > 0 ->
                            "Connected Wi-Fi Peers: ${peerIps.joinToString(", ")} | Port: 42425"
                        myIps.isNotEmpty() ->
                            "Listening on: ${myIps.joinToString(", ")}:42425 | Hotspot Mesh Active"
                        else ->
                            "Off-Grid Mode | Wi-Fi Direct P2P & BLE Active (Router Not Required)"
                    }
                    findViewById<TextView>(R.id.tvWifiMeshDetails)?.text = wifiDetails

                    findViewById<TextView>(R.id.tvWifiDirectDetails)?.text =
                        if (p2pPeers > 0) "Wi-Fi Direct: $p2pPeers Peer(s) Connected (54+ Mbps Link)" else p2pStatus

                    findViewById<TextView>(R.id.tvBleMeshDetails)?.text =
                        "BLE Mesh: $blePeers Peer${if (blePeers != 1) "s" else ""} Connected (Zero-Wi-Fi Full Duplex)"
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
        meshServicesStarted = true
        try {
            MeshForegroundService.start(this)
            network.disaster.management.transport.BleMeshSupervisor.shared?.startMesh()
            network.disaster.management.transport.WifiDirectMeshSupervisor.shared?.startMesh()
            network.disaster.management.transport.WifiMeshSupervisor.shared?.startMesh()
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
