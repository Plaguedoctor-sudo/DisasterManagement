package network.disaster.management.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import network.disaster.management.model.CivilGroup

object GroupQrHelper {

    fun formatGroupToQrString(group: CivilGroup): String {
        val keyB64 = try {
            Base64.encodeToString(group.secretKey, Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().withoutPadding().encodeToString(group.secretKey)
        }
        val safeName = group.name.replace("|", "-")
        val safeDesc = group.description.replace("|", "-")
        val broadcastFlag = if (group.isBroadcast) "1" else "0"
        return "DISASTER_GRP:${group.id}|$safeName|$safeDesc|$keyB64|$broadcastFlag"
    }

    fun generateGroupQrBitmap(group: CivilGroup, size: Int = 512): Bitmap? {
        return try {
            val content = formatGroupToQrString(group)
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
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
}
