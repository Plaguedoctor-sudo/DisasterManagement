package network.disaster.management.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import network.disaster.management.R
import network.disaster.management.model.CivilGroup

class GroupAdapter(
    private var groups: List<CivilGroup>,
    private val onGroupClick: (CivilGroup) -> Unit
) : RecyclerView.Adapter<GroupAdapter.GroupViewHolder>() {

    fun updateGroups(newGroups: List<CivilGroup>) {
        this.groups = newGroups
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(groups[position])
    }

    override fun getItemCount(): Int = groups.size

    inner class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvGroupName: TextView = itemView.findViewById(R.id.tvGroupName)
        private val tvGroupDesc: TextView = itemView.findViewById(R.id.tvGroupDescription)
        private val tvGroupBadge: TextView = itemView.findViewById(R.id.tvGroupBadge)
        private val ivGroupIcon: ImageView = itemView.findViewById(R.id.ivGroupIcon)

        fun bind(group: CivilGroup) {
            tvGroupName.text = group.name
            tvGroupDesc.text = group.description

            if (group.isBroadcast) {
                tvGroupBadge.text = "BROADCAST"
                tvGroupBadge.setBackgroundColor(0xFF882222.toInt())
                tvGroupBadge.setTextColor(0xFFFFFFFF.toInt())
                ivGroupIcon.setImageResource(android.R.drawable.ic_dialog_alert)
            } else {
                tvGroupBadge.text = "ENCRYPTED"
                tvGroupBadge.setBackgroundColor(0xFF1B3B2B.toInt())
                tvGroupBadge.setTextColor(0xFF00FF66.toInt())
                ivGroupIcon.setImageResource(android.R.drawable.ic_lock_lock)
            }

            itemView.setOnClickListener {
                onGroupClick(group)
            }
        }
    }
}
