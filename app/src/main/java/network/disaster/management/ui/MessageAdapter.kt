package network.disaster.management.ui

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import network.disaster.management.R
import network.disaster.management.model.FeedMessage
import network.disaster.management.model.MessageType
import network.disaster.management.storage.LowWearCryptoShredder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private var messages: List<FeedMessage>,
    private val onFileClick: ((FeedMessage) -> Unit)? = null,
    private val shredder: LowWearCryptoShredder? = null,
    private val onReplyClick: ((FeedMessage) -> Unit)? = null,
    private val onUserTrustClick: ((FeedMessage) -> Unit)? = null,
    private val onAcceptFriendClick: ((FeedMessage) -> Unit)? = null,
    private val onRejectFriendClick: ((FeedMessage) -> Unit)? = null,
    private val onLocationClick: ((Double, Double, String) -> Unit)? = null
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun updateMessages(newMessages: List<FeedMessage>) {
        this.messages = newMessages
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_feed_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSender: TextView = itemView.findViewById(R.id.tvSender)
        private val tvTrustBadge: TextView = itemView.findViewById(R.id.tvTrustBadge)
        private val tvBadgeEmergency: TextView = itemView.findViewById(R.id.tvBadgeEmergency)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvContent: TextView = itemView.findViewById(R.id.tvContent)

        // Quoted thread reply
        private val layoutReplyQuote: LinearLayout = itemView.findViewById(R.id.layoutReplyQuote)
        private val tvReplySender: TextView = itemView.findViewById(R.id.tvReplySender)
        private val tvReplySnippet: TextView = itemView.findViewById(R.id.tvReplySnippet)

        // Attachments
        private val layoutAttachment: LinearLayout = itemView.findViewById(R.id.layoutAttachment)
        private val ivMediaType: ImageView = itemView.findViewById(R.id.ivMediaType)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvFileSize: TextView = itemView.findViewById(R.id.tvFileSize)
        private val btnOpenFile: Button = itemView.findViewById(R.id.btnOpenFile)

        // Location Attachment
        private val layoutLocationAttachment: LinearLayout = itemView.findViewById(R.id.layoutLocationAttachment)
        private val ivLocationIcon: ImageView = itemView.findViewById(R.id.ivLocationIcon)
        private val tvLocationCoords: TextView = itemView.findViewById(R.id.tvLocationCoords)

        // Action buttons
        private val btnReply: Button = itemView.findViewById(R.id.btnReply)
        private val btnUserTrust: Button = itemView.findViewById(R.id.btnUserTrust)

        // Friend Request interactive container
        private val layoutFriendRequestActions: LinearLayout = itemView.findViewById(R.id.layoutFriendRequestActions)
        private val tvFriendRequestBanner: TextView = itemView.findViewById(R.id.tvFriendRequestBanner)
        private val btnAcceptFriend: Button = itemView.findViewById(R.id.btnAcceptFriend)
        private val btnRejectFriend: Button = itemView.findViewById(R.id.btnRejectFriend)

        private val cardMessage: com.google.android.material.card.MaterialCardView = itemView.findViewById(R.id.cardMessage)

        fun bind(msg: FeedMessage) {
            tvSender.text = msg.senderName
            tvTime.text = timeFormat.format(Date(msg.timestamp))
            tvContent.text = msg.content

            // 1. Quoted Reply Threading
            if (!msg.replyToId.isNullOrEmpty() || !msg.replyToSender.isNullOrEmpty()) {
                layoutReplyQuote.visibility = View.VISIBLE
                tvReplySender.text = "↩️ Replying to @${msg.replyToSender ?: "Civilian"}"
                tvReplySnippet.text = msg.replyToText ?: "Message"
            } else {
                layoutReplyQuote.visibility = View.GONE
            }

            // 2. Trustworthiness & Friend Status Calculation
            val isFriend = shredder?.isFriend(msg.senderName) == true
            val contact = shredder?.getContact(msg.senderName)
            val isHostile = network.disaster.management.security.HostileActorDetector.shared.isSenderHostile(msg.senderName)
            val profile = network.disaster.management.security.HostileActorDetector.shared.getProfile(msg.senderName)

            when {
                isFriend -> {
                    val score = contact?.trustScore ?: 95
                    tvTrustBadge.visibility = View.VISIBLE
                    tvTrustBadge.text = "🛡️ FRIEND ($score%)"
                    tvTrustBadge.setBackgroundColor(0xFF065F46.toInt()) // Emerald dark
                    tvTrustBadge.setTextColor(0xFF6EE7B7.toInt())
                    btnUserTrust.text = "🛡️ Friend (${score}%)"
                    tvSender.setTextColor(0xFF34D399.toInt())
                    cardMessage.strokeColor = 0xFF059669.toInt()
                    cardMessage.strokeWidth = 2
                }
                isHostile -> {
                    tvTrustBadge.visibility = View.VISIBLE
                    tvTrustBadge.text = "🚨 HOSTILE (0%)"
                    tvTrustBadge.setBackgroundColor(0xFF991B1B.toInt())
                    tvTrustBadge.setTextColor(0xFFFCA5A5.toInt())
                    btnUserTrust.text = "🚨 Hostile Infiltrator"
                    tvSender.setTextColor(0xFFEF4444.toInt())
                    cardMessage.strokeColor = 0xFFDC2626.toInt()
                    cardMessage.strokeWidth = 3
                }
                profile?.classification == network.disaster.management.map.ActorClassification.SUSPICIOUS_ACTOR -> {
                    val score = profile.trustScore
                    tvTrustBadge.visibility = View.VISIBLE
                    tvTrustBadge.text = "⚠️ SUSPICIOUS ($score%)"
                    tvTrustBadge.setBackgroundColor(0xFF92400E.toInt())
                    tvTrustBadge.setTextColor(0xFFFCD34D.toInt())
                    btnUserTrust.text = "⚠️ Suspicious ($score%)"
                    tvSender.setTextColor(0xFFFBBF24.toInt())
                    cardMessage.strokeColor = 0xFFD97706.toInt()
                    cardMessage.strokeWidth = 2
                }
                profile?.classification == network.disaster.management.map.ActorClassification.VERIFIED_CIVILIAN -> {
                    val score = profile.trustScore
                    tvTrustBadge.visibility = View.VISIBLE
                    tvTrustBadge.text = "⭐ VERIFIED ($score%)"
                    tvTrustBadge.setBackgroundColor(0xFF1E3A8A.toInt())
                    tvTrustBadge.setTextColor(0xFF93C5FD.toInt())
                    btnUserTrust.text = "⭐ Verified ($score%)"
                    tvSender.setTextColor(0xFF60A5FA.toInt())
                    cardMessage.strokeColor = 0xFF2563EB.toInt()
                    cardMessage.strokeWidth = 1
                }
                else -> {
                    val score = contact?.trustScore ?: profile?.trustScore ?: 70
                    tvTrustBadge.visibility = View.VISIBLE
                    tvTrustBadge.text = "⚪ CIVILIAN ($score%)"
                    tvTrustBadge.setBackgroundColor(0xFF334155.toInt())
                    tvTrustBadge.setTextColor(0xFFCBD5E1.toInt())
                    btnUserTrust.text = "👤 Trust & Friend"
                    tvSender.setTextColor(0xFF00FF66.toInt())
                    cardMessage.strokeColor = 0xFF2E2E2E.toInt()
                    cardMessage.strokeWidth = 1
                }
            }

            // Emergency Badge
            if (msg.isEmergency || msg.type == MessageType.EMERGENCY) {
                tvBadgeEmergency.visibility = View.VISIBLE
                tvBadgeEmergency.text = "DISTRESS"
                tvBadgeEmergency.setBackgroundColor(0xFF742A2A.toInt())
            } else {
                tvBadgeEmergency.visibility = View.GONE
            }

            // 3. Attachments
            if (msg.fileName != null || msg.type in listOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.FILE)) {
                layoutAttachment.visibility = View.VISIBLE
                tvFileName.text = msg.fileName ?: "Attached Media"
                val kb = (msg.fileSize / 1024.0).coerceAtLeast(0.1)
                tvFileSize.text = String.format(Locale.US, "%.1f KB • Pool C Bulk Media", kb)

                when {
                    msg.type == MessageType.IMAGE || msg.mimeType?.startsWith("image/") == true -> {
                        ivMediaType.setImageResource(android.R.drawable.ic_menu_gallery)
                    }
                    msg.type == MessageType.VIDEO || msg.mimeType?.startsWith("video/") == true -> {
                        ivMediaType.setImageResource(android.R.drawable.ic_media_play)
                    }
                    msg.mimeType?.startsWith("audio/") == true -> {
                        ivMediaType.setImageResource(android.R.drawable.ic_btn_speak_now)
                    }
                    else -> {
                        ivMediaType.setImageResource(android.R.drawable.ic_menu_save)
                    }
                }

                val clickAction = View.OnClickListener {
                    onFileClick?.invoke(msg) ?: openLocalFile(msg)
                }
                btnOpenFile.setOnClickListener(clickAction)
                layoutAttachment.setOnClickListener(clickAction)
            } else {
                layoutAttachment.visibility = View.GONE
            }

            // Location Attachment Handling (Mandatory for Emergency, Voluntary for Civilian)
            val contentStr = msg.content ?: ""
            val locMatch = Regex("\\[LOC:([^\\]]+)\\]").find(contentStr)
            if (locMatch != null) {
                layoutLocationAttachment.visibility = View.VISIBLE
                val rawCoords = locMatch.groupValues[1]
                val parsedCoords = network.disaster.management.system.GpsBeaconHelper.parseBeaconCoordinates("LOC:$rawCoords")
                if (msg.isEmergency || msg.type == MessageType.EMERGENCY) {
                    tvLocationCoords.text = "🚨 MANDATORY DISTRESS LOCATION: [LOC:$rawCoords]\n👉 Tap to view on Tactical Map"
                    tvLocationCoords.setTextColor(0xFFFECACA.toInt()) // Red-100
                    layoutLocationAttachment.setBackgroundColor(0xFF451A1A.toInt()) // Dark Red
                    ivLocationIcon.setColorFilter(0xFFEF4444.toInt())
                } else {
                    tvLocationCoords.text = "📍 VOLUNTARY LOCATION ATTACHMENT: [LOC:$rawCoords]\n👉 Tap to view on Tactical Map"
                    tvLocationCoords.setTextColor(0xFFBAE6FD.toInt()) // Sky-200
                    layoutLocationAttachment.setBackgroundColor(0xFF0C2436.toInt()) // Dark Sky
                    ivLocationIcon.setColorFilter(0xFF38BDF8.toInt())
                }
                layoutLocationAttachment.isClickable = true
                layoutLocationAttachment.isFocusable = true
                layoutLocationAttachment.setOnClickListener {
                    if (parsedCoords != null) {
                        onLocationClick?.invoke(parsedCoords.first, parsedCoords.second, msg.senderName)
                    }
                }
            } else {
                layoutLocationAttachment.visibility = View.GONE
            }

            // 4. Friend Request Banner & Accept/Reject
            if (msg.type == MessageType.FRIEND_REQUEST) {
                layoutFriendRequestActions.visibility = View.VISIBLE
                val target = if (!msg.targetPeer.isNullOrEmpty()) " -> @${msg.targetPeer}" else ""
                tvFriendRequestBanner.text = "🤝 FRIEND REQUEST: @${msg.senderName}$target"
                btnAcceptFriend.visibility = View.VISIBLE
                btnRejectFriend.visibility = View.VISIBLE

                btnAcceptFriend.setOnClickListener {
                    onAcceptFriendClick?.invoke(msg)
                }
                btnRejectFriend.setOnClickListener {
                    onRejectFriendClick?.invoke(msg)
                }
            } else if (msg.type == MessageType.FRIEND_ACCEPT) {
                layoutFriendRequestActions.visibility = View.VISIBLE
                val target = if (!msg.targetPeer.isNullOrEmpty()) " -> @${msg.targetPeer}" else ""
                tvFriendRequestBanner.text = "🎉 FRIEND REQUEST ACCEPTED: Connected with @${msg.senderName}$target"
                btnAcceptFriend.visibility = View.GONE
                btnRejectFriend.visibility = View.GONE
            } else if (msg.type == MessageType.FRIEND_REJECT) {
                layoutFriendRequestActions.visibility = View.VISIBLE
                tvFriendRequestBanner.text = "❌ Friend request declined by @${msg.senderName}"
                btnAcceptFriend.visibility = View.GONE
                btnRejectFriend.visibility = View.GONE
            } else {
                layoutFriendRequestActions.visibility = View.GONE
            }

            // 5. Click Actions: Reply & Trust Dialog
            btnReply.setOnClickListener {
                onReplyClick?.invoke(msg)
            }

            val userClick = View.OnClickListener {
                onUserTrustClick?.invoke(msg)
            }
            btnUserTrust.setOnClickListener(userClick)
            tvSender.setOnClickListener(userClick)
            tvTrustBadge.setOnClickListener(userClick)
        }

        private fun openLocalFile(msg: FeedMessage) {
            val path = msg.localFilePath ?: return
            val file = File(path)
            if (!file.exists()) return

            try {
                val context = itemView.context
                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, msg.mimeType ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }
}
