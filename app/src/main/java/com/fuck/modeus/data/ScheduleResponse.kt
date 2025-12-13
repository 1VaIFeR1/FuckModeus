package com.fuck.modeus.data

import com.google.gson.annotations.SerializedName

// Главный ответ
data class ScheduleResponse(
    @SerializedName("_embedded") val embedded: Embedded
)

data class Embedded(
    val events: List<Event>,
    val persons: List<Person>,
    val rooms: List<Room>,
    @SerializedName("course-unit-realizations") val courseUnits: List<CourseUnit>,
    @SerializedName("event-locations") val eventLocations: List<EventLocation>,
    @SerializedName("event-attendees") val eventAttendees: List<EventAttendee>,
    @SerializedName("event-rooms") val eventRooms: List<EventRoom>,
    @SerializedName("cycle-realizations") val cycleRealizations: List<CycleRealization>,
    @SerializedName("event-teams") val eventTeams: List<EventTeam>
)

data class Event(
    val id: String,
    val name: String,
    @SerializedName("nameShort") val nameShort: String?,
    @SerializedName("typeId") val typeId: String, // LECT, SEMI...
    @SerializedName("start") val start: String, // 2025-10-18T09:50:00+03:00
    @SerializedName("end") val end: String,
    @SerializedName("_links") val links: EventLinks
)

data class EventLinks(
    @SerializedName("course-unit-realization") val courseUnit: Link?,
    @SerializedName("cycle-realization") val cycleRealization: Link?
)

data class Link(val href: String)

// Справочники
data class Person(val id: String, val fullName: String)
data class Room(val id: String, val name: String)
data class CourseUnit(val id: String, val name: String, val nameShort: String?)
data class CycleRealization(val id: String, val code: String?)

// Связки
data class EventAttendee(val _links: AttLinks) {
    data class AttLinks(val event: Link, val person: Link)
}

data class EventLocation(val eventId: String, val customLocation: String?, val _links: LocLinks) {
    data class LocLinks(val self: List<Link>, @SerializedName("event-rooms") val eventRooms: Link?)
}

data class EventRoom(val id: String, val _links: RoomLinks) {
    data class RoomLinks(val event: Link, val room: Link)
}

data class EventTeam(val eventId: String, val size: Int)


// --- Модель для UI (оставляем старую, но метод парсинга изменится) ---
data class ScheduleItem(
    val id: String,
    val fullStartDate: Long,
    val subject: String,
    val moduleShortName: String?,
    val startTime: String,
    val endTime: String,
    val date: String,
    val teacher: String,
    val room: String,
    val type: String,
    val moduleFullName: String?,
    val groupCode: String?,
    val teamSize: Int?,
    val locationType: String,
    val courseUnitId: String?
)

data class CachedData(
    val targetId: String,
    val targetName: String,
    val lastUpdateTime: String,
    val scheduleJsonResponse: String
)

// --- Хелпер для перевода типов пар ---
object EventTypeMapper {
    fun getHumanReadableType(typeId: String): String {
        return when(typeId) {
            "LECT" -> "Лекция"
            "SEMI" -> "Практика"
            "LAB" -> "Лабораторная"
            "EXAM" -> "Экзамен"
            "PRETEST" -> "Зачет"
            "CONS" -> "Консультация"
            "CUR_CHECK" -> "Текущий контроль"
            "MID_CHECK" -> "Аттестация"
            else -> typeId
        }
    }
}