package com.fuck.modeus.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fuck.modeus.R
import com.fuck.modeus.data.ScheduleItem

class ScheduleAdapter(
    private val onLongItemClick: (ScheduleItem) -> Unit
) : ListAdapter<ScheduleItem, ScheduleAdapter.ScheduleViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScheduleViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_schedule, parent, false)
        return return ScheduleViewHolder(view, onLongItemClick)
    }

    override fun onBindViewHolder(holder: ScheduleViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    class ScheduleViewHolder(
        itemView: View,
        private val onLongItemClick: (ScheduleItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        // Элементы из item_schedule.xml
        private val tvSubject: TextView = itemView.findViewById(R.id.tvSubject)
        private val tvModule: TextView = itemView.findViewById(R.id.tvModule)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvTeacher: TextView = itemView.findViewById(R.id.tvTeacher)
        private val tvRoom: TextView = itemView.findViewById(R.id.tvRoom)
        private val tvType: TextView = itemView.findViewById(R.id.tvType)

        // Метод bind для ScheduleItem
        fun bind(item: ScheduleItem) {
            tvSubject.text = item.subject

            // Логика для отображения или скрытия модуля
            if (!item.moduleShortName.isNullOrBlank()) {
                tvModule.visibility = View.VISIBLE
                tvModule.text = "📚 ${item.moduleShortName}"
            } else {
                tvModule.visibility = View.GONE
            }

            tvTime.text = "⏰ ${item.startTime} - ${item.endTime} | 📅 ${item.date}"
            tvTeacher.text = "🧑‍🏫 ${item.teacher}"
            tvRoom.text = "🚪 ${item.room}"

            val typeText = when (item.type) {
                "Лекция" -> "🎓 Лекция"
                "Практика" -> "✍️ Практика"
                "Лабораторная" -> "🔬 Лабораторная"
                else -> "⚡ ${item.type}"
            }
            tvType.text = typeText
            itemView.setOnLongClickListener {
                onLongItemClick(item) // Вызываем действие, которое нам передали
                true // Возвращаем true, чтобы система знала, что мы обработали клик
            }
            itemView.setOnLongClickListener {
                onLongItemClick(item) // Вызываем действие, которое нам передали
                true // Возвращаем true, чтобы система знала, что мы обработали клик
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ScheduleItem>() {
        override fun areItemsTheSame(oldItem: ScheduleItem, newItem: ScheduleItem): Boolean {
            return oldItem.fullStartDate == newItem.fullStartDate && oldItem.subject == newItem.subject
        }

        override fun areContentsTheSame(oldItem: ScheduleItem, newItem: ScheduleItem): Boolean {
            return oldItem == newItem
        }
    }
}