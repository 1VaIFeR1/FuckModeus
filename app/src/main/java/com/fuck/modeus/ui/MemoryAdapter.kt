package com.fuck.modeus.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fuck.modeus.R

// Модель элемента памяти
data class MemoryItem(
    val name: String,       // Техническое имя (filename.json)
    val description: String,// Понятное описание (База ID)
    val sizeBytes: Long,    // Размер
    val type: MemoryType    // Тип для иконки и логики удаления
)

enum class MemoryType { FILE, PREFS, DB, CACHE, HISTORY }

class MemoryAdapter(
    private val onDeleteClick: (MemoryItem) -> Unit
) : RecyclerView.Adapter<MemoryAdapter.ViewHolder>() {

    private val items = mutableListOf<MemoryItem>()

    fun submitList(newItems: List<MemoryItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_memory_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvDesc.text = item.description
        holder.tvName.text = item.name
        holder.tvSize.text = formatSize(item.sizeBytes)

        // Иконки
        val iconRes = when (item.type) {
            MemoryType.FILE -> R.drawable.ic_file
            MemoryType.PREFS -> android.R.drawable.ic_menu_preferences
            MemoryType.DB -> android.R.drawable.ic_menu_sort_by_size
            MemoryType.CACHE -> android.R.drawable.ic_menu_save
            MemoryType.HISTORY -> android.R.drawable.ic_menu_recent_history
        }
        holder.ivIcon.setImageResource(iconRes)

        holder.btnDelete.setOnClickListener { onDeleteClick(item) }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    override fun getItemCount() = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDesc: TextView = view.findViewById(R.id.tvFileDescription)
        val tvName: TextView = view.findViewById(R.id.tvFileName)
        val tvSize: TextView = view.findViewById(R.id.tvFileSize)
        val ivIcon: ImageView = view.findViewById(R.id.ivFileType)
        val btnDelete: ImageView = view.findViewById(R.id.btnDeleteFile)
    }
}