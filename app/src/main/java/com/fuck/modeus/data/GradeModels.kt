package com.fuck.modeus.data

import com.google.gson.annotations.SerializedName

// Главный ответ от сервера
data class GradeResponse(
    @SerializedName("courseUnitRealizationControlObjects") val summaryObjects: List<SummaryControlObject>?,
    @SerializedName("lessonControlObjects") val lessonObjects: List<LessonControlObject>?
)

// --- Для ИТОГОВОГО балла ---
data class SummaryControlObject(
    val typeName: String, // "Итог текущ."
    val resultCurrent: GradeValue? // Объект с оценкой
)

// --- Для ОЦЕНОК ЗА ЗАНЯТИЯ ---
data class LessonControlObject(
    val typeName: String, // "Реферат", "Работа на занятии"
    val result: GradeValue? // Объект с оценкой (может быть null, если не оценено)
)

// Общая обертка для значения оценки
data class GradeValue(
    val resultValue: String? // "77.00", "4", "0"
)

// --- Модель чисто для UI (чтобы удобно показывать в списке) ---
data class GradeUiItem(
    val name: String,
    val score: String,
    val isZero: Boolean // Чтобы подсветить нули серым
)
data class GradebookEntry(
    val subjectName: String,
    val score: String,
    val details: List<GradeUiItem>, // <--- Список работ внутри предмета
    var isExpanded: Boolean = false // <--- Состояние UI
)