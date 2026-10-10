package network.disaster.management.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import java.util.concurrent.ConcurrentHashMap

class OutOfOrderRatchetEngine(
    private val memoryLock: NativeMemoryLock
) {
    private val MAX_SKIPPED = 256
    private val skippedKeys = ConcurrentHashMap<String, Long>()

    data class RatchetState(var chainKey: ByteArray, var sequenceNumber: Int)

    fun getOrDeriveKey(state: RatchetState, targetSeq: Int): ByteArray? {
        if (targetSeq < state.sequenceNumber) {
            return consumeSkippedKey(targetSeq)
        }
        return try {
            deriveMessageKey(state, targetSeq)
        } catch (_: Exception) {
            null
        }
    }

    fun deriveMessageKey(state: RatchetState, targetSeq: Int): ByteArray {
        if (targetSeq < state.sequenceNumber) {
            val skipped = consumeSkippedKey(targetSeq)
            if (skipped != null) return skipped
            throw IllegalArgumentException("Message sequence $targetSeq already consumed or expired (current=${state.sequenceNumber})")
        }
        val diff = targetSeq - state.sequenceNumber
        require(diff in 0..MAX_SKIPPED) { "Sequence gap violates forward secrecy boundaries: diff=$diff" }

        while (state.sequenceNumber < targetSeq) {
            val missedKey = computeStep(state.chainKey, "MESSAGE-KEY")
            val nextChain = computeStep(state.chainKey, "CHAIN-ADVANCE")

            // Evict oldest skipped key if vault limit reached to prevent mlock resource exhaustion
            if (skippedKeys.size >= MAX_SKIPPED) {
                val oldestKey = skippedKeys.keys().toList().minByOrNull { it.toIntOrNull() ?: Int.MAX_VALUE }
                if (oldestKey != null) {
                    val oldAddr = skippedKeys.remove(oldestKey)
                    if (oldAddr != null) memoryLock.wipeAndFree(oldAddr, 32)
                }
            }

            val addr = memoryLock.allocateLockedMemory(32)
            if (addr != 0L) {
                memoryLock.writeLockedBytes(addr, missedKey, 32)
                skippedKeys["${state.sequenceNumber}"] = addr
            }

            state.chainKey = nextChain
            state.sequenceNumber++
        }

        val key = computeStep(state.chainKey, "MESSAGE-KEY")
        state.chainKey = computeStep(state.chainKey, "CHAIN-ADVANCE")
        state.sequenceNumber++
        return key
    }

    fun consumeSkippedKey(targetSeq: Int): ByteArray? {
        val addr = skippedKeys.remove("$targetSeq") ?: return null
        val bytes = memoryLock.readLockedBytes(addr, 32)
        memoryLock.wipeAndFree(addr, 32)
        return bytes
    }

    fun purgeAllSkippedKeys() {
        val keys = skippedKeys.keys().toList()
        for (k in keys) {
            skippedKeys.remove(k)?.let { addr ->
                memoryLock.wipeAndFree(addr, 32)
            }
        }
    }

    private fun computeStep(key: ByteArray, info: String): ByteArray {
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(key))
        val ib = info.toByteArray(Charsets.UTF_8)
        hmac.update(ib, 0, ib.size)
        val out = ByteArray(32)
        hmac.doFinal(out, 0)
        return out
    }
}
