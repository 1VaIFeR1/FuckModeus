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

private const val VIEW_TYPE_NORMAL = 1
private const val VIEW_TYPE_EMPTY = 2

// Адаптер больше не принимает никаких аргументов в конструктор
class ScheduleAdapter : ListAdapter<ScheduleItem, RecyclerView.ViewHolder>(DiffCallback()) {

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).subject == "Нет пары") VIEW_TYPE_EMPTY else VIEW_TYPE_NORMAL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_NORMAL) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_schedule, parent, false)
            NormalViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_empty_lesson, parent, false)
            EmptyViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is NormalViewHolder) {
            holder.bind(item)
        } else if (holder is EmptyViewHolder) {
            holder.bind(item)
        }
    }

    // --- ViewHolder для обычной пары ---
    class NormalViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Находим все View из item_schedule.xml
        private val tvSubject: TextView = itemView.findViewById(R.id.tvSubject)
        private val tvModule: TextView = itemView.findViewById(R.id.tvModule)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvTeacher: TextView = itemView.findViewById(R.id.tvTeacher)
        private val tvRoom: TextView = itemView.findViewById(R.id.tvRoom)
        private val tvType: TextView = itemView.findViewById(R.id.tvType)

        // Метод для заполнения View данными
        fun bind(item: ScheduleItem) {
            tvSubject.text = item.subject
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
        }
    }

    // --- ViewHolder для пустой пары ---
    class EmptyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTime: TextView = itemView.findViewById(R.id.tvEmptyLessonTime)

        // ВОТ НЕДОСТАЮЩИЙ МЕТОД bind
        fun bind(item: ScheduleItem) {
            tvTime.text = "${item.startTime} - ${item.endTime}"
        }
    }

    // --- DiffCallback с РЕАЛИЗОВАННЫМИ МЕТОДАМИ ---
    class DiffCallback : DiffUtil.ItemCallback<ScheduleItem>() {
        override fun areItemsTheSame(oldItem: ScheduleItem, newItem: ScheduleItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ScheduleItem, newItem: ScheduleItem): Boolean {
            return oldItem == newItem
        }
    }
}