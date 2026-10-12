package network.disaster.management.system

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.sin

/**
 * High-stress physical rescue signaling subsystem:
 * 1. High-intensity acoustic rescue siren / whistle (penetrates rubble and concrete)
 * 2. International Morse Code SOS optical strobe (... --- ...) via camera flash
 */
class EmergencyRescueSignaler(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private var cameraId: String? = null

    private var strobeScope: CoroutineScope? = null
    private var sirenScope: CoroutineScope? = null

    var isStrobeActive: Boolean = false
        private set

    var isSirenActive: Boolean = false
        private set

    init {
        findCameraWithFlash()
    }

    private fun findCameraWithFlash() {
        try {
            val cm = cameraManager ?: return
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id
                    break
                }
            }
            if (cameraId == null && cm.cameraIdList.isNotEmpty()) {
                cameraId = cm.cameraIdList.firstOrNull()
            }
        } catch (_: Exception) {}
    }

    /**
     * Toggles optical Morse Code SOS strobe.
     */
    fun toggleStrobe(): Boolean {
        if (isStrobeActive) {
            stopStrobe()
            return false
        } else {
            startStrobe()
            return true
        }
    }

    fun startStrobe() {
        val cid = cameraId ?: return
        val cm = cameraManager ?: return
        if (isStrobeActive) return

        isStrobeActive = true
        strobeScope = CoroutineScope(Dispatchers.Default + Job())
        strobeScope?.launch {
            val dot = 150L
            val dash = 450L
            val intra = 150L
            val inter = 350L
            val wordGap = 1200L

            try {
                while (isActive && isStrobeActive) {
                    // S: ...
                    for (i in 0 until 3) {
                        setTorch(cm, cid, true)
                        delay(dot)
                        setTorch(cm, cid, false)
                        delay(intra)
                    }
                    delay(inter)

                    // O: ---
                    for (i in 0 until 3) {
                        setTorch(cm, cid, true)
                        delay(dash)
                        setTorch(cm, cid, false)
                        delay(intra)
                    }
                    delay(inter)

                    // S: ...
                    for (i in 0 until 3) {
                        setTorch(cm, cid, true)
                        delay(dot)
                        setTorch(cm, cid, false)
                        delay(intra)
                    }
                    delay(wordGap)
                }
            } finally {
                setTorch(cm, cid, false)
            }
        }
    }

    fun stopStrobe() {
        isStrobeActive = false
        strobeScope?.cancel()
        strobeScope = null
        val cid = cameraId
        val cm = cameraManager
        if (cid != null && cm != null) {
            setTorch(cm, cid, false)
        }
    }

    private fun setTorch(cm: CameraManager, id: String, state: Boolean) {
        try {
            cm.setTorchMode(id, state)
        } catch (_: Exception) {}
    }

    /**
     * Toggles high-intensity acoustic rescue siren/whistle.
     */
    fun toggleSiren(): Boolean {
        if (isSirenActive) {
            stopSiren()
            return false
        } else {
            startSiren()
            return true
        }
    }

    fun startSiren() {
        if (isSirenActive) return
        isSirenActive = true

        sirenScope = CoroutineScope(Dispatchers.Default + Job())
        sirenScope?.launch {
            val sampleRate = 22050
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            var audioTrack: AudioTrack? = null
            try {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufSize * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack.play()

                val buffer = ShortArray(1024)
                var phase = 0.0
                var freq = 1200.0
                var freqStep = 25.0

                while (isActive && isSirenActive) {
                    for (i in buffer.indices) {
                        phase += 2.0 * Math.PI * freq / sampleRate
                        if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
                        buffer[i] = (sin(phase) * Short.MAX_VALUE * 0.95).toInt().toShort()

                        freq += freqStep / buffer.size
                        if (freq > 2400.0) {
                            freq = 2400.0
                            freqStep = -abs(freqStep)
                        } else if (freq < 1000.0) {
                            freq = 1000.0
                            freqStep = abs(freqStep)
                        }
                    }
                    audioTrack.write(buffer, 0, buffer.size)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (_: Exception) {}
            }
        }
    }

    fun stopSiren() {
        isSirenActive = false
        sirenScope?.cancel()
        sirenScope = null
    }

    fun stopAll() {
        stopStrobe()
        stopSiren()
    }
}
