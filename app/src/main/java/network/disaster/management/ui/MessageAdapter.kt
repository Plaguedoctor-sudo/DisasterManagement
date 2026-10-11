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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private var messages: List<FeedMessage>,
    private val onFileClick: ((FeedMessage) -> Unit)? = null
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
        private val tvBadgeEmergency: TextView = itemView.findViewById(R.id.tvBadgeEmergency)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvContent: TextView = itemView.findViewById(R.id.tvContent)
        private val layoutAttachment: LinearLayout = itemView.findViewById(R.id.layoutAttachment)
        private val ivMediaType: ImageView = itemView.findViewById(R.id.ivMediaType)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvFileSize: TextView = itemView.findViewById(R.id.tvFileSize)
        private val btnOpenFile: Button = itemView.findViewById(R.id.btnOpenFile)

        private val cardMessage: com.google.android.material.card.MaterialCardView = itemView.findViewById(R.id.cardMessage)

        fun bind(msg: FeedMessage) {
            tvSender.text = msg.senderName
            tvTime.text = timeFormat.format(Date(msg.timestamp))
            tvContent.text = msg.content

            val isHostile = network.disaster.management.security.HostileActorDetector.shared.isSenderHostile(msg.senderName)
            val profile = network.disaster.management.security.HostileActorDetector.shared.getProfile(msg.senderName)

            if (isHostile) {
                tvBadgeEmergency.visibility = View.VISIBLE
                tvBadgeEmergency.text = "🚨 HOSTILE INFILTRATOR"
                tvBadgeEmergency.setBackgroundColor(0xFFB71C1C.toInt())
                tvSender.setTextColor(0xFFFF2222.toInt())
                tvSender.text = "⚠️ ${msg.senderName} [COUNTER-INTEL DETECTED]"
                cardMessage.strokeColor = 0xFFFF2222.toInt()
                cardMessage.strokeWidth = 3
            } else if (profile?.classification == network.disaster.management.map.ActorClassification.SUSPICIOUS_ACTOR) {
                tvBadgeEmergency.visibility = View.VISIBLE
                tvBadgeEmergency.text = "⚠️ SUSPICIOUS ACTOR (${profile.trustScore}%)"
                tvBadgeEmergency.setBackgroundColor(0xFFE65100.toInt())
                tvSender.setTextColor(0xFFFFB300.toInt())
                cardMessage.strokeColor = 0xFFE65100.toInt()
                cardMessage.strokeWidth = 2
            } else if (msg.isEmergency || msg.type == MessageType.EMERGENCY) {
                tvBadgeEmergency.visibility = View.VISIBLE
                tvBadgeEmergency.text = "DISTRESS"
                tvBadgeEmergency.setBackgroundColor(0xFF742A2A.toInt())
                tvSender.setTextColor(0xFFFF4444.toInt())
                cardMessage.strokeColor = 0xFF2E2E2E.toInt()
                cardMessage.strokeWidth = 1
            } else {
                tvBadgeEmergency.visibility = View.GONE
                tvSender.setTextColor(0xFF00FF66.toInt())
                cardMessage.strokeColor = 0xFF2E2E2E.toInt()
                cardMessage.strokeWidth = 1
            }

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
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }
}
