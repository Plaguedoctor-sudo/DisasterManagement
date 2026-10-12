package network.disaster.management.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import network.disaster.management.R
import network.disaster.management.storage.LowWearCryptoShredder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCANNED_DATA = "SCANNED_QR_DATA"
        const val EXTRA_GROUP_NAME = "SCANNED_GROUP_NAME"
        const val EXTRA_FRIEND_PEER = "SCANNED_FRIEND_PEER"
    }

    private lateinit var shredder: LowWearCryptoShredder
    private val qrReader = QRCodeReader()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraControl: CameraControl? = null
    private var isTorchOn = false
    private val isHandled = AtomicBoolean(false)

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required to scan QR codes", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scanner)

        shredder = LowWearCryptoShredder(this)

        findViewById<ImageButton>(R.id.btnBackQr).setOnClickListener {
            finish()
        }

        findViewById<ImageButton>(R.id.btnToggleTorch).setOnClickListener {
            toggleTorch()
        }

        findViewById<Button>(R.id.btnManualPasteQr).setOnClickListener {
            showManualPasteDialog()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val previewView = findViewById<PreviewView>(R.id.qrCameraPreview) ?: return
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
                val camera = cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                cameraControl = camera?.cameraControl
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Camera initialization error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processCameraFrame(imageProxy: ImageProxy) {
        if (isHandled.get()) {
            imageProxy.close()
            return
        }

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
                    val text = result?.text
                    if (!text.isNullOrEmpty()) {
                        if (isHandled.compareAndSet(false, true)) {
                            runOnUiThread {
                                handleDecodedQrText(text)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // No QR code in frame
                } finally {
                    qrReader.reset()
                }
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun handleDecodedQrText(text: String) {
        findViewById<TextView>(R.id.tvScanningStatus)?.text = "✅ QR Code Recognized!"

        when {
            text.startsWith("DISASTER_GRP:") -> {
                val imported = shredder.importGroupFromQr(text)
                if (imported != null) {
                    Toast.makeText(this, "✅ Joined Group: ${imported.name}", Toast.LENGTH_LONG).show()
                    val resultIntent = Intent().apply {
                        putExtra(EXTRA_SCANNED_DATA, text)
                        putExtra(EXTRA_GROUP_NAME, imported.name)
                    }
                    setResult(Activity.RESULT_OK, resultIntent)
                    finish()
                } else {
                    Toast.makeText(this, "Invalid group key in QR code", Toast.LENGTH_SHORT).show()
                    isHandled.set(false)
                }
            }
            text.startsWith("DISASTER_FRIEND:") -> {
                val peerId = text.removePrefix("DISASTER_FRIEND:").trim()
                Toast.makeText(this, "✅ Scanned Peer: @$peerId", Toast.LENGTH_SHORT).show()
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_SCANNED_DATA, text)
                    putExtra(EXTRA_FRIEND_PEER, peerId)
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            }
            else -> {
                // Generic QR text (could be manual payload or group string)
                val imported = shredder.importGroupFromQr(text)
                if (imported != null) {
                    Toast.makeText(this, "✅ Joined Group: ${imported.name}", Toast.LENGTH_LONG).show()
                    val resultIntent = Intent().apply {
                        putExtra(EXTRA_SCANNED_DATA, text)
                        putExtra(EXTRA_GROUP_NAME, imported.name)
                    }
                    setResult(Activity.RESULT_OK, resultIntent)
                    finish()
                } else {
                    val resultIntent = Intent().apply {
                        putExtra(EXTRA_SCANNED_DATA, text)
                    }
                    setResult(Activity.RESULT_OK, resultIntent)
                    finish()
                }
            }
        }
    }

    private fun toggleTorch() {
        isTorchOn = !isTorchOn
        cameraControl?.enableTorch(isTorchOn)
        val icon = if (isTorchOn) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_day
        findViewById<ImageButton>(R.id.btnToggleTorch)?.setImageResource(icon)
    }

    private fun showManualPasteDialog() {
        val et = EditText(this).apply {
            hint = "Paste DISASTER_GRP:... or peer string"
            setPadding(32, 24, 32, 24)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
        }

        AlertDialog.Builder(this)
            .setTitle("⌨️ Manual Code Entry")
            .setMessage("Paste or type the group invite or peer QR string:")
            .setView(et)
            .setPositiveButton("Import") { _, _ ->
                val payload = et.text.toString().trim()
                if (payload.isNotEmpty()) {
                    handleDecodedQrText(payload)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
        } catch (_: Exception) {}
    }
}
