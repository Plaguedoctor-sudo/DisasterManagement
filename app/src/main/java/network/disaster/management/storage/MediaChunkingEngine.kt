package network.disaster.management.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import network.disaster.management.crypto.EnvelopeFormat
import network.disaster.management.model.FileManifest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

class MediaChunkingEngine(
    private val context: Context? = null,
    private val shredder: LowWearCryptoShredder? = null
) {
    companion object {
        const val MAX_CHUNK_PAYLOAD = 2800 // Fits within 2864-byte payload limit
        const val FILE_HEADER_MAGIC: Short = 0x464D.toShort() // 'FM' (File Media)
    }

    data class ChunkHeader(
        val fileId: String,
        val chunkIndex: Int,
        val totalChunks: Int,
        val data: ByteArray
    )

    data class ChunkPackage(
        val fileId: String,
        val chunkIndex: Int,
        val totalChunks: Int,
        val chunkData: ByteArray,
        val packet4k: ByteArray
    )

    fun chunkFileFromUri(uri: Uri): Pair<FileManifest, List<ChunkPackage>> {
        val (fileName, _, mimeType) = resolveMetadata(uri)
        val fileBytes = readFileBytes(uri)
        return chunkBytes(fileBytes, fileName, mimeType)
    }

    fun chunkBytes(fileBytes: ByteArray, fileName: String, mimeType: String): Pair<FileManifest, List<ChunkPackage>> {
        val fileId = UUID.randomUUID().toString().replace("-", "").take(16)
        val totalChunks = ((fileBytes.size + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD).coerceAtLeast(1)
        val manifest = FileManifest(
            fileId = fileId,
            fileName = fileName,
            fileSize = fileBytes.size.toLong(),
            mimeType = mimeType,
            totalChunks = totalChunks,
            chunkSize = MAX_CHUNK_PAYLOAD
        )

        val packages = mutableListOf<ChunkPackage>()
        for (i in 0 until totalChunks) {
            val start = i * MAX_CHUNK_PAYLOAD
            val end = (start + MAX_CHUNK_PAYLOAD).coerceAtMost(fileBytes.size)
            val slice = fileBytes.copyOfRange(start, end)

            // Header: MAGIC (2B) + CHUNK_IDX (4B) + TOTAL_CHUNKS (4B) + FILE_ID (16B) + DATA_LEN (4B) = 30 bytes
            val buf = ByteBuffer.allocate(30 + slice.size).order(ByteOrder.BIG_ENDIAN)
            buf.putShort(FILE_HEADER_MAGIC)
            buf.putInt(i)
            buf.putInt(totalChunks)
            buf.put(fileId.toByteArray(Charsets.US_ASCII).copyOf(16))
            buf.putInt(slice.size)
            buf.put(slice)
            val packetBody = buf.array()

            // Pad to uniform 4,096-byte invariant profile
            val packet4k = ByteBuffer.allocate(EnvelopeFormat.TIER_S_SIZE).order(ByteOrder.BIG_ENDIAN)
            packet4k.putShort(EnvelopeFormat.MAGIC_HEADER)
            packet4k.put(EnvelopeFormat.PROTO_VERSION_TIER1)
            packet4k.put((EnvelopeFormat.FLAG_ENCRYPTED.toInt() or EnvelopeFormat.FLAG_FILE_CHUNK.toInt()).toByte())
            packet4k.put(7.toByte()) // hopLimit
            packet4k.put(ByteArray(3))
            packet4k.putLong(System.currentTimeMillis())
            packet4k.putLong(System.currentTimeMillis() + 86400000L)
            packet4k.putInt(0) // PoW diff
            packet4k.putInt(0) // PoW nonce
            val chunkBundleId = MessageDigest.getInstance("SHA-256").digest(
                "$fileId:$i".toByteArray(Charsets.UTF_8)
            )
            packet4k.put(chunkBundleId)
            packet4k.put(ByteArray(16))
            packet4k.put(ByteArray(32))
            packet4k.put(ByteArray(1088))
            packet4k.put(ByteArray(12))
            packet4k.putInt(packetBody.size)
            packet4k.put(packetBody.copyOf(2864))
            packet4k.put(ByteArray(16))

            packages.add(ChunkPackage(fileId, i, totalChunks, slice, packet4k.array()))
        }
        return Pair(manifest, packages)
    }

    fun parseChunkHeader(rawChunkPayload: ByteArray): ChunkHeader? {
        if (rawChunkPayload.size < 30) return null
        val buf = ByteBuffer.wrap(rawChunkPayload).order(ByteOrder.BIG_ENDIAN)
        val magic = buf.short
        if (magic != FILE_HEADER_MAGIC) return null

        val chunkIdx = buf.int
        val totalChunks = buf.int
        val idBytes = ByteArray(16)
        buf.get(idBytes)
        val fileId = String(idBytes, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
        val dataLen = buf.int
        if (buf.remaining() < dataLen) return null

        val data = ByteArray(dataLen)
        buf.get(data)
        return ChunkHeader(fileId, chunkIdx, totalChunks, data)
    }

    fun ingestChunk(rawChunkPayload: ByteArray): Boolean {
        val header = parseChunkHeader(rawChunkPayload) ?: return false
        return shredder?.saveFileChunk(header.fileId, header.chunkIndex, header.totalChunks, header.data) ?: false
    }

    fun tryReassembleFile(fileId: String, fileName: String, totalChunks: Int): File? {
        val ctx = context ?: return null
        val shr = shredder ?: return null
        val outDir = File(ctx.filesDir, "received_media").apply { mkdirs() }
        val safeFileName = File(fileName).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outFile = File(outDir, "${fileId}_$safeFileName")
        if (!outFile.canonicalPath.startsWith(outDir.canonicalPath)) {
            return null // Abort: path traversal attempt detected
        }
        val success = shr.reassembleFile(fileId, totalChunks, outFile)
        return if (success) outFile else null
    }

    private fun resolveMetadata(uri: Uri): Triple<String, Long, String> {
        val ctx = context ?: return Triple("attachment_${System.currentTimeMillis()}", 0L, "application/octet-stream")
        var name = "attachment_${System.currentTimeMillis()}"
        var size = 0L
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"

        ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
        return Triple(name, size, mime)
    }

    private fun readFileBytes(uri: Uri): ByteArray {
        val ctx = context ?: return ByteArray(0)
        val stream: InputStream? = ctx.contentResolver.openInputStream(uri)
        val byteBuffer = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        stream?.use { input ->
            var len: Int
            while (input.read(buffer).also { len = it } != -1) {
                byteBuffer.write(buffer, 0, len)
            }
        }
        return byteBuffer.toByteArray()
    }
}
