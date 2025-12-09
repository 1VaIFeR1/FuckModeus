package com.fuck.modeus.data

import com.google.gson.annotations.SerializedName

object TargetType {
    const val PERSON = "PERSON"
    const val ROOM = "ROOM"
}

data class ScheduleTarget(
    val name: String,

    @SerializedName("person_id") val id: String,

    val type: String = TargetType.PERSON,

    // Новое поле для поиска (например "Преподаватель", "Аудитория")
    // Оно не обязательно должно быть в JSON, мы заполним его сами при создании
    val description: String = "",

    var isPinned: Boolean = false
)