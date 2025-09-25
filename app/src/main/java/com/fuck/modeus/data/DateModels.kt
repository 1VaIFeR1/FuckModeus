package com.fuck.modeus.data

import java.util.Date

// Модель для отображения одного дня в нижнем баре
data class DayItem(
    val date: Date,         // Полная дата
    val dayOfWeek: String,  // "Пн", "Вт" ...
    val dayOfMonth: String, // "1", "2"...
    var isSelected: Boolean = false
)

// Модель для отображения одной недели в верхнем баре
data class WeekItem(
    val weekNumber: Int,    // Номер недели в семестре
    val startDate: Date,    // Дата понедельника
    val endDate: Date,      // Дата воскресенья
    val displayableString: String, // Готовая строка "1 неделя (01-07)"
    var isSelected: Boolean = false
)