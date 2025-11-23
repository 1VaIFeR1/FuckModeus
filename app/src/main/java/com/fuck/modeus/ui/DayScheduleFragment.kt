package com.fuck.modeus.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fuck.modeus.R

class DayScheduleFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: ScheduleAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_day_schedule, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView = view.findViewById<RecyclerView>(R.id.rvDaySchedule)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmptyDay)

        adapter = ScheduleAdapter { scheduleItem ->
            (activity as? MainActivity)?.showLessonDetailsDialog(scheduleItem)
        }

        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(context)

        val dateMillis = arguments?.getLong(ARG_DATE) ?: 0L

        viewModel.scheduleMap.observe(viewLifecycleOwner) {
            val lessons = viewModel.getLessonsForDate(dateMillis)

            if (lessons.isEmpty()) {
                if (viewModel.showEmptyLessons.value == true) {
                    recyclerView.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                }
            } else {
                recyclerView.visibility = View.VISIBLE
                tvEmpty.visibility = View.GONE
                adapter.submitList(lessons)
            }
        }
    }

    companion object {
        private const val ARG_DATE = "arg_date"

        fun newInstance(dateMillis: Long): DayScheduleFragment {
            val fragment = DayScheduleFragment()
            val args = Bundle()
            args.putLong(ARG_DATE, dateMillis)
            fragment.arguments = args
            return fragment
        }
    }
}