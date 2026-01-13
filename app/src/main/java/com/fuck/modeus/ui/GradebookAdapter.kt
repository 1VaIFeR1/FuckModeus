package com.fuck.modeus.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView // <--- Добавился этот импорт
import androidx.recyclerview.widget.RecyclerView
import com.fuck.modeus.R
import com.fuck.modeus.data.GradebookEntry
import com.fuck.modeus.data.GradeUiItem

class GradebookAdapter : RecyclerView.Adapter<GradebookAdapter.ViewHolder>() {

    private val items = mutableListOf<GradebookEntry>()

    fun submitList(newItems: List<GradebookEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gradebook_expandable, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    // --- ЛОГИКА ЦВЕТОВ ---
    private fun getColorForScore(scoreVal: Double): Int {
        return when {
            scoreVal == 0.0 -> Color.BLACK // 0 - Черный (Внимание: плохо видно на темном фоне!)
            scoreVal < 60.0 -> Color.parseColor("#FF5252") // 1-59 - Красный
            scoreVal < 71.0 -> Color.parseColor("#AED581") // 60-70 - Светло-зеленый
            scoreVal < 85.0 -> Color.parseColor("#4CAF50") // 71-84 - Зеленый (стандарт)
            scoreVal < 100.0 -> Color.parseColor("#2E7D32") // 85-99 - Темно-зеленый (насыщенный)
            else -> Color.parseColor("#FFD700") // 100 - Золотой (Легендарный)
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvSubjectName)
        private val tvScore: TextView = itemView.findViewById(R.id.tvTotalScore)
        private val ivArrow: ImageView = itemView.findViewById(R.id.ivArrow)
        // Теперь мы управляем видимостью ScrollView, а не LinearLayout внутри него
        private val scrollDetails: NestedScrollView = itemView.findViewById(R.id.scrollDetails)
        private val containerDetails: LinearLayout = itemView.findViewById(R.id.containerDetails)
        private val containerHeader: LinearLayout = itemView.findViewById(R.id.containerHeader)

        fun bind(item: GradebookEntry) {
            tvName.text = item.subjectName
            tvScore.text = item.score

            // Применяем цвета
            val scoreVal = item.score.toDoubleOrNull() ?: 0.0
            tvScore.setTextColor(getColorForScore(scoreVal))

            // Логика разворачивания
            if (item.isExpanded) {
                scrollDetails.visibility = View.VISIBLE
                ivArrow.rotation = 180f
                populateDetails(item.details)

                // --- ФИКС СКРОЛЛА (ВСТАВИТЬ СЮДА) ---
                // Говорим родительскому RecyclerView: "Не трогай мои касания!"
                scrollDetails.setOnTouchListener(object : View.OnTouchListener {
                    var startY = 0f

                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        when (event.action) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                startY = event.y
                                // Сначала запрещаем родителю перехватывать, даем шанс вложенному
                                v.parent.requestDisallowInterceptTouchEvent(true)
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                val deltaY = event.y - startY

                                // Проверяем: "Уперлись ли мы в край?"

                                // Если тянем вверх (скролл вниз) И больше некуда скроллить вниз
                                val isAtBottom = deltaY < 0 && !v.canScrollVertically(1)

                                // Если тянем вниз (скролл вверх) И больше некуда скроллить вверх
                                val isAtTop = deltaY > 0 && !v.canScrollVertically(-1)

                                if (isAtBottom || isAtTop) {
                                    // Разрешаем родителю забрать скролл
                                    v.parent.requestDisallowInterceptTouchEvent(false)
                                } else {
                                    // Иначе продолжаем скроллить сами
                                    v.parent.requestDisallowInterceptTouchEvent(true)
                                }
                            }
                            android.view.MotionEvent.ACTION_UP,
                            android.view.MotionEvent.ACTION_CANCEL -> {
                                v.parent.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        // Обрабатываем само событие скролла
                        v.onTouchEvent(event)
                        return true
                    }
                })
                // ------------------------------------

            } else {
                scrollDetails.visibility = View.GONE
                ivArrow.rotation = 0f
                containerDetails.removeAllViews()

                // Убираем слушатель, чтобы не тратить ресурсы
                scrollDetails.setOnTouchListener(null)
            }

            containerHeader.setOnClickListener {
                item.isExpanded = !item.isExpanded
                notifyItemChanged(adapterPosition)
            }
        }

        private fun populateDetails(details: List<GradeUiItem>) {
            containerDetails.removeAllViews()
            val inflater = LayoutInflater.from(itemView.context)

            if (details.isEmpty()) {
                val tvEmpty = TextView(itemView.context)
                tvEmpty.text = "Нет оценок"
                tvEmpty.setTextColor(Color.GRAY)
                tvEmpty.textSize = 12f
                tvEmpty.setPadding(0, 8, 0, 8)
                containerDetails.addView(tvEmpty)
                return
            }

            for (detail in details) {
                val detailView = inflater.inflate(R.layout.item_grade, containerDetails, false)

                val tvWorkName = detailView.findViewById<TextView>(R.id.tvWorkName)
                val tvWorkScore = detailView.findViewById<TextView>(R.id.tvWorkScore)

                tvWorkName.text = detail.name
                tvWorkScore.text = detail.score
                tvWorkName.textSize = 13f

                // --- НОВАЯ ЛОГИКА ЦВЕТОВ ДЛЯ ВЛОЖЕННОГО СПИСКА ---
                val detailVal = detail.score.toDoubleOrNull() ?: 0.0

                if (detailVal > 0) {
                    // Если есть баллы - Белый (Контрастно на #414141)
                    tvWorkScore.setTextColor(Color.WHITE)
                } else {
                    // Если 0 - Серый (чтобы не отвлекало)
                    tvWorkScore.setTextColor(Color.parseColor("#1E1F22"))
                }
                // ------------------------------------------------

                containerDetails.addView(detailView)
            }
        }
    }
}