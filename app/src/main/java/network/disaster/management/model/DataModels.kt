package network.disaster.management.model

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    FILE,
    EMERGENCY
}

data class CivilGroup(
    val id: String,
    val name: String,
    val description: String,
    val isBroadcast: Boolean,
    val secretKey: ByteArray,
    val createdEpoch: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CivilGroup
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

data class FeedMessage(
    val id: String,
    val groupId: String,
    val senderName: String,
    val content: String,
    val type: MessageType,
    val fileName: String? = null,
    val fileSize: Long = 0L,
    val mimeType: String? = null,
    val localFilePath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isEmergency: Boolean = false
)

data class FileManifest(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val totalChunks: Int,
    val chunkSize: Int
)
