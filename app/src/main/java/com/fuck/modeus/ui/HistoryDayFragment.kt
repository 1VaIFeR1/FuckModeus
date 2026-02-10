package com.fuck.modeus.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fuck.modeus.R
import java.util.Calendar // <--- ИМПОРТ ОБЯЗАТЕЛЕН

class HistoryDayFragment : Fragment() {

    private val viewModel: HistoryViewModel by viewModels({ requireParentFragment() })
    private lateinit var adapter: ScheduleAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_day_schedule, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView = view.findViewById<RecyclerView>(R.id.rvDaySchedule)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmptyDay)

        adapter = ScheduleAdapter {
            // Пустой клик
        }

        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(context)

        val dateMillis = arguments?.getLong(ARG_DATE) ?: 0L

        viewModel.scheduleMap.observe(viewLifecycleOwner) { map ->
            // ИСПРАВЛЕННЫЙ БЛОК КАЛЕНДАРЯ
            val dayStart = Calendar.getInstance().apply {
                timeInMillis = dateMillis // Правильное написание
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val lessons = map[dayStart] ?: emptyList()

            if (lessons.isEmpty()) {
                recyclerView.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
            } else {
                recyclerView.visibility = View.VISIBLE
                tvEmpty.visibility = View.GONE
                adapter.submitList(lessons)
            }
        }
    }

    companion object {
        private const val ARG_DATE = "arg_date"
        fun newInstance(dateMillis: Long): HistoryDayFragment {
            val fragment = HistoryDayFragment()
            val args = Bundle()
            args.putLong(ARG_DATE, dateMillis)
            fragment.arguments = args
            return fragment
        }
    }
}