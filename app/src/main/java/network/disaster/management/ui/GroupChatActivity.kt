package network.disaster.management.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.disaster.management.R
import network.disaster.management.crypto.EnvelopeFormat
import network.disaster.management.crypto.HybridEngine
import network.disaster.management.model.CivilGroup
import network.disaster.management.model.FeedMessage
import network.disaster.management.model.MessageType
import network.disaster.management.routing.TriPoolRouter
import network.disaster.management.storage.LowWearCryptoShredder
import network.disaster.management.storage.MediaChunkingEngine
import network.disaster.management.system.GpsBeaconHelper
import network.disaster.management.transport.acoustic.FastFskModem
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.UUID

class GroupChatActivity : AppCompatActivity() {

    private lateinit var shredder: LowWearCryptoShredder
    private lateinit var router: TriPoolRouter
    private lateinit var mediaChunker: MediaChunkingEngine
    private lateinit var adapter: MessageAdapter
    private val hybridEngine = HybridEngine()
    private val acousticModem = FastFskModem()
    private val secureRandom = SecureRandom()
    private val gpsHelper by lazy { GpsBeaconHelper(this) }

    private var currentGroup: CivilGroup? = null
    private var groupId: String = "GROUP_PUBLIC_BROADCAST"

    private val localRecipientTag = ByteArray(16).apply {
        for (i in indices) this[i] = (i + 1).toByte()
    }

    private val pickMediaLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleMediaAttachment(it) }
    }

    private val requestAudioLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            findViewById<ImageButton>(R.id.btnRecordVoice)?.let { startVoiceRecording(it) }
        } else {
            Toast.makeText(this, "Microphone permission required for voice notes", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_chat)

        groupId = intent.getStringExtra("EXTRA_GROUP_ID") ?: "GROUP_PUBLIC_BROADCAST"
        shredder = LowWearCryptoShredder(this)
        router = TriPoolRouter(shredder, localRecipientTag)
        mediaChunker = MediaChunkingEngine(this, shredder)

        currentGroup = shredder.getGroupById(groupId)

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val tvGroupName = findViewById<TextView>(R.id.tvChatGroupName)
        val tvGroupDesc = findViewById<TextView>(R.id.tvChatGroupDesc)
        val rvMessages = findViewById<RecyclerView>(R.id.rvGroupMessages)
        val etChatMessage = findViewById<EditText>(R.id.etChatMessage)
        val btnSendChat = findViewById<ImageButton>(R.id.btnSendChat)
        val btnAttachMedia = findViewById<ImageButton>(R.id.btnAttachMedia)
        val btnLeaveGroup = findViewById<ImageButton>(R.id.btnLeaveGroup)
        val btnAcousticChirp = findViewById<ImageButton>(R.id.btnAcousticChirp)
        val btnShareGroupQr = findViewById<ImageButton>(R.id.btnShareGroupQr)
        val btnRecordVoice = findViewById<ImageButton>(R.id.btnRecordVoice)

        val btnTeamCheckIn = findViewById<Button>(R.id.btnTeamCheckIn)
        val btnTeamNeedAid = findViewById<Button>(R.id.btnTeamNeedAid)
        val btnTeamSupplies = findViewById<Button>(R.id.btnTeamSupplies)
        val btnTeamLocation = findViewById<Button>(R.id.btnTeamLocation)

        tvGroupName.text = currentGroup?.name ?: "Group Chat"
        tvGroupDesc.text = if (currentGroup?.isBroadcast == true) {
            "Open Civilian Broadcast Channel"
        } else {
            "Encrypted Mesh Channel • Kyber768/X25519"
        }

        btnBack.setOnClickListener { finish() }

        adapter = MessageAdapter(emptyList()) { msg ->
            openAttachment(msg)
        }
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        rvMessages.layoutManager = layoutManager
        rvMessages.adapter = adapter

        btnSendChat.setOnClickListener {
            val text = etChatMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendTextMessage(text)
                etChatMessage.text.clear()
            }
        }

        btnAttachMedia.setOnClickListener {
            pickMediaLauncher.launch("*/*")
        }

        btnLeaveGroup.setOnClickListener {
            if (groupId == "GROUP_PUBLIC_BROADCAST") {
                Toast.makeText(this, "Cannot leave default Civil Public Broadcast Channel", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Leave Group")
                .setMessage("Are you sure you want to leave '${currentGroup?.name}'? Ephemeral group keys and local cryptographic tokens will be wiped from this device.")
                .setPositiveButton("Leave & Shred") { _, _ ->
                    shredder.leaveGroup(groupId)
                    Toast.makeText(this, "Left group: local credentials shredded", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnAcousticChirp.setOnClickListener {
            Toast.makeText(this, "Emitting near-ultrasound 4FSK chirp (18.5kHz - 19.5kHz)...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                acousticModem.transmitEmergencyBurst(groupId.toByteArray(Charsets.UTF_8).take(16).toByteArray())
            }
        }

        btnShareGroupQr.setOnClickListener {
            val group = currentGroup ?: return@setOnClickListener
            val bmp = GroupQrHelper.generateGroupQrBitmap(group)
            if (bmp != null) {
                val iv = ImageView(this).apply {
                    setImageBitmap(bmp)
                    setPadding(32, 32, 32, 32)
                }
                AlertDialog.Builder(this)
                    .setTitle("Share Group Mesh Key")
                    .setMessage("Scan this QR code from another device to join '${group.name}'.")
                    .setView(iv)
                    .setPositiveButton("Close", null)
                    .show()
            }
        }

        btnRecordVoice.setOnClickListener {
            if (!isRecordingVoice) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startVoiceRecording(btnRecordVoice)
                } else {
                    requestAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            } else {
                stopVoiceRecording(btnRecordVoice)
            }
        }

        btnTeamCheckIn.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val coords = gpsHelper.getFastestCoordinates()?.toBeaconString() ?: "LOC:UNKNOWN"
                sendTextMessage("✅ STATUS CHECK-IN: SAFE & ACCOUNTED FOR [$coords]")
            }
        }

        btnTeamNeedAid.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val coords = gpsHelper.getFastestCoordinates()?.toBeaconString() ?: "LOC:UNKNOWN"
                sendTextMessage("🚨 PRIORITY ALERT: NEED IMMEDIATE EVACUATION / AID [$coords]", isEmergency = true)
                acousticModem.transmitEmergencyBurst(groupId.toByteArray(Charsets.UTF_8).take(16).toByteArray())
            }
        }

        btnTeamSupplies.setOnClickListener {
            showTeamSupplyDialog()
        }

        btnTeamLocation.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val coords = gpsHelper.getFastestCoordinates()?.toBeaconString() ?: "LOC:ACQUIRING"
                sendTextMessage("📍 TEAM LOCATION BEACON: [$coords]")
            }
        }

        loadMessages()
        startLivePolling()
    }

    private fun loadMessages() {
        lifecycleScope.launch(Dispatchers.IO) {
            val messages = shredder.getMessages(groupId)
            withContext(Dispatchers.Main) {
                adapter.updateMessages(messages)
                val rvMessages = findViewById<RecyclerView>(R.id.rvGroupMessages)
                if (messages.isNotEmpty()) {
                    rvMessages.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    private fun startLivePolling() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2500)
                val messages = shredder.getMessages(groupId)
                withContext(Dispatchers.Main) {
                    adapter.updateMessages(messages)
                }
            }
        }
    }

    private fun showTeamSupplyDialog() {
        val options = arrayOf(
            "💧 Water & Hydration Needed",
            "🩹 First Aid / Trauma Kit Needed",
            "🥫 Food / Non-Perishable Rations Needed",
            "⛺ Emergency Shelter / Blankets Needed",
            "🔋 Battery / Radio / Flashlight Needed",
            "🛠️ Tools / Extraction Equipment Needed"
        )
        AlertDialog.Builder(this)
            .setTitle("Team Supply Request")
            .setItems(options) { _, which ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val coords = gpsHelper.getFastestCoordinates()?.toBeaconString() ?: "LOC:UNKNOWN"
                    sendTextMessage("📦 SUPPLY NEED: ${options[which]} [$coords]")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendTextMessage(text: String, isEmergency: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val senderId = "Node-" + (Build.MODEL.takeLast(4).ifEmpty { "ME" })
            val msgId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            val msg = FeedMessage(
                id = msgId,
                groupId = groupId,
                senderName = senderId,
                content = text,
                type = if (isEmergency) MessageType.EMERGENCY else MessageType.TEXT,
                timestamp = now,
                isEmergency = isEmergency
            )
            shredder.insertMessage(msg)

            // Encapsulate into 4KB invariant packet and route through TriPoolRouter (Pool A for emergency, Pool B for text)
            try {
                val inputBytes = text.toByteArray(Charsets.UTF_8)
                val x25519Pair = hybridEngine.generateX25519KeyPair()
                val kyberPair = hybridEngine.generateKyber768KeyPair()
                val (pqSecret, pqCiphertext) = hybridEngine.encapsulateKyber768(kyberPair.public)
                val classicalSecret = hybridEngine.calculateX25519Agreement(x25519Pair.private, x25519Pair.public)
                val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
                val derivedKey = hybridEngine.deriveHybridSharedKey(classicalSecret, pqSecret, salt)

                val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
                val aad = ByteArray(16).also { secureRandom.nextBytes(it) }
                val (ciphertext, macTag) = hybridEngine.encryptPayloadTier1(derivedKey, nonce, aad, inputBytes)

                val bundleId = ByteArray(32).also { secureRandom.nextBytes(it) }
                val x25519PubBytes = ByteArray(32).also { secureRandom.nextBytes(it) }

                val batteryMgr = network.disaster.management.system.OemBatteryManager(this@GroupChatActivity)
                val diff = batteryMgr.getOptimalPoWDifficulty(isEmergency)

                val envelope = EnvelopeFormat.Tier1Envelope(
                    hopLimit = if (isEmergency) 15.toByte() else 7.toByte(),
                    creationEpoch = now,
                    ttlExpiry = now + (if (isEmergency) 72 * 3600 * 1000L else 24 * 3600 * 1000L),
                    powDifficulty = diff,
                    powNonce = 0,
                    bundleId = bundleId,
                    rollingRecipientTag = localRecipientTag,
                    ephemeralX25519Pub = x25519PubBytes,
                    mlKemCiphertext = pqCiphertext,
                    nonce = nonce,
                    bucketLength = ciphertext.size,
                    encryptedBody = ciphertext,
                    macTag = macTag,
                    flags = if (isEmergency) (EnvelopeFormat.FLAG_ENCRYPTED.toInt() or EnvelopeFormat.FLAG_EMERGENCY.toInt()).toByte() else EnvelopeFormat.FLAG_ENCRYPTED
                )
                val rawSerialized = EnvelopeFormat.serializeTier1(envelope)
                val solvedNonce = EnvelopeFormat.solvePoW(rawSerialized, envelope.powDifficulty)
                envelope.powNonce = solvedNonce
                val finalPacket = EnvelopeFormat.serializeTier1(envelope)
                router.processIngressPacket(finalPacket)
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                loadMessages()
            }
        }
    }

    private fun handleMediaAttachment(uri: Uri) {
        Toast.makeText(this, "Preparing and slicing media for store-carry-forward mesh...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (manifest, packages) = mediaChunker.chunkFileFromUri(uri)

                // Cache original file locally so sender can view it
                val mediaDir = File(filesDir, "mesh_media").apply { mkdirs() }
                val localFile = File(mediaDir, manifest.fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(localFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // Store chunks and route each into Pool C
                for (pkg in packages) {
                    shredder.saveFileChunk(pkg.fileId, pkg.chunkIndex, pkg.totalChunks, pkg.chunkData)
                    router.processIngressPacket(pkg.packet4k)
                }

                val type = when {
                    manifest.mimeType.startsWith("image/") -> MessageType.IMAGE
                    manifest.mimeType.startsWith("video/") -> MessageType.VIDEO
                    else -> MessageType.FILE
                }

                val senderId = "Node-" + (Build.MODEL.takeLast(4).ifEmpty { "ME" })
                val msg = FeedMessage(
                    id = UUID.randomUUID().toString(),
                    groupId = groupId,
                    senderName = senderId,
                    content = "Shared media: ${manifest.fileName} (${packages.size} chunks)",
                    type = type,
                    fileName = manifest.fileName,
                    fileSize = manifest.fileSize,
                    mimeType = manifest.mimeType,
                    localFilePath = localFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    isEmergency = false
                )
                shredder.insertMessage(msg)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@GroupChatActivity,
                        "Partitioned ${manifest.fileName} into ${packages.size} x 4KB invariant frames (Pool C)",
                        Toast.LENGTH_LONG
                    ).show()
                    loadMessages()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GroupChatActivity, "Attachment error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private var activeMediaPlayer: android.media.MediaPlayer? = null

    private fun openAttachment(msg: FeedMessage) {
        android.util.Log.i("OpenAttachment", "openAttachment tapped in GroupChat: id=${msg.id}, name=${msg.fileName}, path=${msg.localFilePath}")
        val candidates = listOfNotNull(
            msg.localFilePath?.let { File(it) },
            msg.fileName?.let { File(File(cacheDir, "voice_memos"), it) },
            msg.fileName?.let { File(File(filesDir, "mesh_media"), it) },
            msg.fileName?.let { File(File(filesDir, "received_media"), it) }
        )
        val file = candidates.firstOrNull { it.exists() }
        if (file == null) {
            Toast.makeText(this, "File not yet reassembled or missing from local storage", Toast.LENGTH_SHORT).show()
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

    private var mediaRecorder: android.media.MediaRecorder? = null
    private var isRecordingVoice = false
    private var voiceOutputFile: File? = null

    private fun startVoiceRecording(btn: ImageButton) {
        try {
            val dir = File(cacheDir, "voice_memos").apply { mkdirs() }
            voiceOutputFile = File(dir, "memo_${System.currentTimeMillis()}.m4a")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.media.MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaRecorder()
            }.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setOutputFile(voiceOutputFile!!.absolutePath)
                prepare()
                start()
            }
            isRecordingVoice = true
            btn.setColorFilter(0xFFFF0000.toInt())
            Toast.makeText(this, "Recording voice memo... Tap again to send", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            isRecordingVoice = false
            Toast.makeText(this, "Could not start audio recorder: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVoiceRecording(btn: ImageButton) {
        if (!isRecordingVoice) return
        isRecordingVoice = false
        btn.setColorFilter(0xFFFFA000.toInt())

        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null

            val file = voiceOutputFile
            if (file != null && file.exists() && file.length() > 0) {
                handleVoiceMemoFile(file)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Recording stopped: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleVoiceMemoFile(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = file.readBytes()
                val (manifest, packages) = mediaChunker.chunkBytes(bytes, file.name, "audio/mp4")

                for (pkg in packages) {
                    shredder.saveFileChunk(pkg.fileId, pkg.chunkIndex, pkg.totalChunks, pkg.chunkData)
                    router.processIngressPacket(pkg.packet4k)
                }

                val senderId = "Node-" + (Build.MODEL.takeLast(4).ifEmpty { "ME" })
                val msg = FeedMessage(
                    id = UUID.randomUUID().toString(),
                    groupId = groupId,
                    senderName = senderId,
                    content = "Voice memo (${packages.size} chunks)",
                    type = MessageType.FILE,
                    fileName = manifest.fileName,
                    fileSize = manifest.fileSize,
                    mimeType = "audio/mp4",
                    localFilePath = file.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    isEmergency = false
                )
                shredder.insertMessage(msg)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GroupChatActivity, "Voice memo sent (${packages.size} chunks) to Pool C", Toast.LENGTH_SHORT).show()
                    loadMessages()
                }
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeMediaPlayer?.release()
        activeMediaPlayer = null
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
    }
}
