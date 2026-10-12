package network.disaster.management.transport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import network.disaster.management.crypto.CredentialSecurityPolicy
import network.disaster.management.security.CitadelDefenseManager
import network.disaster.management.security.NetworkQuarantineManager
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * ApkPropagationServer: Zero-Infrastructure Offline Viral App Sideloading Engine.
 * Hardened with High-Assurance Security Blueprint Measures:
 * 1. Direct Socket Resolution & Anti-Spoofing (CWE-290 Immunity / Blueprint Section 2).
 * 2. Active Deception Honeypots & Automated 24-Hour Quarantine (Blueprint Section 9).
 * 3. Military-STIG Content Security Policy & Protective HTTP Headers (Blueprint Section 10).
 * 4. Dual-Keyed Rate Limiting (Blueprint Section 5).
 * 5. Citadel Defense Mode (DEFCON 1 Enclave Isolation / Blueprint Section 14.E).
 */
class ApkPropagationServer(private val context: Context) {

    companion object {
        private const val TAG = "ApkPropagationServer"
        const val SERVER_PORT = 8080
        @Volatile var shared: ApkPropagationServer? = null

        /**
         * STIG-Compliant Security Response Headers (Blueprint Section 10).
         */
        val STIG_SECURITY_HEADERS = """
            X-DNS-Prefetch-Control: on
            Strict-Transport-Security: max-age=63072000; includeSubDomains; preload
            X-Frame-Options: DENY
            X-Content-Type-Options: nosniff
            X-XSS-Protection: 1; mode=block
            Referrer-Policy: strict-origin-when-cross-origin
            Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()
            Cross-Origin-Opener-Policy: same-origin
            Cross-Origin-Resource-Policy: same-origin
            Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none';
        """.trimIndent().lines().joinToString("\r\n") { it.trim() } + "\r\n"
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val rateLimiter = MeshRateLimiter(peerLimitPerSec = 15, channelLimitPerSec = 50)

    fun start() {
        if (isRunning) return
        isRunning = true

        thread(start = true, name = "ApkPropagation-Server", isDaemon = true) {
            try {
                serverSocket = ServerSocket(SERVER_PORT)
                Log.i(TAG, "Offline APK propagation server listening on port $SERVER_PORT")

                while (isRunning && serverSocket != null && !serverSocket!!.isClosed) {
                    try {
                        val client = serverSocket!!.accept()
                        thread(start = true, isDaemon = true) {
                            handleClient(client)
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not bind server port $SERVER_PORT: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10000

            // 1. Direct Socket Resolution (CWE-290 Immunity / Blueprint Section 2.1)
            // Authoritative remote IP directly from physical TCP socket, ignoring forged headers.
            val rawIp = socket.inetAddress?.hostAddress ?: "127.0.0.1"
            val verifiedRemoteIp = rawIp.replace("^/+".toRegex(), "").replace("::ffff:", "").trim()

            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val rawPath = parts[1]

            // 2. Ingress Header Sanitization (Blueprint Section 2.2)
            // Read headers to prevent pipe clogging, explicitly ignoring user-supplied X-Forwarded-For / Host bypasses
            var line: String?
            while (true) {
                line = input.readLine()
                if (line.isNullOrEmpty()) break
            }

            // 3. Citadel Defense Mode Lockdown (Blueprint Section 14.E)
            if (!CitadelDefenseManager.isIngressPermitted(verifiedRemoteIp)) {
                sendHttpError(output, 503, "DEFCON 1 Citadel Lockdown: Remote Ingress Disabled")
                return
            }

            // 4. Automated Network Quarantine Check (Blueprint Section 9.2)
            if (NetworkQuarantineManager.isQuarantined(verifiedRemoteIp)) {
                sendHttpError(output, 403, "Forbidden: Host Quarantined under Anti-Reconnaissance Policy")
                return
            }

            // 5. Active Deception Honeypot Trap Detection (Blueprint Section 9.1)
            if (NetworkQuarantineManager.isCanaryPath(rawPath)) {
                NetworkQuarantineManager.quarantineIp(
                    verifiedRemoteIp,
                    "Reconnaissance probe detected hitting honeypot path: $rawPath"
                )
                sendHttpError(output, 403, "Access Denied: Security Containment Engaged")
                return
            }

            // 5b. Inline Threat IDS Inspection (Blueprint Section 2.2 & Step 1)
            val threatCheck = network.disaster.management.security.ActiveAdversaryDefense.inspectPayload(rawPath)
            if (threatCheck.isThreat) {
                NetworkQuarantineManager.quarantineIp(
                    verifiedRemoteIp,
                    "Heuristic signature blocked (${threatCheck.threatCategory}): ${threatCheck.details}"
                )
                network.disaster.management.security.ActiveAdversaryDefense.handleAttackerTarpit(output, maxChunks = 4, delayMs = 250L)
                return
            }

            // 6. Dual-Keyed Anti-Botnet Rate Limiting (Blueprint Section 5)
            if (!rateLimiter.allowPacket(verifiedRemoteIp, "HTTP_SIDELOAD")) {
                sendHttpError(output, 429, "Too Many Requests: Rate Limit Exceeded")
                return
            }

            if (rawPath == "/download" || rawPath.startsWith("/download?")) {
                serveApkDownload(output)
            } else {
                servePortalHtml(output)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client handle error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendHttpError(output: OutputStream, statusCode: Int, message: String) {
        val body = """
            <!DOCTYPE html>
            <html><head><title>$statusCode $message</title></head>
            <body style="background:#0B1120;color:#FCA5A5;font-family:sans-serif;padding:40px;text-align:center;">
              <h2>🚨 SECURITY ENFORCEMENT ($statusCode)</h2>
              <p>$message</p>
            </body></html>
        """.trimIndent()
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $statusCode $message\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                STIG_SECURITY_HEADERS +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun servePortalHtml(output: OutputStream) {
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>🚨 DISASTER MESH — Offline Network</title>
              <style>
                body { background-color: #0B1120; color: #F8FAFC; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; text-align: center; padding: 20px; margin: 0; }
                .container { max-width: 500px; margin: 0 auto; background: #1E293B; border-radius: 16px; padding: 24px; box-shadow: 0 10px 25px rgba(0,0,0,0.5); border: 1px solid #334155; }
                h1 { color: #00FF66; font-size: 22px; margin-top: 0; }
                .badge { display: inline-block; background: #991B1B; color: #FCA5A5; padding: 4px 12px; border-radius: 9999px; font-weight: bold; font-size: 11px; margin-bottom: 12px; }
                p { font-size: 14px; line-height: 1.5; color: #CBD5E1; }
                .btn { display: block; background: #00FF66; color: #000000; font-weight: 800; font-size: 16px; padding: 18px 20px; border-radius: 12px; text-decoration: none; margin: 24px 0 16px 0; box-shadow: 0 4px 14px rgba(0,255,102,0.4); text-transform: uppercase; }
                .btn:active { background: #00CC52; }
                .steps { text-align: left; background: #0F172A; border-radius: 12px; padding: 16px; margin-top: 16px; border: 1px solid #1E293B; font-size: 13px; color: #94A3B8; }
                .steps ol { margin: 8px 0 0 0; padding-left: 20px; }
                .steps li { margin-bottom: 8px; }
                .highlight { color: #60A5FA; font-weight: bold; }
                .footer { font-size: 11px; color: #64748B; margin-top: 20px; }
              </style>
            </head>
            <body>
              <div class="container">
                <div class="badge">OFF-GRID SURVIVAL NETWORK</div>
                <h1>🚨 DISASTER MESH PORTAL</h1>
                <p>Cellular towers and internet are offline. Download this app to join nearby survivors on the decentralized, battery-efficient disaster mesh network.</p>
                
                <a class="btn" href="/download">⬇️ DOWNLOAD DISASTER APP (APK)</a>

                <div class="steps">
                  <strong style="color: #F1F5F9;">📲 Fast Installation Instructions:</strong>
                  <ol>
                    <li>Tap <span class="highlight">Download Disaster App</span> above.</li>
                    <li>When download completes, tap <span class="highlight">Open</span> or locate <code>DisasterManagement.apk</code> in your Downloads folder.</li>
                    <li>If Android displays <em>"Install unknown apps"</em>, toggle <span class="highlight">Allow from this source</span>.</li>
                    <li>Tap <span class="highlight">Install</span> and open the app.</li>
                    <li>Enable Wi-Fi and Bluetooth to immediately connect with other survivors and send SOS reports!</li>
                  </ol>
                </div>
                <div class="footer">DISASTER RELIEF MESH v1.0 • P2P ENCRYPTED ZERO-INTERNET MESH</div>
              </div>
            </body>
            </html>
        """.trimIndent()

        val bytes = html.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                STIG_SECURITY_HEADERS +
                "Connection: close\r\n\r\n"

        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun serveApkDownload(output: OutputStream) {
        val apkFile = File(context.applicationInfo.sourceDir)
        if (!apkFile.exists() || !apkFile.canRead()) {
            val error = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            output.write(error.toByteArray(Charsets.US_ASCII))
            output.flush()
            return
        }

        val fileLength = apkFile.length()
        val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/vnd.android.package-archive\r\n" +
                "Content-Disposition: attachment; filename=\"DisasterManagement.apk\"\r\n" +
                "Content-Length: $fileLength\r\n" +
                STIG_SECURITY_HEADERS +
                "Connection: close\r\n\r\n"

        output.write(headers.toByteArray(Charsets.US_ASCII))

        FileInputStream(apkFile).use { fis ->
            val buf = ByteArray(65536)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
            }
        }
        output.flush()
        Log.i(TAG, "Successfully streamed APK ($fileLength bytes) to offline survivor browser!")
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            // First priority: active Wi-Fi AP or Wi-Fi Direct interface (ap0, wlan0, p2p, swlan)
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                if (name.contains("ap") || name.contains("p2p") || name.contains("wlan") || name.contains("hotspot") || name.contains("rndis")) {
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            val ip = addr.hostAddress
                            if (!ip.isNullOrEmpty() && ip != "127.0.0.1") {
                                return ip
                            }
                        }
                    }
                }
            }
            // Second priority: any other non-loopback active IPv4 address
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val ip = addr.hostAddress
                        if (!ip.isNullOrEmpty() && ip != "127.0.0.1") {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.43.1" // Standard Android Wi-Fi Hotspot Gateway IP default
    }

    fun getPortalUrl(): String {
        val port = serverSocket?.localPort ?: SERVER_PORT
        return "http://${getLocalIpAddress()}:$port"
    }

    fun generatePortalQr(size: Int = 512): Bitmap? {
        return try {
            val url = getPortalUrl()
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }

    fun exportApkForSharing(): File? {
        return try {
            val src = File(context.applicationInfo.sourceDir)
            val target = File(context.cacheDir, "DisasterManagement.apk")
            if (target.exists() && target.length() == src.length()) {
                return target
            }
            src.copyTo(target, overwrite = true)
            target
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy APK for sharing: ${e.message}")
            null
        }
    }
}
