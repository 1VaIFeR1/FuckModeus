package com.fuck.modeus.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.fuck.modeus.ui.MainViewModel
import java.util.Calendar

class DayPagerAdapter(activity: FragmentActivity, private val viewModel: MainViewModel) : FragmentStateAdapter(activity) {

    // Количество дней в семестре (берем с запасом, например 200, или вычисляем точно)
    // Пейджер работает лениво, так что можно задать большое число
    override fun getItemCount(): Int = 200

    override fun createFragment(position: Int): Fragment {
        // Вычисляем дату для этой позиции
        val cal = Calendar.getInstance()
        cal.time = viewModel.semesterStartDate
        cal.add(Calendar.DAY_OF_YEAR, position)

        return DayScheduleFragment.newInstance(cal.timeInMillis)
    }
}