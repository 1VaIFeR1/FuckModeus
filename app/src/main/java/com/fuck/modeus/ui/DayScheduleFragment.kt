package com.fuck.modeus.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fuck.modeus.R
import com.fuck.modeus.data.ScheduleItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DayScheduleFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvNoLessons: TextView
    private val scheduleAdapter = ScheduleAdapter{}

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_day_schedule, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerView)
        tvNoLessons = view.findViewById(R.id.tvNoLessons)

        recyclerView.apply {
            adapter = scheduleAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        // Получаем список пар, который передала Activity
        val scheduleJson = arguments?.getString(ARG_SCHEDULE_ITEMS)
        if (scheduleJson != null) {
            val type = object : TypeToken<List<ScheduleItem>>() {}.type
            val items: List<ScheduleItem> = Gson().fromJson(scheduleJson, type)
            displaySchedule(items)
        }
    }

    private fun displaySchedule(items: List<ScheduleItem>) {
        scheduleAdapter.submitList(items)
        tvNoLessons.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    companion object {
        private const val ARG_SCHEDULE_ITEMS = "arg_schedule_items"

        fun newInstance(scheduleItems: List<ScheduleItem>): DayScheduleFragment {
            val fragment = DayScheduleFragment()
            val args = Bundle()
            // Мы передаем список пар в виде JSON-строки
            args.putString(ARG_SCHEDULE_ITEMS, Gson().toJson(scheduleItems))
            fragment.arguments = args
            return fragment
        }
    }
}