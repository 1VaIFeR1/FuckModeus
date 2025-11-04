package com.fuck.modeus.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

open class OnSwipeTouchListener(context: Context) : View.OnTouchListener {

    private var startX = 0f
    private var startY = 0f

    // Порог, после которого движение считается свайпом, а не тапом
    private val SWIPE_DISTANCE_THRESHOLD = 100

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 1. Запоминаем, где палец коснулся экрана
                startX = event.x
                startY = event.y
                // Возвращаем false, чтобы не мешать другим слушателям (например, для долгого нажатия)
                return false
            }
            MotionEvent.ACTION_UP -> {
                // 2. Палец оторвался от экрана, анализируем жест
                val endX = event.x
                val endY = event.y
                val diffX = endX - startX
                val diffY = endY - startY

                // 3. Проверяем, что это был именно горизонтальный свайп
                // Условие: путь по горизонтали больше, чем по вертикали
                if (abs(diffX) > abs(diffY)) {

                    // 4. Проверяем, что это был достаточно длинный свайп, а не случайное дрожание пальца
                    if (abs(diffX) > SWIPE_DISTANCE_THRESHOLD) {
                        if (diffX > 0) {
                            onSwipeRight() // Движение вправо
                        } else {
                            onSwipeLeft() // Движение влево
                        }
                        // 5. Мы обработали этот жест как свайп, "съедаем" его, возвращая true
                        return true
                    }
                }
            }
        }
        // 6. Во всех остальных случаях (вертикальный скролл, тап, короткое движение)
        // мы не вмешиваемся и позволяем системе обработать жест как обычно.
        return false
    }

    open fun onSwipeRight() {}
    open fun onSwipeLeft() {}
}