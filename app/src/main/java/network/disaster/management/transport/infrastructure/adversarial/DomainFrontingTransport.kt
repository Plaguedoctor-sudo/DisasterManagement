package network.disaster.management.transport.infrastructure.adversarial

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class DomainFrontingTransport(
    private val allowedFront: String = "bank-payment-gateway.edge-cdn.net",
    private val realHost: String = "hidden-relay.mesh-ingress.org",
    private val cdnIp: String = "104.16.132.229"
) {
    suspend fun transmitFronted(bundle4k: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val raw = Socket().apply {
                connect(InetSocketAddress(cdnIp, 443), 5000)
                soTimeout = 5000
            }
            val ssl = sslFactory.createSocket(raw, allowedFront, 443, true) as SSLSocket

            val params = SSLParameters()
            params.serverNames = listOf(SNIHostName(allowedFront))
            ssl.sslParameters = params
            ssl.startHandshake()

            val req = "POST /v1/drop HTTP/1.1\r\n" +
                    "Host: $realHost\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Content-Length: ${bundle4k.size}\r\n" +
                    "Connection: close\r\n\r\n"

            ssl.outputStream.write(req.toByteArray(Charsets.US_ASCII))
            ssl.outputStream.write(bundle4k)
            ssl.outputStream.flush()

            val resp = ByteArray(256)
            val read = ssl.inputStream.read(resp)
            ssl.close()
            if (read <= 0) return@withContext false
            String(resp, 0, read).startsWith("HTTP/1.1 200")
        } catch (_: Exception) {
            false
        }
    }
}
