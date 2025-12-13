package com.fuck.modeus.data

import com.google.gson.annotations.SerializedName

data class TechnologyResponse(
    @SerializedName("lessonTechnologies") val lessons: List<LessonTech>?
)

data class LessonTech(
    val lessonName: String?, // "Дифференцированный зачет", "Экзамен"
    val lessonTypeName: String? // "Аттестация"
)