package network.disaster.management.transport.optical

import java.nio.ByteBuffer
import java.util.Random

class FountainQrCodec {
    companion object {
        const val CHUNK_SIZE = 128
        const val SOURCE_BLOCKS = 32
        const val TOTAL_SIZE = CHUNK_SIZE * SOURCE_BLOCKS // 4096 bytes

        fun getDropletBlocks(seed: Int, degree: Int): Set<Int> {
            val prng = Random(seed.toLong())
            sampleSoliton(prng) // Advance state identical to generator
            val set = mutableSetOf<Int>()
            while (set.size < degree) set.add(prng.nextInt(SOURCE_BLOCKS))
            return set
        }

        private fun sampleSoliton(prng: Random): Int {
            val p = prng.nextDouble()
            return when {
                p < 0.35 -> 1
                p < 0.70 -> 2
                p < 0.85 -> 3
                p < 0.95 -> 4
                else -> 8
            }
        }
    }

    data class Droplet(val seed: Int, val degree: Int, val payload: ByteArray)

    fun createDroplet(bundle4k: ByteArray, seed: Int): Droplet {
        require(bundle4k.size == TOTAL_SIZE) { "Fountain input must strictly adhere to 4KB bucket" }
        val prng = Random(seed.toLong())
        val deg = sampleSoliton(prng)

        val set = mutableSetOf<Int>()
        while (set.size < deg) set.add(prng.nextInt(SOURCE_BLOCKS))

        val dropletData = ByteArray(CHUNK_SIZE)
        for (block in set) {
            val offset = block * CHUNK_SIZE
            for (i in 0 until CHUNK_SIZE) {
                dropletData[i] = (dropletData[i].toInt() xor bundle4k[offset + i].toInt()).toByte()
            }
        }
        return Droplet(seed, deg, dropletData)
    }

    fun serializeForQr(d: Droplet): ByteArray {
        val b = ByteBuffer.allocate(5 + CHUNK_SIZE)
        b.putInt(d.seed)
        b.put(d.degree.toByte())
        b.put(d.payload)
        return b.array()
    }

    fun deserializeFromQr(raw: ByteArray): Droplet? {
        if (raw.size < 5 + CHUNK_SIZE) return null
        val b = ByteBuffer.wrap(raw)
        val seed = b.int
        val degree = b.get().toInt() and 0xFF
        val payload = ByteArray(CHUNK_SIZE)
        b.get(payload)
        return Droplet(seed, degree, payload)
    }

    /**
     * GF(2) Gaussian Elimination Decoder for optimal recovery of 4KB payload from received QR droplets
     */
    class LTDecoder {
        private val basisMask = IntArray(SOURCE_BLOCKS)
        private val basisPayload = Array(SOURCE_BLOCKS) { ByteArray(CHUNK_SIZE) }
        private var rank = 0

        val isComplete: Boolean
            get() = rank == SOURCE_BLOCKS

        val currentRank: Int
            get() = rank

        fun addDroplet(droplet: Droplet): Boolean {
            if (isComplete) return true

            val blocks = getDropletBlocks(droplet.seed, droplet.degree)
            var mask = 0
            for (b in blocks) {
                mask = mask or (1 shl b)
            }
            val payload = droplet.payload.clone()

            // Row-reduce incoming droplet against current basis over GF(2)
            for (i in 0 until SOURCE_BLOCKS) {
                if ((mask and (1 shl i)) != 0) {
                    if (basisMask[i] == 0) {
                        basisMask[i] = mask
                        basisPayload[i] = payload
                        rank++
                        break
                    } else {
                        mask = mask xor basisMask[i]
                        for (k in 0 until CHUNK_SIZE) {
                            payload[k] = (payload[k].toInt() xor basisPayload[i][k].toInt()).toByte()
                        }
                    }
                }
            }

            return isComplete
        }

        fun reconstruct(): ByteArray? {
            if (!isComplete) return null
            val result = ByteArray(TOTAL_SIZE)
            val solvedPayloads = Array(SOURCE_BLOCKS) { basisPayload[it].clone() }
            val masks = basisMask.clone()

            // Back-substitution
            for (i in SOURCE_BLOCKS - 1 downTo 0) {
                for (j in i + 1 until SOURCE_BLOCKS) {
                    if ((masks[i] and (1 shl j)) != 0) {
                        for (k in 0 until CHUNK_SIZE) {
                            solvedPayloads[i][k] = (solvedPayloads[i][k].toInt() xor solvedPayloads[j][k].toInt()).toByte()
                        }
                    }
                }
                System.arraycopy(solvedPayloads[i], 0, result, i * CHUNK_SIZE, CHUNK_SIZE)
            }
            return result
        }
    }
}
