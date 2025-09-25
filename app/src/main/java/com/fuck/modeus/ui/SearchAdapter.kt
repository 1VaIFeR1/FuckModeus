package com.fuck.modeus.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fuck.modeus.R
import com.fuck.modeus.data.ScheduleTarget

class SearchAdapter(
    private val onItemClick: (ScheduleTarget) -> Unit,
    private val onPinClick: (ScheduleTarget) -> Unit
) : ListAdapter<ScheduleTarget, SearchAdapter.SearchViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
        return SearchViewHolder(view, onItemClick, onPinClick)
    }

    override fun onBindViewHolder(holder: SearchViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SearchViewHolder(
        itemView: View,
        private val onItemClick: (ScheduleTarget) -> Unit,
        private val onPinClick: (ScheduleTarget) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val ivPin: ImageView = itemView.findViewById(R.id.ivPin)

        fun bind(target: ScheduleTarget) {
            tvName.text = target.name

            // ВОТ КЛЮЧЕВАЯ ЛОГИКА
            // Если закреплено - используем желтую иконку, если нет - серую.
            val pinIcon = if (target.isPinned) {
                R.drawable.ic_star_filled_pinned
            } else {
                R.drawable.ic_star_filled
            }
            ivPin.setImageResource(pinIcon)

            itemView.setOnClickListener { onItemClick(target) }
            ivPin.setOnClickListener { onPinClick(target) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ScheduleTarget>() {
        override fun areItemsTheSame(oldItem: ScheduleTarget, newItem: ScheduleTarget): Boolean =
            oldItem.person_id == newItem.person_id

        override fun areContentsTheSame(oldItem: ScheduleTarget, newItem: ScheduleTarget): Boolean =
            oldItem == newItem
    }
}