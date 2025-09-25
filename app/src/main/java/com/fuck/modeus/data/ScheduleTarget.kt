package com.fuck.modeus.data

data class ScheduleTarget(
    val name: String,
    val person_id: String,
    var isPinned: Boolean = false
)