package com.fuck.modeus.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fuck.modeus.R
import com.fuck.modeus.data.WeekItem

class WeeksAdapter(private val onItemClick: (WeekItem) -> Unit) :
    ListAdapter<WeekItem, WeeksAdapter.WeekViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WeekViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_week, parent, false)
        return WeekViewHolder(view as TextView, onItemClick)
    }

    override fun onBindViewHolder(holder: WeekViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class WeekViewHolder(private val textView: TextView, private val onItemClick: (WeekItem) -> Unit) :
        RecyclerView.ViewHolder(textView) {
        fun bind(item: WeekItem) {
            textView.text = item.displayableString
            // Меняем цвет фона для выделенного элемента
            textView.setBackgroundResource(if (item.isSelected) R.drawable.bg_rounded_rect_selected else R.drawable.bg_rounded_rect)
            textView.setOnClickListener { onItemClick(item) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<WeekItem>() {
        override fun areItemsTheSame(oldItem: WeekItem, newItem: WeekItem): Boolean =
            oldItem.weekNumber == newItem.weekNumber && oldItem.startDate == newItem.startDate

        override fun areContentsTheSame(oldItem: WeekItem, newItem: WeekItem): Boolean =
            oldItem == newItem
    }
}