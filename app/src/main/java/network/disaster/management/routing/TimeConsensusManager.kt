package network.disaster.management.routing

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class TimeConsensusManager {
    companion object {
        val shared = TimeConsensusManager()
    }

    private val offsets = ConcurrentLinkedQueue<Long>()
    private val lamportClock = AtomicLong(0L)

    /**
     * Records an observed peer epoch timestamp (ms) to compute median time offset.
     */
    fun recordPeerTimestamp(peerEpochMs: Long) {
        val diff = peerEpochMs - System.currentTimeMillis()
        if (abs(diff) < 48 * 3600 * 1000L) {
            offsets.add(diff)
            if (offsets.size > 15) offsets.poll()
        }
    }

    /**
     * Returns the network consensus time in milliseconds based on median peer clock offset.
     */
    fun getConsensusTimeMs(): Long {
        val local = System.currentTimeMillis()
        if (offsets.isEmpty()) return local
        val sorted = offsets.toList().sorted()
        val median = sorted[sorted.size / 2]
        return local + median
    }

    fun getConsensusEpochHour(): Long {
        return getConsensusTimeMs() / 3600000L
    }

    /**
     * Advances and returns the local Lamport logical clock for outgoing events.
     */
    fun tickLamport(): Long {
        return lamportClock.incrementAndGet()
    }

    /**
     * Witnesses a remote Lamport timestamp and advances the local clock to preserve causality.
     */
    fun witnessLamport(remoteLamport: Long): Long {
        while (true) {
            val current = lamportClock.get()
            val next = maxOf(current, remoteLamport) + 1
            if (lamportClock.compareAndSet(current, next)) {
                return next
            }
        }
    }

    fun currentLamport(): Long = lamportClock.get()

    /**
     * Verifies that a packet's creation and TTL bounds are strictly valid against
     * off-grid network consensus time, preventing replay attacks from drifted or malicious clocks.
     */
    fun isPacketWithinTtl(creationEpoch: Long, ttlExpiry: Long, gracePeriodMs: Long = 15 * 60 * 1000L): Boolean {
        val now = getConsensusTimeMs()
        // Packet cannot be created in the far future (clock-skew replay)
        if (creationEpoch > now + gracePeriodMs) return false
        // Packet cannot be expired beyond grace period
        if (now > ttlExpiry + gracePeriodMs) return false
        return true
    }
}

