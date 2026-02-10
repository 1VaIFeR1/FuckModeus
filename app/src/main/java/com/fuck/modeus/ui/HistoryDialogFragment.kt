package com.fuck.modeus.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.fuck.modeus.R
import com.fuck.modeus.data.DayItem // <--- ДОБАВЛЕН ИМПОРТ
import java.util.Calendar
import androidx.fragment.app.setFragmentResult // Для отправки сигнала
import android.content.DialogInterface
import androidx.core.os.bundleOf

class HistoryDialogFragment : DialogFragment() {

    private val viewModel: HistoryViewModel by viewModels()
    private lateinit var fileName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileName = arguments?.getString("ARG_FILE") ?: ""
        viewModel.loadHistoryFile(fileName)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.dialog_history_viewer, container, false)

        // ИЗМЕНЕНИЕ: Используем специальный метод для закрытия
        view.findViewById<View>(R.id.btnCloseHistory).setOnClickListener {
            closeAndReturn()
        }

        val tvTitle = view.findViewById<TextView>(R.id.tvHistoryTitle)
        viewModel.historyTitle.observe(viewLifecycleOwner) { tvTitle.text = it }

        setupScheduleUI(view)

        return view
    }

    private fun closeAndReturn() {
        // Отправляем сигнал "HISTORY_CLOSED" в MainActivity
        setFragmentResult("HISTORY_CLOSED", bundleOf())
        dismiss()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        // Тоже отправляем сигнал
        setFragmentResult("HISTORY_CLOSED", bundleOf())
    }

    private fun setupScheduleUI(view: View) {
        val viewPager = view.findViewById<ViewPager2>(R.id.viewPagerHistory)
        val rvWeeks = view.findViewById<RecyclerView>(R.id.rvWeeksHistory)
        val rvDays = view.findViewById<RecyclerView>(R.id.rvDaysHistory)

        val weeksAdapter = WeeksAdapter { week ->
            val diff = ((week.startDate.time - viewModel.semesterStartDate.time) / (1000 * 60 * 60 * 24)).toInt()
            viewPager.setCurrentItem(diff, false)
        }
        rvWeeks.adapter = weeksAdapter
        rvWeeks.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)

        val daysAdapter = DaysAdapter { day ->
            val diff = ((day.date.time - viewModel.semesterStartDate.time) / (1000 * 60 * 60 * 24)).toInt()
            viewPager.setCurrentItem(diff, true)
        }
        rvDays.adapter = daysAdapter
        rvDays.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)

        viewModel.weeks.observe(viewLifecycleOwner) {
            weeksAdapter.submitList(it)
            if (viewPager.adapter == null) {
                viewPager.adapter = HistoryPagerAdapter(this, viewModel.semesterStartDate)
            }
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDaysList(position, daysAdapter)
                viewModel.onPageChanged(position)
            }
        })
    }

    private fun updateDaysList(position: Int, adapter: DaysAdapter) {
        val cal = Calendar.getInstance()
        cal.time = viewModel.semesterStartDate
        cal.add(Calendar.DAY_OF_YEAR, position)
        val currentDate = cal.time

        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        if (cal.time.after(currentDate)) cal.add(Calendar.DAY_OF_WEEK, -7)

        val days = mutableListOf<DayItem>()
        val dateFormat = java.text.SimpleDateFormat("d", java.util.Locale("ru"))
        val dayNameFormat = java.text.SimpleDateFormat("EE", java.util.Locale("ru"))

        for (i in 0..6) {
            val date = cal.time

            // Сравниваем только дни (без учета времени)
            val isSame = isSameDay(date, currentDate)

            // ИСПРАВЛЕНО: Правильный конструктор DayItem
            days.add(DayItem(
                date = date,
                dayOfWeek = dayNameFormat.format(date), // "Пн"
                dayOfMonth = dateFormat.format(date),   // "12"
                isSelected = isSame
            ))
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        adapter.submitList(days)
    }

    // Хелпер для сравнения дней
    private fun isSameDay(d1: java.util.Date, d2: java.util.Date): Boolean {
        val c1 = Calendar.getInstance().apply { time = d1 }
        val c2 = Calendar.getInstance().apply { time = d2 }
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
                c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.95).toInt()
            val height = (resources.displayMetrics.heightPixels * 0.75).toInt()
            window.setLayout(width, height)
        }
    }

    companion object {
        fun newInstance(fileName: String): HistoryDialogFragment {
            val f = HistoryDialogFragment()
            val args = Bundle()
            args.putString("ARG_FILE", fileName)
            f.arguments = args
            return f
        }
    }
}