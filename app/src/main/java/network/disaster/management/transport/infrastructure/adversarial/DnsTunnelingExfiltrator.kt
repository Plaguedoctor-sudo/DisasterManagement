package network.disaster.management.transport.infrastructure.adversarial

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class DnsTunnelingExfiltrator(private val domain: String = "egress.mesh-fallback.net") {

    companion object {
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    }

    suspend fun transmitDnsFastPath(dnsServer: InetAddress, frame: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val base32 = toBase32(frame)
            // Divide into max 50-char labels to respect RFC 1035 (max 63 chars per label)
            val chunk = base32.take(50)
            val nonce = (System.currentTimeMillis() and 0xFFFF).toString(16)
            val fqdn = "$chunk.$nonce.$domain"
            val query = buildDnsQuery(fqdn)

            DatagramSocket().use { s ->
                s.soTimeout = 3000
                val p = DatagramPacket(query, query.size, dnsServer, 53)
                s.send(p)
                val resp = ByteArray(512)
                s.receive(DatagramPacket(resp, resp.size))
                resp.isNotEmpty()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun buildDnsQuery(fqdn: String): ByteArray {
        val buf = ByteBuffer.allocate(512)
        buf.putShort((0..65535).random().toShort()) // Transaction ID
        buf.putShort(0x0100.toShort())              // Standard query with recursion desired
        buf.putShort(1.toShort())                   // Questions: 1
        buf.putShort(0.toShort())                   // Answer RRs: 0
        buf.putShort(0.toShort())                   // Authority RRs: 0
        buf.putShort(0.toShort())                   // Additional RRs: 0

        for (part in fqdn.split(".")) {
            if (part.isEmpty()) continue
            val b = part.toByteArray(Charsets.US_ASCII)
            buf.put(b.size.toByte())
            buf.put(b)
        }
        buf.put(0.toByte())                         // Root zero length label
        buf.putShort(0x0010.toShort())              // Type TXT (16)
        buf.putShort(0x0001.toShort())              // Class IN (1)

        val res = ByteArray(buf.position())
        System.arraycopy(buf.array(), 0, res, 0, buf.position())
        return res
    }

    fun toBase32(d: ByteArray): String {
        val out = StringBuilder()
        var buf = 0
        var bits = 0
        for (b in d) {
            buf = (buf shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buf ushr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buf shl (5 - bits)) and 0x1F])
        }
        return out.toString()
    }

    fun fromBase32(s: String): ByteArray {
        val clean = s.lowercase().trimEnd('=')
        val out = ArrayList<Byte>()
        var buf = 0
        var bits = 0
        for (c in clean) {
            val idx = ALPHABET.indexOf(c)
            if (idx < 0) continue
            buf = (buf shl 5) or idx
            bits += 5
            if (bits >= 8) {
                out.add(((buf ushr (bits - 8)) and 0xFF).toByte())
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
