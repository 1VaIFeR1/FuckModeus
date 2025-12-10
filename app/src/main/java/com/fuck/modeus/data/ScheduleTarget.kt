package com.fuck.modeus.data

import com.google.gson.annotations.SerializedName

object TargetType {
    const val PERSON = "PERSON"
    const val ROOM = "ROOM"
}

// Основная модель (V2)
data class ScheduleTarget(
    val name: String,

    // Используем alternate, чтобы читать и старое поле "id", и новое "person_id"
    @SerializedName("person_id", alternate = ["id"])
    val id: String,

    val type: String = TargetType.PERSON,
    val description: String = "",
    var isPinned: Boolean = false
)