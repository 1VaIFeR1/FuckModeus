package com.fuck.modeus.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fuck.modeus.R
import com.fuck.modeus.data.DayItem

class DaysAdapter(private val onItemClick: (DayItem) -> Unit) :
    ListAdapter<DayItem, DaysAdapter.DayViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_day, parent, false)
        return DayViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DayViewHolder(itemView: View, private val onItemClick: (DayItem) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private val tvDayName: TextView = itemView.findViewById(R.id.tvDayName)
        private val tvDayNumber: TextView = itemView.findViewById(R.id.tvDayNumber)

        fun bind(item: DayItem) {
            tvDayName.text = item.dayOfWeek
            tvDayNumber.text = item.dayOfMonth
            // Меняем цвет фона для выделенного элемента
            tvDayNumber.setBackgroundResource(if (item.isSelected) R.drawable.bg_day_of_week else android.R.color.transparent)
            itemView.setOnClickListener { onItemClick(item) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DayItem>() {
        override fun areItemsTheSame(oldItem: DayItem, newItem: DayItem): Boolean =
            oldItem.date == newItem.date

        override fun areContentsTheSame(oldItem: DayItem, newItem: DayItem): Boolean =
            oldItem == newItem
    }
}