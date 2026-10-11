package network.disaster.management.model

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    FILE,
    EMERGENCY,
    FRIEND_REQUEST,
    FRIEND_ACCEPT,
    FRIEND_REJECT
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
    val isEmergency: Boolean = false,
    val replyToId: String? = null,
    val replyToSender: String? = null,
    val replyToText: String? = null,
    val targetPeer: String? = null
)

data class CivilianContact(
    val peerId: String,
    val displayName: String,
    val isFriend: Boolean = true,
    val trustScore: Int = 85, // 0 - 100
    val trustBadge: String = "FRIEND", // FRIEND, FIRST_RESPONDER, NEUTRAL, FLAGGED
    val notes: String = "",
    val addedEpoch: Long = System.currentTimeMillis(),
    val friendStatus: String = "NONE" // NONE, PENDING_SENT, PENDING_RECEIVED, ACCEPTED, REJECTED
)

data class FileManifest(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val totalChunks: Int,
    val chunkSize: Int
)
