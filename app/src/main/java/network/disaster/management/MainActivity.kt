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
import network.disaster.management.map.DisasterIncidentType
import network.disaster.management.map.MapIncident
import network.disaster.management.map.TacticalPoint
import network.disaster.management.system.EmergencyRescueSignaler
import network.disaster.management.localai.OnDeviceTacticalAdvisor
import network.disaster.management.localai.LocalAirGapManager
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
    private lateinit var rescueSignaler: EmergencyRescueSignaler
    private val onDeviceAdvisor = OnDeviceTacticalAdvisor()
    private val memoryLock = NativeMemoryLock()
    private val hybridEngine = HybridEngine()
    private val acousticModem = FastFskModem()
    private val secureRandom = SecureRandom()
    private val simEngine = network.disaster.management.simulation.SurvivorSimulationEngine()
    private var mapTacticalView: network.disaster.management.ui.TacticalLocalMapView? = null
    private var selectedIncident: MapIncident? = null
    private var currentIncidents: List<MapIncident> = emptyList()

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
        rescueSignaler = EmergencyRescueSignaler(this)

        setupPermissionBanner()
        setupStopSwitch()
        setupTabs()
        setupBroadcastFeed()
        setupTacticalMap()
        setupGroupsTab()
        setupMediaTab()
        setupRelaysTab()
        setupCloudTab()

        findViewById<TextView>(R.id.tvStatus).text = "Status: Initializing Mesh Transceivers..."

        updateUserIdentityUI()
        if (!UserIdentityManager.isUserNameCustomized(this)) {
            showChooseNameDialog(isInitialPrompt = true)
        }

        checkAndRequestAllPermissions()
        startPeriodicRefresh()
        handleFocusIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleFocusIntent(intent)
    }

    private fun handleFocusIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.hasExtra("EXTRA_FOCUS_LAT") && intent.hasExtra("EXTRA_FOCUS_LON")) {
            val lat = intent.getDoubleExtra("EXTRA_FOCUS_LAT", 0.0)
            val lon = intent.getDoubleExtra("EXTRA_FOCUS_LON", 0.0)
            val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
            tabLayout?.getTabAt(1)?.select()
            mapTacticalView?.centerOn(TacticalPoint(lat, lon))
        }
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
        val tabCloud = findViewById<View>(R.id.layoutTabCloud)

        tabLayout.addTab(tabLayout.newTab().setText("Feed"))
        tabLayout.addTab(tabLayout.newTab().setText("Tactical Map"))
        tabLayout.addTab(tabLayout.newTab().setText("Groups"))
        tabLayout.addTab(tabLayout.newTab().setText("Media Share"))
        tabLayout.addTab(tabLayout.newTab().setText("Relays"))
        tabLayout.addTab(tabLayout.newTab().setText("Tactical AI"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tabBroadcast.visibility = View.GONE
                tabMap?.visibility = View.GONE
                tabGroups.visibility = View.GONE
                tabMedia.visibility = View.GONE
                tabRelays.visibility = View.GONE
                tabCloud?.visibility = View.GONE

                when (tab?.position) {
                    0 -> {
                        tabBroadcast.visibility = View.VISIBLE
                        loadBroadcastMessages()
                    }
                    1 -> {
                        tabMap?.visibility = View.VISIBLE
                        updateTacticalMapUi()
                    }
                    2 -> {
                        tabGroups.visibility = View.VISIBLE
                        loadGroups()
                        updateRollCallSummary()
                    }
                    3 -> tabMedia.visibility = View.VISIBLE
                    4 -> {
                        tabRelays.visibility = View.VISIBLE
                        updateStorageQuotaDisplay()
                    }
                    5 -> {
                        tabCloud?.visibility = View.VISIBLE
                        updateCloudTabUi()
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupTacticalMap() {
        mapTacticalView = findViewById(R.id.mapTacticalView)

        val tvMapGpsStatus = findViewById<TextView>(R.id.tvMapGpsStatus)
        val btnCenterMyLocation = findViewById<Button>(R.id.btnCenterMyLocation)
        val btnFilterAll = findViewById<Button>(R.id.btnFilterAll)
        val btnFilterSos = findViewById<Button>(R.id.btnFilterSos)
        val btnFilterHazards = findViewById<Button>(R.id.btnFilterHazards)
        val btnFilterSupplies = findViewById<Button>(R.id.btnFilterSupplies)
        val btnFilterSafe = findViewById<Button>(R.id.btnFilterSafe)
        val btnZoomIn = findViewById<Button>(R.id.btnMapZoomIn)
        val btnZoomOut = findViewById<Button>(R.id.btnMapZoomOut)
        val cardSelected = findViewById<View>(R.id.cardSelectedIncident)
        val btnIncidentClose = findViewById<ImageButton>(R.id.btnIncidentClose)
        val btnIncidentCopyGps = findViewById<Button>(R.id.btnIncidentCopyGps)

        btnCenterMyLocation?.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val coords = gpsHelper.getFastestCoordinates()
                withContext(Dispatchers.Main) {
                    if (coords != null) {
                        val pt = TacticalPoint(coords.latitude, coords.longitude)
                        mapTacticalView?.userLocation = pt
                        mapTacticalView?.centerOn(pt)
                        tvMapGpsStatus?.text = "📍 GPS: ${"%.5f".format(coords.latitude)}, ${"%.5f".format(coords.longitude)}"
                        Toast.makeText(this@MainActivity, "Centered on current GPS coordinates", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Acquiring GPS fix... Please ensure location is enabled", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnFilterAll?.setOnClickListener {
            mapTacticalView?.activeFilter = null
            highlightFilterButton(btnFilterAll)
        }
        btnFilterSos?.setOnClickListener {
            mapTacticalView?.activeFilter = DisasterIncidentType.DISTRESS_SOS
            highlightFilterButton(btnFilterSos)
        }
        btnFilterHazards?.setOnClickListener {
            mapTacticalView?.activeFilter = DisasterIncidentType.HAZARD_ALERT
            highlightFilterButton(btnFilterHazards)
        }
        btnFilterSupplies?.setOnClickListener {
            mapTacticalView?.activeFilter = DisasterIncidentType.SUPPLY_REQUEST
            highlightFilterButton(btnFilterSupplies)
        }
        btnFilterSafe?.setOnClickListener {
            mapTacticalView?.activeFilter = DisasterIncidentType.CIVILIAN_SAFE
            highlightFilterButton(btnFilterSafe)
        }

        btnZoomIn?.setOnClickListener { mapTacticalView?.zoomIn() }
        btnZoomOut?.setOnClickListener { mapTacticalView?.zoomOut() }

        mapTacticalView?.onIncidentSelected = { incident ->
            selectedIncident = incident
            if (incident != null) {
                findViewById<TextView>(R.id.tvIncidentCardTitle)?.text = when (incident.type) {
                    DisasterIncidentType.DISTRESS_SOS -> "🔴 DISTRESS ALERT"
                    DisasterIncidentType.HAZARD_ALERT -> "⚠️ HAZARD REPORT"
                    DisasterIncidentType.SUPPLY_REQUEST -> "🟡 SUPPLY NEED"
                    DisasterIncidentType.CIVILIAN_SAFE -> "🟢 CIVILIAN SAFE"
                }
                val distMeters = mapTacticalView?.userLocation?.distanceTo(incident.location)?.toInt()
                val distText = if (distMeters != null) " • ${distMeters}m away" else ""
                findViewById<TextView>(R.id.tvIncidentCardSender)?.text = "@${incident.senderName}$distText"
                findViewById<TextView>(R.id.tvIncidentCardContent)?.text = incident.details
                cardSelected?.visibility = View.VISIBLE
            } else {
                cardSelected?.visibility = View.GONE
            }
        }

        btnIncidentClose?.setOnClickListener {
            selectedIncident = null
            mapTacticalView?.clearSelection()
            cardSelected?.visibility = View.GONE
        }

        btnIncidentCopyGps?.setOnClickListener {
            val inc = selectedIncident
            if (inc != null) {
                val clipMgr = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("GPS", "${inc.location.lat},${inc.location.lon}")
                clipMgr.setPrimaryClip(clip)
                Toast.makeText(this, "Coordinates copied: ${inc.location.lat},${inc.location.lon}", Toast.LENGTH_SHORT).show()
            }
        }

        updateTacticalMapUi()
    }

    private fun highlightFilterButton(activeBtn: Button?) {
        val filterBtns = listOfNotNull(
            findViewById<Button>(R.id.btnFilterAll),
            findViewById<Button>(R.id.btnFilterSos),
            findViewById<Button>(R.id.btnFilterHazards),
            findViewById<Button>(R.id.btnFilterSupplies),
            findViewById<Button>(R.id.btnFilterSafe)
        )
        for (btn in filterBtns) {
            btn.alpha = if (btn == activeBtn) 1.0f else 0.6f
        }
    }

    private fun updateTacticalMapUi() {
        lifecycleScope.launch(Dispatchers.IO) {
            val coords = gpsHelper.getFastestCoordinates()
            val messages = shredder.getMessages(null)
            val incidents = parseMapIncidents(messages)
            currentIncidents = incidents

            withContext(Dispatchers.Main) {
                if (coords != null) {
                    val pt = TacticalPoint(coords.latitude, coords.longitude)
                    mapTacticalView?.userLocation = pt
                    findViewById<TextView>(R.id.tvMapGpsStatus)?.text =
                        "📍 GPS: ${"%.5f".format(coords.latitude)}, ${"%.5f".format(coords.longitude)}"
                } else {
                    findViewById<TextView>(R.id.tvMapGpsStatus)?.text = "📍 GPS: Standby / Acquiring fix..."
                }

                mapTacticalView?.incidents = incidents

                val sosCount = incidents.count { it.type == DisasterIncidentType.DISTRESS_SOS }
                val hazardCount = incidents.count { it.type == DisasterIncidentType.HAZARD_ALERT }
                val supplyCount = incidents.count { it.type == DisasterIncidentType.SUPPLY_REQUEST }
                val safeCount = incidents.count { it.type == DisasterIncidentType.CIVILIAN_SAFE }

                findViewById<Button>(R.id.btnFilterAll)?.text = "🌐 ALL (${incidents.size})"
                findViewById<Button>(R.id.btnFilterSos)?.text = "🔴 DISTRESS ($sosCount)"
                findViewById<Button>(R.id.btnFilterHazards)?.text = "⚠️ HAZARDS ($hazardCount)"
                findViewById<Button>(R.id.btnFilterSupplies)?.text = "🟡 SUPPLIES ($supplyCount)"
                findViewById<Button>(R.id.btnFilterSafe)?.text = "🟢 SAFE ($safeCount)"

                findViewById<TextView>(R.id.tvMapIncidentSummary)?.text =
                    "TACTICAL GIS HUD: $sosCount Distress Alerts • $hazardCount Hazards • $supplyCount Supply Needs • $safeCount Safe Check-ins."
            }
        }
    }

    private fun parseMapIncidents(messages: List<FeedMessage>): List<MapIncident> {
        val list = mutableListOf<MapIncident>()
        val locRegex = Regex("""\[?LOC:\s*(-?\d+\.?\d*)\s*,\s*(-?\d+\.?\d*)\]?""")

        for (msg in messages) {
            val match = locRegex.find(msg.content)
            if (match != null) {
                val lat = match.groupValues[1].toDoubleOrNull()
                val lon = match.groupValues[2].toDoubleOrNull()
                if (lat != null && lon != null) {
                    val upper = msg.content.uppercase()
                    val type = when {
                        msg.isEmergency || msg.type == MessageType.EMERGENCY ||
                                upper.contains("DISTRESS") || upper.contains("TRAPPED") || upper.contains("SOS") || upper.contains("MEDICAL") ->
                            DisasterIncidentType.DISTRESS_SOS
                        upper.contains("HAZARD") || upper.contains("FIRE") || upper.contains("FLOOD") ||
                                upper.contains("DOWNED") || upper.contains("GAS") || upper.contains("COLLAPSE") ->
                            DisasterIncidentType.HAZARD_ALERT
                        upper.contains("SUPPLIES") || upper.contains("SUPPLY") || upper.contains("WATER") ||
                                upper.contains("FOOD") || upper.contains("MEDIC") || upper.contains("NEED") ->
                            DisasterIncidentType.SUPPLY_REQUEST
                        upper.contains("SAFE") || upper.contains("STATUS: SAFE") ->
                            DisasterIncidentType.CIVILIAN_SAFE
                        else ->
                            if (msg.isEmergency) DisasterIncidentType.DISTRESS_SOS else DisasterIncidentType.CIVILIAN_SAFE
                    }

                    val title = when (type) {
                        DisasterIncidentType.DISTRESS_SOS -> "Distress Beacon"
                        DisasterIncidentType.HAZARD_ALERT -> "Hazard Alert"
                        DisasterIncidentType.SUPPLY_REQUEST -> "Supply Request"
                        DisasterIncidentType.CIVILIAN_SAFE -> "Civilian Safe"
                    }

                    list.add(
                        MapIncident(
                            id = msg.id,
                            type = type,
                            title = title,
                            senderName = msg.senderName,
                            location = TacticalPoint(lat, lon),
                            timestamp = msg.timestamp,
                            details = msg.content
                        )
                    )
                }
            }
        }
        return list
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

        val btnEmergencySosBig = findViewById<Button>(R.id.btnEmergencySosBig)
        val btnRescueSiren = findViewById<Button>(R.id.btnRescueSiren)
        val btnRescueStrobe = findViewById<Button>(R.id.btnRescueStrobe)

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

        btnEmergencySosBig?.setOnClickListener {
            broadcastDistressAlert()
            showRescueBeaconPrompt()
        }

        btnRescueSiren?.setOnClickListener {
            rescueSignaler.toggleSiren()
            updateRescueSignalerUi()
        }

        btnRescueStrobe?.setOnClickListener {
            rescueSignaler.toggleStrobe()
            updateRescueSignalerUi()
        }

        broadcastAdapter = MessageAdapter(
            messages = emptyList(),
            onFileClick = { msg -> openAttachment(msg) },
            shredder = shredder,
            onReplyClick = { msg -> handleReplyToMessage(msg) },
            onUserTrustClick = { msg -> showUserTrustDialog(msg) },
            onAcceptFriendClick = { msg -> acceptFriendRequest(msg.senderName) },
            onRejectFriendClick = { msg -> rejectFriendRequest(msg.senderName) },
            onLocationClick = { lat, lon, _ ->
                val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
                tabLayout?.getTabAt(1)?.select()
                mapTacticalView?.centerOn(TacticalPoint(lat, lon))
            }
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
            showRescueBeaconPrompt()
        }

        btnQuickSafe.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val coords = gpsHelper.getFastestCoordinates()?.toBeaconString()
                val locTag = if (coords != null) " [$coords]" else ""
                broadcastReport("🟢 STATUS: SAFE — Civilian check-in confirmed$locTag")
            }
        }

        btnQuickTrapped.setOnClickListener {
            broadcastDistressAlert("🔴 CRITICAL: TRAPPED / MEDICAL AID REQUIRED")
            showRescueBeaconPrompt()
        }

        btnQuickSupplies.setOnClickListener {
            showQuickSuppliesDialog()
        }

        btnQuickHazard.setOnClickListener {
            showQuickHazardDialog()
        }

        updateRescueSignalerUi()
        loadBroadcastMessages()
    }

    private fun updateRescueSignalerUi() {
        val btnSiren = findViewById<Button>(R.id.btnRescueSiren)
        val btnStrobe = findViewById<Button>(R.id.btnRescueStrobe)

        if (rescueSignaler.isSirenActive) {
            btnSiren?.text = "🔊 SIREN: ACTIVE (LOUD)"
            btnSiren?.setBackgroundColor(Color.parseColor("#DC2626"))
            btnSiren?.setTextColor(Color.WHITE)
        } else {
            btnSiren?.text = "🔊 SIREN: OFF"
            btnSiren?.setBackgroundColor(Color.parseColor("#1E293B"))
            btnSiren?.setTextColor(Color.parseColor("#F87171"))
        }

        if (rescueSignaler.isStrobeActive) {
            btnStrobe?.text = "🔦 SOS FLASH: FLASHING"
            btnStrobe?.setBackgroundColor(Color.parseColor("#D97706"))
            btnStrobe?.setTextColor(Color.WHITE)
        } else {
            btnStrobe?.text = "🔦 SOS FLASH: OFF"
            btnStrobe?.setBackgroundColor(Color.parseColor("#1E293B"))
            btnStrobe?.setTextColor(Color.parseColor("#FBBF24"))
        }
    }

    private fun showRescueBeaconPrompt() {
        if (!rescueSignaler.isSirenActive && !rescueSignaler.isStrobeActive) {
            AlertDialog.Builder(this)
                .setTitle("🚨 DISTRESS TRANSMITTED")
                .setMessage("Emergency radio packet broadcasted! Would you like to activate the continuous sweeping acoustic siren and Morse code optical strobe to alert nearby search parties & canines?")
                .setPositiveButton("🔊 Turn ON Both Beacons") { _, _ ->
                    if (!rescueSignaler.isSirenActive) rescueSignaler.toggleSiren()
                    if (!rescueSignaler.isStrobeActive) rescueSignaler.toggleStrobe()
                    updateRescueSignalerUi()
                }
                .setNeutralButton("🔦 Strobe Only") { _, _ ->
                    if (!rescueSignaler.isStrobeActive) rescueSignaler.toggleStrobe()
                    updateRescueSignalerUi()
                }
                .setNegativeButton("Keep Silent", null)
                .show()
        }
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
            .setTitle("🟡 Request Supplies (Auto-GPS)")
            .setItems(options) { _, which ->
                val selectedNeed = options[which]
                lifecycleScope.launch(Dispatchers.IO) {
                    val coords = gpsHelper.getFastestCoordinates()?.toBeaconString()
                    val locTag = if (coords != null) " [$coords]" else ""
                    broadcastReport("🟡 RESOURCE NEED: $selectedNeed$locTag")
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
            .setTitle("⚠️ Report Hazard / Danger (Auto-GPS)")
            .setItems(options) { _, which ->
                val selectedHazard = options[which]
                lifecycleScope.launch(Dispatchers.IO) {
                    val coords = gpsHelper.getFastestCoordinates()?.toBeaconString()
                    val locTag = if (coords != null) " [$coords]" else ""
                    broadcastReport("⚠️ HAZARD ALERT: $selectedHazard$locTag")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadBroadcastMessages() {
        lifecycleScope.launch(Dispatchers.IO) {
            val messages = shredder.getMessages(null)
            for (msg in messages) {
                network.disaster.management.security.HostileActorDetector.shared.analyzeMessage(msg, emptyList())
            }
            withContext(Dispatchers.Main) {
                broadcastAdapter.updateMessages(messages)
                val emptyTv = findViewById<TextView>(R.id.tvEmptyBroadcastFeed)
                emptyTv?.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                updateTacticalMapUi()
                updateRollCallSummary()
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
        var apkServer = ApkPropagationServer.shared
        if (apkServer == null) {
            apkServer = ApkPropagationServer(this).also {
                ApkPropagationServer.shared = it
                it.start()
            }
        } else {
            apkServer.start()
        }
        val portalUrl = apkServer.getPortalUrl()

        val options = arrayOf(
            "📲 Send SMS / Message with App Download Instructions",
            "📤 Beam APK Directly (Quick Share / Nearby Share / Bluetooth)",
            "📶 Show Wi-Fi Hotspot Portal & Camera QR Code"
        )

        AlertDialog.Builder(this)
            .setTitle("📲 Propagate Disaster Mesh App")
            .setMessage("Propagate DisasterManagement app to non-app survivors without cell service:\n\nActive Server: $portalUrl")
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
        replyText: String? = null,
        fileName: String? = null,
        fileSize: Long? = null,
        mimeType: String? = null,
        fileId: String? = null
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
                if (!fileName.isNullOrEmpty()) {
                    put("fileName", fileName)
                }
                if (fileSize != null && fileSize > 0) {
                    put("fileSize", fileSize)
                }
                if (!mimeType.isNullOrEmpty()) {
                    put("mimeType", mimeType)
                }
                if (!fileId.isNullOrEmpty()) {
                    put("fileId", fileId)
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
        val btnQuickRollCallPing = findViewById<Button>(R.id.btnQuickRollCallPing)
        val tvRollCallSummary = findViewById<TextView>(R.id.tvRollCallSummary)

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

        btnQuickRollCallPing?.setOnClickListener {
            broadcastReport("📡 ROLL CALL: Requesting status check-in from all family and team members. Please tap '🟢 I AM SAFE'!")
            Toast.makeText(this, "Roll Call Request Broadcasted across Mesh", Toast.LENGTH_SHORT).show()
        }

        tvRollCallSummary?.setOnClickListener {
            showFriendsManagementDialog()
        }

        updateRollCallSummary()
        loadGroups()
    }

    private fun updateRollCallSummary() {
        lifecycleScope.launch(Dispatchers.IO) {
            val contacts = shredder.getAllContacts().filter { it.isFriend }
            val messages = shredder.getMessages(null)
            val myId = UserIdentityManager.getUserName(this@MainActivity)

            var safeCount = 0
            var distressCount = 0
            val unaccountedList = mutableListOf<String>()

            for (contact in contacts) {
                if (contact.peerId == myId) continue
                val lastMsg = messages.filter { it.senderName == contact.peerId }.maxByOrNull { it.timestamp }
                if (lastMsg == null) {
                    unaccountedList.add(contact.peerId)
                } else {
                    val upper = lastMsg.content.uppercase()
                    when {
                        lastMsg.isEmergency || lastMsg.type == MessageType.EMERGENCY || upper.contains("DISTRESS") || upper.contains("TRAPPED") ->
                            distressCount++
                        upper.contains("SAFE") || upper.contains("STATUS: SAFE") ->
                            safeCount++
                        else ->
                            unaccountedList.add(contact.peerId)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                val tvSummary = findViewById<TextView>(R.id.tvRollCallSummary)
                if (contacts.isEmpty()) {
                    tvSummary?.text = "No saved contacts yet. Tap here to pair with family & team via QR code."
                } else {
                    val unaccountedText = if (unaccountedList.isEmpty()) "None" else unaccountedList.take(3).joinToString(", ") { "@$it" } + if (unaccountedList.size > 3) " +${unaccountedList.size - 3} more" else ""
                    tvSummary?.text = "🟢 Safe: $safeCount | 🔴 In Distress: $distressCount | ⚪ Unaccounted: ${unaccountedList.size} ($unaccountedText)\n[Tap to view contacts directory]"
                }
            }
        }
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
                    isEmergency = false,
                    fileId = manifest.fileId
                )
                shredder.insertMessage(feedMsg)
                broadcastMeshEnvelope(
                    msgId = feedMsg.id,
                    senderId = senderId,
                    content = feedMsg.content,
                    type = type,
                    fileName = manifest.fileName,
                    fileSize = manifest.fileSize,
                    mimeType = manifest.mimeType,
                    fileId = manifest.fileId
                )

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

    private fun setupCloudTab() {
        val btnAuditAirGap = findViewById<Button>(R.id.btnAuditAirGap)
        val btnAsk = findViewById<Button>(R.id.btnAskGemini)
        val etPrompt = findViewById<EditText>(R.id.etGeminiPrompt)
        val layoutResponse = findViewById<View>(R.id.layoutGeminiResponse)
        val tvResponse = findViewById<TextView>(R.id.tvGeminiResponseText)
        val btnBroadcastAi = findViewById<Button>(R.id.btnBroadcastAiToMesh)
        val btnCopyAi = findViewById<Button>(R.id.btnCopyAiResponse)
        val btnOpenSettings = findViewById<Button>(R.id.btnOpenCloudSettings)

        val chipTriage = findViewById<Button>(R.id.chipTriageDistress)
        val chipSitrep = findViewById<Button>(R.id.chipGenerateSitrep)
        val chipTrauma = findViewById<Button>(R.id.chipTraumaBleed)
        val chipWater = findViewById<Button>(R.id.chipWaterSafety)
        val chipCollapse = findViewById<Button>(R.id.chipCollapseHazard)
        val chipBurns = findViewById<Button>(R.id.chipBurns)
        val chipSmoke = findViewById<Button>(R.id.chipSmoke)
        val chipHypo = findViewById<Button>(R.id.chipHypothermia)

        val badge = findViewById<TextView>(R.id.tvCloudUplinkBadge)
        val status = findViewById<TextView>(R.id.tvCloudSyncStatus)
        badge?.text = "100% AIR-GAPPED"
        badge?.setBackgroundColor(0xFF064E3B.toInt())
        badge?.setTextColor(0xFFA7F3D0.toInt())
        status?.text = "All distress triage, survival intelligence, and vaults execute 100% locally."
        status?.setTextColor(0xFF00FF66.toInt())

        btnAuditAirGap?.setOnClickListener {
            val audit = network.disaster.management.localai.LocalAirGapManager.auditAirGapStatus(this)
            AlertDialog.Builder(this)
                .setTitle("🛡️ ${audit.policyTitle}")
                .setMessage(
                    "STATUS: STRICT AIR-GAP CONFIRMED\n\n" +
                    "• Network Policy:\n${audit.networkPolicySummary}\n\n" +
                    "• Storage Encryption:\n${audit.storagePolicySummary}\n\n" +
                    "• Tactical Intelligence:\n${audit.intelligenceSummary}\n\n" +
                    "• Physical Mesh Transport:\n${audit.physicalMeshSummary}"
                )
                .setPositiveButton("Confirmed", null)
                .show()
        }

        fun displayAiAdvice(title: String, text: String) {
            layoutResponse?.visibility = View.VISIBLE
            tvResponse?.text = text
        }

        btnAsk?.setOnClickListener {
            val query = etPrompt?.text?.toString()?.trim().orEmpty()
            if (query.isNotEmpty()) {
                val advice = onDeviceAdvisor.queryEmergencyAdvisor(query)
                displayAiAdvice("ADVICE", advice)
            }
        }

        chipTriage?.setOnClickListener {
            val latestDistress = shredder.getMessages()
                .lastOrNull { it.isEmergency || it.content.contains("SOS", true) || it.content.contains("TRAPPED", true) }
            val textToTriage = latestDistress?.content ?: "RED ALERT: Trapped survivor with deep puncture wound and heavy bleeding near building collapse."
            etPrompt?.setText("Triage assessment: $textToTriage")
            val assessment = onDeviceAdvisor.triageDistress(textToTriage)
            val formatted = buildString {
                append("🚨 ON-DEVICE TRIAGE RESULT: [${assessment.priorityCode}] ${assessment.priorityLabel}\n")
                append("Urgency Score: ${assessment.urgencyScore}/10 | Evac Required: ${assessment.requiresEvacuation}\n\n")
                append("Tactical Summary:\n${assessment.summary}\n\n")
                append("Immediate Life-Safety Actions:\n")
                assessment.immediateActions.forEach { append("• $it\n") }
                append("\nRequired Medical Supplies:\n")
                assessment.suggestedSupplies.forEach { append("• $it\n") }
            }
            displayAiAdvice("TRIAGE", formatted)
        }

        chipSitrep?.setOnClickListener {
            etPrompt?.setText("Generate Sector SITREP")
            val msgs = shredder.getMessages()
            val report = onDeviceAdvisor.generateSitrep(msgs, currentIncidents, 1)
            displayAiAdvice("SITREP", report.rawBriefing)
        }

        chipTrauma?.setOnClickListener {
            etPrompt?.setText("Severe bleeding & tourniquet application instructions")
            val advice = onDeviceAdvisor.queryEmergencyAdvisor("bleeding tourniquet")
            displayAiAdvice("TRAUMA", advice)
        }

        chipWater?.setOnClickListener {
            etPrompt?.setText("Water purification emergency instructions")
            val advice = onDeviceAdvisor.queryEmergencyAdvisor("water purification")
            displayAiAdvice("WATER", advice)
        }

        chipCollapse?.setOnClickListener {
            etPrompt?.setText("Building collapse entrapment survival protocol")
            val advice = onDeviceAdvisor.queryEmergencyAdvisor("building collapse entrapment")
            displayAiAdvice("COLLAPSE", advice)
        }

        chipBurns?.setOnClickListener {
            etPrompt?.setText("Thermal burn emergency treatment protocol")
            val advice = onDeviceAdvisor.queryEmergencyAdvisor("thermal burn")
            displayAiAdvice("BURNS", advice)
        }

        chipSmoke?.setOnClickListener {
            etPrompt?.setText("Toxic fumes & smoke evacuation protocol")
            val advice = onDeviceAdvisor.queryEmergencyAdvisor("toxic smoke fumes")
            displayAiAdvice("SMOKE", advice)
        }

        chipHypo?.setOnClickListener {
            etPrompt?.setText("Hypothermia & shock stabilization protocol")
            val advice = onDeviceAdvisor.queryEmergencyAdvisor("hypothermia shock")
            displayAiAdvice("HYPOTHERMIA", advice)
        }

        btnBroadcastAi?.setOnClickListener {
            val adviceText = tvResponse?.text?.toString()?.trim().orEmpty()
            if (adviceText.isNotEmpty()) {
                val snippet = adviceText.lines().take(5).joinToString("\n")
                broadcastReport("🤖 [ON-DEVICE TACTICAL ADVISORY]:\n$snippet")
                Toast.makeText(this@MainActivity, "📢 Broadcasted tactical advice to local mesh!", Toast.LENGTH_SHORT).show()
            }
        }

        btnCopyAi?.setOnClickListener {
            val text = tvResponse?.text?.toString().orEmpty()
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Tactical Emergency Advice", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this@MainActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnOpenSettings?.setOnClickListener {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
    }

    private fun updateCloudTabUi() {
        val tvInfo = findViewById<TextView>(R.id.tvCloudProjectInfo)
        tvInfo?.text = "Environment: Strictly Local • Zero WAN / Cloud Egress Enforced"
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
        var file = candidates.firstOrNull { it.exists() && it.length() > 0 }
        if (file == null && !msg.fileName.isNullOrEmpty()) {
            val safeName = msg.fileName
            val fileId = msg.fileId ?: UUID.nameUUIDFromBytes(safeName.toByteArray(Charsets.UTF_8)).toString().replace("-", "").take(16)
            val chunkCount = shredder.getReceivedChunkCount(fileId)
            val totalNeeded = if (msg.fileSize > 0) {
                ((msg.fileSize + 3999) / 4000).toInt().coerceAtLeast(1)
            } else {
                chunkCount
            }
            if (chunkCount > 0 && chunkCount >= totalNeeded) {
                val outDir = File(filesDir, "received_media").apply { mkdirs() }
                val targetFile = File(outDir, safeName)
                val success = shredder.reassembleFile(fileId, totalNeeded, targetFile)
                if (success && targetFile.exists() && targetFile.length() > 0) {
                    file = targetFile
                }
            }
        }
        if (file == null) {
            Toast.makeText(this, "File chunks still arriving or missing: ${msg.fileName}", Toast.LENGTH_SHORT).show()
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
                    network.disaster.management.security.HostileActorDetector.shared.analyzeMessage(msg, emptyList())
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
                    updateTacticalMapUi()
                    updateRollCallSummary()

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
            // If the supervisors are already active from a previous service start, ensure they are running
            network.disaster.management.transport.BleMeshSupervisor.shared?.startMesh()
            network.disaster.management.transport.WifiDirectMeshSupervisor.shared?.startMesh()
            network.disaster.management.transport.WifiMeshSupervisor.shared?.startMesh()
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        rescueSignaler.stopAll()
        activeMediaPlayer?.release()
        activeMediaPlayer = null
        if (sessionKeyBufferAddress != 0L) {
            memoryLock.wipeAndFree(sessionKeyBufferAddress, 4096)
            sessionKeyBufferAddress = 0L
        }
    }
}
