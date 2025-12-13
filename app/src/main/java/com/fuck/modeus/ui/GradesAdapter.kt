package com.fuck.modeus.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fuck.modeus.R
import com.fuck.modeus.data.GradeUiItem

class GradesAdapter : RecyclerView.Adapter<GradesAdapter.GradeViewHolder>() {

    private val items = mutableListOf<GradeUiItem>()

    fun submitList(newItems: List<GradeUiItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GradeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_grade, parent, false)
        return GradeViewHolder(view)
    }

    override fun onBindViewHolder(holder: GradeViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    class GradeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvWorkName)
        private val tvScore: TextView = itemView.findViewById(R.id.tvWorkScore)

        fun bind(item: GradeUiItem) {
            tvName.text = item.name
            tvScore.text = item.score

            if (item.isZero) {
                tvScore.setTextColor(Color.GRAY) // Серый, если 0
            } else {
                tvScore.setTextColor(Color.parseColor("#4CAF50")) // Зеленый, если есть баллы
            }
        }
    }
}