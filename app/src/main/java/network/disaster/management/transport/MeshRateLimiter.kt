package network.disaster.management.transport

import java.util.concurrent.ConcurrentHashMap

/**
 * Dual-Keyed Mesh Flood Protection & Rate Limiter (Blueprint Section 5).
 * Prevents radio exhaustion, transceiver buffer flooding, and CPU denial-of-service
 * by tracking sliding-window limits across physical peer addresses and channel types.
 */
class MeshRateLimiter(
    private val peerLimitPerSec: Int = 20,
    private val channelLimitPerSec: Int = 60
) {
    private data class RateWindow(var count: Int, var windowStartMs: Long)

    private val peerWindows = ConcurrentHashMap<String, RateWindow>()
    private val channelWindows = ConcurrentHashMap<String, RateWindow>()

    @Synchronized
    fun allowPacket(peerId: String, channelOrType: String): Boolean {
        val now = System.currentTimeMillis()

        // 1. Peer-keyed sliding rate window
        val peerEntry = peerWindows.compute(peerId) { _, existing ->
            if (existing == null || now - existing.windowStartMs >= 1000L) {
                RateWindow(1, now)
            } else {
                existing.count++
                existing
            }
        }

        if ((peerEntry?.count ?: 0) > peerLimitPerSec) {
            return false // Peer exceeded packet-per-second burst limit
        }

        // 2. Channel/Type-keyed sliding rate window
        val channelEntry = channelWindows.compute(channelOrType) { _, existing ->
            if (existing == null || now - existing.windowStartMs >= 1000L) {
                RateWindow(1, now)
            } else {
                existing.count++
                existing
            }
        }

        if ((channelEntry?.count ?: 0) > channelLimitPerSec) {
            return false // Channel aggregate burst limit exceeded
        }

        // Periodic maintenance purge of stale entries (older than 10 seconds)
        if (peerWindows.size > 256) {
            val staleThreshold = now - 10000L
            peerWindows.entries.removeIf { it.value.windowStartMs < staleThreshold }
            channelWindows.entries.removeIf { it.value.windowStartMs < staleThreshold }
        }

        return true
    }
}
