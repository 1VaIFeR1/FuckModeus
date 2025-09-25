package com.fuck.modeus.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.math.abs

class CustomSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    private var startX = 0f
    private var startY = 0f
    private var isHorizontalSwipe = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                isHorizontalSwipe = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - startX)
                val dy = abs(ev.y - startY)
                // Если горизонтальное движение ЗНАЧИТЕЛЬНО больше вертикального,
                // считаем это горизонтальным свайпом и не перехватываем жест.
                if (dx > dy * 2) {
                    isHorizontalSwipe = true
                }
            }
        }

        // Если это горизонтальный свайп, не даем SwipeRefreshLayout перехватить его.
        if (isHorizontalSwipe) {
            return false
        }

        // В противном случае, пусть работает стандартная логика.
        return super.onInterceptTouchEvent(ev)
    }
}