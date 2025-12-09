package com.fuck.modeus.data

import com.google.gson.annotations.SerializedName

data class Attendee(
    val id: String, // ID записи
    @SerializedName("personId") val personId: String, // ID человека для поиска расписания
    val fullName: String,
    val roleName: String,
    val specialtyCode: String?,
    val specialtyName: String?,
    val specialtyProfile: String?
)