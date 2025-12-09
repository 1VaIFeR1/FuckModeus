package com.fuck.modeus.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fuck.modeus.R
import com.fuck.modeus.data.Attendee

class AttendeesAdapter(
    private val onAttendeeClick: (Attendee) -> Unit
) : ListAdapter<Attendee, AttendeesAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_attendee, parent, false)
        return ViewHolder(view, onAttendeeClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onClick: (Attendee) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvAttendeeName)
        private val tvInfo: TextView = itemView.findViewById(R.id.tvAttendeeInfo)

        fun bind(item: Attendee) {
            tvName.text = item.fullName

            val info = StringBuilder(item.roleName)
            if (!item.specialtyCode.isNullOrEmpty()) {
                info.append(" • ").append(item.specialtyCode)
            }
            if (!item.specialtyProfile.isNullOrEmpty()) {
                info.append("\n").append(item.specialtyProfile)
            }
            tvInfo.text = info.toString()

            // КЛИК ПО ЭЛЕМЕНТУ
            itemView.setOnClickListener {
                onClick(item)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Attendee>() {
        override fun areItemsTheSame(oldItem: Attendee, newItem: Attendee) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Attendee, newItem: Attendee) = oldItem == newItem
    }
}