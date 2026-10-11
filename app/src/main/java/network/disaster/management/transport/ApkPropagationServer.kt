package network.disaster.management.transport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * ApkPropagationServer: Zero-Infrastructure Offline Viral App Sideloading Engine.
 *
 * In disaster zones where cellular towers and Google Play are destroyed, survivors
 * cannot communicate until they have the DisasterManagement app.
 *
 * This server runs an ultra-light embedded HTTP daemon (port 8080) on the Android device:
 * 1. Serves an emergency captive portal page over Wi-Fi Hotspot / Wi-Fi Direct.
 * 2. Streams the running application's own signed APK (`applicationInfo.sourceDir`) directly
 *    to any non-app survivor's standard web browser (Chrome/Samsung/Safari/Firefox).
 * 3. Provides dynamic QR code generation pointing to http://<IP>:8080/download for camera scan sideloading.
 */
class ApkPropagationServer(private val context: Context) {

    companion object {
        private const val TAG = "ApkPropagationServer"
        const val SERVER_PORT = 8080
        @Volatile var shared: ApkPropagationServer? = null
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false

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
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1]

            // Drain headers
            var line: String?
            while (true) {
                line = input.readLine()
                if (line.isNullOrEmpty()) break
            }

            if (path == "/download" || path.startsWith("/download?")) {
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
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.43.1" // Standard Android Wi-Fi Hotspot Gateway IP
    }

    fun getPortalUrl(): String {
        return "http://${getLocalIpAddress()}:$SERVER_PORT"
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
