package network.disaster.management.transport

/**
 * High-performance 256-bit (32-byte) Bloom filter optimized for low-bandwidth BLE mesh exchanges.
 * Uses 3 non-cryptographic projections across the 32-byte bundle ID to summarize local inventory
 * in a single 32-byte wire payload.
 */
class BundleBloomFilter(val filterBytes: ByteArray = ByteArray(FILTER_SIZE_BYTES)) {

    companion object {
        const val FILTER_SIZE_BYTES = 32
        const val BIT_COUNT = 256

        fun fromBundleIds(bundleIds: List<ByteArray>): BundleBloomFilter {
            val bloom = BundleBloomFilter()
            for (id in bundleIds) {
                bloom.add(id)
            }
            return bloom
        }
    }

    fun add(bundleId: ByteArray) {
        val (h1, h2, h3) = computeIndices(bundleId)
        setBit(h1)
        setBit(h2)
        setBit(h3)
    }

    /**
     * Returns false if bundleId is definitely NOT in the filter (zero false negatives).
     * Returns true if bundleId might be in the filter.
     */
    fun mightContain(bundleId: ByteArray): Boolean {
        val (h1, h2, h3) = computeIndices(bundleId)
        return isBitSet(h1) && isBitSet(h2) && isBitSet(h3)
    }

    private fun setBit(bitIndex: Int) {
        val b = bitIndex and 0xFF
        val bytePos = b ushr 3
        val bitPos = b and 0x07
        filterBytes[bytePos] = (filterBytes[bytePos].toInt() or (1 shl bitPos)).toByte()
    }

    private fun isBitSet(bitIndex: Int): Boolean {
        val b = bitIndex and 0xFF
        val bytePos = b ushr 3
        val bitPos = b and 0x07
        return (filterBytes[bytePos].toInt() and (1 shl bitPos)) != 0
    }

    private fun computeIndices(bundleId: ByteArray): Triple<Int, Int, Int> {
        val b0 = if (bundleId.isNotEmpty()) bundleId[0].toInt() and 0xFF else 0
        val b1 = if (bundleId.size > 1) bundleId[1].toInt() and 0xFF else 0
        val b2 = if (bundleId.size > 2) bundleId[2].toInt() and 0xFF else 0
        val b3 = if (bundleId.size > 3) bundleId[3].toInt() and 0xFF else 0
        val b4 = if (bundleId.size > 4) bundleId[4].toInt() and 0xFF else 0
        val b5 = if (bundleId.size > 5) bundleId[5].toInt() and 0xFF else 0

        val h1 = ((b0 shl 8) or b1) and 0xFF
        val h2 = ((b2 shl 8) or b3) and 0xFF
        val h3 = ((b4 shl 8) or b5) and 0xFF
        return Triple(h1, h2, h3)
    }
}
