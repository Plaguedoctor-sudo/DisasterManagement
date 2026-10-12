package network.disaster.management.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import network.disaster.management.R
import network.disaster.management.routing.TriPoolRouter
import network.disaster.management.storage.LowWearCryptoShredder
import network.disaster.management.transport.optical.FountainQrCodec
import java.util.concurrent.Executors

class OpticalFountainActivity : AppCompatActivity() {

    private lateinit var shredder: LowWearCryptoShredder
    private lateinit var router: TriPoolRouter
    private val qrWriter = QRCodeWriter()
    private val qrReader = QRCodeReader()

    private var isBroadcasting = true
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val localRecipientTag = ByteArray(16).apply {
        for (i in indices) this[i] = (i + 1).toByte()
    }

    private val fountainCodec = FountainQrCodec()
    private var fountainDecoder = FountainQrCodec.LTDecoder()
    private var lastDecodedSeed = -1
    private var lastDecodedTime = 0L

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCameraScanner()
        } else {
            Toast.makeText(this, "Camera permission required for LT Fountain scanner", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_optical_fountain)

        shredder = LowWearCryptoShredder(this)
        router = TriPoolRouter(shredder, localRecipientTag)

        val btnBack = findViewById<ImageButton>(R.id.btnBackOptical)
        btnBack.setOnClickListener { finish() }

        val rgMode = findViewById<RadioGroup>(R.id.rgOpticalMode)
        val layoutTransmit = findViewById<LinearLayout>(R.id.layoutOpticalTransmit)
        val layoutReceive = findViewById<LinearLayout>(R.id.layoutOpticalReceive)

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbModeTransmit) {
                layoutTransmit.visibility = View.VISIBLE
                layoutReceive.visibility = View.GONE
                stopCameraScanner()
                isBroadcasting = true
                startFountainAnimation()
            } else {
                layoutTransmit.visibility = View.GONE
                layoutReceive.visibility = View.VISIBLE
                isBroadcasting = false
                checkCameraPermissionAndStart()
            }
        }

        setupReceiveTab()
        startFountainAnimation()
    }

    private fun startFountainAnimation() {
        val ivQr = findViewById<ImageView>(R.id.ivAnimatedQr)
        val tvInfo = findViewById<TextView>(R.id.tvDropletInfo)

        lifecycleScope.launch(Dispatchers.Default) {
            val recentBundleId = shredder.getAllBundleIds(1).firstOrNull()
            val packet = (if (recentBundleId != null) shredder.retrieveBundle(recentBundleId) else null)
                ?: ByteArray(4096) { 0x42.toByte() }

            var seed = 1

            while (isActive && isBroadcasting) {
                val droplet = fountainCodec.createDroplet(packet, seed)
                val rawBytes = fountainCodec.serializeForQr(droplet)
                val encodedDroplet = "LT:" + Base64.encodeToString(rawBytes, Base64.NO_WRAP)
                val bmp = generateQrBitmap(encodedDroplet, 400)

                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        ivQr.setImageBitmap(bmp)
                        tvInfo.text = "Droplet #${seed} (Seed 0x${seed.toString(16).uppercase()}) | Degree: ${droplet.degree}"
                    }
                }

                seed++
                delay(120L) // ~8 fps stream
            }
        }
    }

    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraScanner() {
        val previewView = findViewById<PreviewView>(R.id.cameraPreviewView) ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processCameraFrame(imageProxy)
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCameraScanner() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processCameraFrame(imageProxy: ImageProxy) {
        try {
            val mediaImage = imageProxy.image
            if (mediaImage != null && mediaImage.format == ImageFormat.YUV_420_888) {
                val yPlane = mediaImage.planes[0]
                val yBuffer = yPlane.buffer
                val yBytes = ByteArray(yBuffer.remaining())
                yBuffer.get(yBytes)

                val width = imageProxy.width
                val height = imageProxy.height
                val rowStride = yPlane.rowStride

                val source = PlanarYUVLuminanceSource(
                    yBytes, rowStride, height,
                    0, 0, width, height, false
                )
                val binaryBmp = BinaryBitmap(HybridBinarizer(source))

                try {
                    val result = qrReader.decode(binaryBmp)
                    val text = result.text
                    if (text != null) {
                        if (text.startsWith("DISASTER_GRP:")) {
                            val imported = shredder.importGroupFromQr(text)
                            runOnUiThread {
                                if (imported != null) {
                                    Toast.makeText(this, "✅ Joined Group via QR: ${imported.name}", Toast.LENGTH_LONG).show()
                                    finish()
                                }
                            }
                        } else if (text.startsWith("LT:")) {
                            val now = System.currentTimeMillis()
                            val droplet = decodeDropletFromString(text)
                            if (droplet != null) {
                                // Avoid ingesting identical frame within 200ms
                                if (droplet.seed != lastDecodedSeed || now - lastDecodedTime > 200L) {
                                    lastDecodedSeed = droplet.seed
                                    lastDecodedTime = now
                                    runOnUiThread {
                                        ingestDropletToSolver(droplet)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // No QR detected in this frame, normal operation
                } finally {
                    qrReader.reset()
                }
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun ingestDropletToSolver(droplet: FountainQrCodec.Droplet) {
        val tvStatus = findViewById<TextView>(R.id.tvReceiverRankStatus)
        val pbRank = findViewById<ProgressBar>(R.id.pbRankSolver)
        val tvLogs = findViewById<TextView>(R.id.tvSolverLogs)

        val completed = fountainDecoder.addDroplet(droplet)
        val rank = fountainDecoder.currentRank
        pbRank.progress = rank
        val percent = (rank * 100) / 32
        tvStatus.text = "Solving matrix: $rank / 32 ranks ($percent%)"
        tvLogs.text = "${tvLogs.text}\n+ Ingested Droplet (seed=${droplet.seed}, deg=${droplet.degree}, rank=$rank)"

        if (completed) {
            val solved = fountainDecoder.reconstruct()
            if (solved != null) {
                val verdict = router.processIngressPacket(solved)
                tvStatus.text = "RECONSTRUCTION COMPLETE (100% Full Rank)"
                pbRank.progress = 32
                tvLogs.text = "${tvLogs.text}\n>>> SUCCESS: Solved 4KB profile! Routed -> $verdict"
                Toast.makeText(this, "Reconstructed 4KB packet via Optical Fountain!", Toast.LENGTH_LONG).show()
                fountainDecoder = FountainQrCodec.LTDecoder()
            }
        }
    }

    private fun setupReceiveTab() {
        val etInput = findViewById<EditText>(R.id.etManualDropletInput)
        val btnIngest = findViewById<Button>(R.id.btnIngestDroplet)
        val btnReset = findViewById<Button>(R.id.btnResetSolver)
        val btnToggleManual = findViewById<Button>(R.id.btnToggleManualInput)
        val layoutManual = findViewById<LinearLayout>(R.id.layoutManualEntry)
        val tvStatus = findViewById<TextView>(R.id.tvReceiverRankStatus)
        val pbRank = findViewById<ProgressBar>(R.id.pbRankSolver)
        val tvLogs = findViewById<TextView>(R.id.tvSolverLogs)

        btnReset.setOnClickListener {
            fountainDecoder = FountainQrCodec.LTDecoder()
            lastDecodedSeed = -1
            pbRank.progress = 0
            tvStatus.text = "Awaiting LT QR droplets... (Rank 0/32)"
            tvLogs.text = "[Reset] Peeling decoder cleared. Ready for next stream."
            Toast.makeText(this, "Decoder solver reset", Toast.LENGTH_SHORT).show()
        }

        btnToggleManual.setOnClickListener {
            val isVisible = layoutManual.visibility == View.VISIBLE
            layoutManual.visibility = if (isVisible) View.GONE else View.VISIBLE
            btnToggleManual.text = if (isVisible) "Manual Entry" else "Hide Manual"
        }

        btnIngest.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                val droplet = decodeDropletFromString(text)
                if (droplet != null) {
                    etInput.text.clear()
                    ingestDropletToSolver(droplet)
                } else {
                    Toast.makeText(this, "Invalid droplet encoding", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun decodeDropletFromString(encoded: String): FountainQrCodec.Droplet? {
        return try {
            val rawB64 = encoded.removePrefix("LT:")
            val bytes = Base64.decode(rawB64, Base64.NO_WRAP)
            fountainCodec.deserializeFromQr(bytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun generateQrBitmap(content: String, size: Int): Bitmap? {
        return try {
            val bitMatrix = qrWriter.encode(content, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isBroadcasting = false
        stopCameraScanner()
        cameraExecutor.shutdown()
    }
}
