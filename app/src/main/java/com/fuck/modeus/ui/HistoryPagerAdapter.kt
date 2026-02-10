package com.fuck.modeus.ui

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import java.util.Calendar

class HistoryPagerAdapter(fragment: Fragment, private val semesterStart: java.util.Date) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = 150 // Примерно 20 недель

    override fun createFragment(position: Int): Fragment {
        val cal = Calendar.getInstance()
        cal.time = semesterStart
        cal.add(Calendar.DAY_OF_YEAR, position)
        return HistoryDayFragment.newInstance(cal.timeInMillis)
    }
}