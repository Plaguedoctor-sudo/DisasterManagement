package network.disaster.management.transport.acoustic

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.cos
import kotlin.math.sin

class FastFskModem {
    companion object {
        const val SAMPLE_RATE = 44100
        const val FREQ_MARK = 18500.0   // bit 1
        const val FREQ_SPACE = 19500.0  // bit 0
        const val SAMPLES_PER_BIT = 220
        const val PREAMBLE_BYTE1: Byte = 0xAA.toByte()
        const val PREAMBLE_BYTE2: Byte = 0x55.toByte()
    }

    fun transmitEmergencyBurst(data: ByteArray) {
        val framedData = byteArrayOf(PREAMBLE_BYTE1, PREAMBLE_BYTE2) + data
        val totalBits = framedData.size * 8
        val buf = ShortArray(totalBits * SAMPLES_PER_BIT)
        var idx = 0

        for (b in framedData) {
            for (bitIdx in 7 downTo 0) {
                val bit = (b.toInt() ushr bitIdx) and 0x01
                val freq = if (bit == 1) FREQ_MARK else FREQ_SPACE

                for (s in 0 until SAMPLES_PER_BIT) {
                    val angle = 2.0 * Math.PI * s * (freq / SAMPLE_RATE)
                    buf[idx++] = (sin(angle) * Short.MAX_VALUE).toInt().toShort()
                }
            }
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buf.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(buf, 0, buf.size)
        track.play()

        // Release track when done to prevent AudioTrack limit exhaustion
        track.setNotificationMarkerPosition(buf.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack?) {
                try {
                    t?.stop()
                    t?.release()
                } catch (_: Exception) {}
            }
            override fun onPeriodicNotification(t: AudioTrack?) {}
        })
    }

    /**
     * Goertzel algorithm to detect signal energy around target frequencies (18.5kHz vs 19.5kHz)
     */
    fun demodulateSampleWindow(samples: ShortArray, offset: Int, length: Int): Int {
        val energyMark = goertzelPower(samples, offset, length, FREQ_MARK, SAMPLE_RATE)
        val energySpace = goertzelPower(samples, offset, length, FREQ_SPACE, SAMPLE_RATE)
        return if (energyMark > energySpace) 1 else 0
    }

    private fun goertzelPower(samples: ShortArray, offset: Int, length: Int, targetFreq: Double, sampleRate: Int): Double {
        val k = (0.5 + ((length * targetFreq) / sampleRate)).toInt()
        val omega = (2.0 * Math.PI * k) / length
        val cosine = cos(omega)
        val coeff = 2.0 * cosine

        var q0: Double
        var q1 = 0.0
        var q2 = 0.0

        for (i in 0 until length) {
            val sample = samples[offset + i].toDouble()
            q0 = coeff * q1 - q2 + sample
            q2 = q1
            q1 = q0
        }

        return q1 * q1 + q2 * q2 - q1 * q2 * coeff
    }

    private var audioRecord: android.media.AudioRecord? = null
    private var isListening = false
    private var listeningThread: Thread? = null

    @android.annotation.SuppressLint("MissingPermission")
    fun startAcousticListener(onDistressDetected: (ByteArray) -> Unit) {
        if (isListening) return
        isListening = true

        val minBuf = android.media.AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLES_PER_BIT * 16)

        try {
            audioRecord = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )

            if (audioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
                isListening = false
                return
            }

            audioRecord?.startRecording()

            listeningThread = Thread {
                val samples = ShortArray(SAMPLES_PER_BIT)
                var shiftRegister = 0
                val payloadBits = mutableListOf<Int>()
                var lockedPreamble = false
                var lastAlertTimestamp = 0L

                while (isListening && audioRecord?.recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    val read = audioRecord?.read(samples, 0, samples.size) ?: -1
                    if (read > 0) {
                        val bit = demodulateSampleWindow(samples, 0, read)
                        val markEnergy = goertzelPower(samples, 0, read, FREQ_MARK, SAMPLE_RATE)
                        val spaceEnergy = goertzelPower(samples, 0, read, FREQ_SPACE, SAMPLE_RATE)
                        val totalPower = markEnergy + spaceEnergy

                        // Must have significant SNR above ambient noise
                        if (totalPower > 50000.0) {
                            if (!lockedPreamble) {
                                shiftRegister = ((shiftRegister shl 1) or bit) and 0xFFFF
                                if (shiftRegister == 0xAA55) {
                                    lockedPreamble = true
                                    payloadBits.clear()
                                }
                            } else {
                                payloadBits.add(bit)
                                if (payloadBits.size >= 64) {
                                    // 64 bits = 8 bytes payload
                                    val now = System.currentTimeMillis()
                                    if (now - lastAlertTimestamp > 10000L) {
                                        lastAlertTimestamp = now
                                        val detectedBytes = ByteArray(8)
                                        for (i in 0 until 8) {
                                            var b = 0
                                            for (j in 0 until 8) {
                                                b = (b shl 1) or payloadBits[i * 8 + j]
                                            }
                                            detectedBytes[i] = b.toByte()
                                        }
                                        onDistressDetected(detectedBytes)
                                    }
                                    lockedPreamble = false
                                    shiftRegister = 0
                                    payloadBits.clear()
                                }
                            }
                        } else {
                            if (!lockedPreamble) {
                                shiftRegister = 0
                            }
                        }
                    }
                }
            }.apply {
                priority = Thread.MIN_PRIORITY
                start()
            }
        } catch (_: Exception) {
            isListening = false
        }
    }

    fun stopAcousticListener() {
        isListening = false
        try {
            listeningThread?.interrupt()
            listeningThread = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (_: Exception) {}
    }
}
