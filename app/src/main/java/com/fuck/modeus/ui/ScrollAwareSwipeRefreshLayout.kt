package com.fuck.modeus.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.math.abs

class ScrollAwareSwipeRefreshLayout(context: Context, attrs: AttributeSet) : SwipeRefreshLayout(context, attrs) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var isHorizontalDrag = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                isHorizontalDrag = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (isHorizontalDrag) {
                    return false // Если уже поняли, что это горизонтальный свайп — не мешаем
                }

                val xDiff = abs(ev.x - startX)
                val yDiff = abs(ev.y - startY)

                // Если движение по горизонтали больше, чем по вертикали, и превышает порог "дрожания"
                if (xDiff > touchSlop && xDiff > yDiff) {
                    isHorizontalDrag = true
                    return false // Отдаем событие ребенку (ViewPager2)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isHorizontalDrag = false
            }
        }

        return super.onInterceptTouchEvent(ev)
    }
}