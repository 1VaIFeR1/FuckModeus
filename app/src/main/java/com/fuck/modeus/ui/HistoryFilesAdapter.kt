package com.fuck.modeus.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fuck.modeus.R
import java.io.File

class HistoryFilesAdapter(
    private val onItemClick: (File) -> Unit,
    private val onEditClick: (File) -> Unit,
    private val onDeleteClick: (File) -> Unit // <--- НОВЫЙ КОЛБЭК
) : RecyclerView.Adapter<HistoryFilesAdapter.ViewHolder>() {

    private val files = mutableListOf<File>()

    fun submitList(newFiles: List<File>) {
        files.clear()
        files.addAll(newFiles)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        val displayName = file.name
            .removePrefix("history_")
            .removeSuffix(".json")
            .replace("_", " ")

        holder.tvName.text = displayName

        holder.itemView.setOnClickListener { onItemClick(file) }
        holder.btnEdit.setOnClickListener { onEditClick(file) }

        // Обработка удаления
        holder.btnDelete.setOnClickListener { onDeleteClick(file) }
    }

    override fun getItemCount() = files.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvFileName)
        val btnEdit: ImageView = view.findViewById(R.id.btnEdit)
        val btnDelete: ImageView = view.findViewById(R.id.btnDelete) // <--- НОВОЕ ПОЛЕ
    }
}