package com.fuck.modeus.data

import com.google.gson.annotations.SerializedName
import java.util.Date

data class ScheduleResponse(
    @SerializedName("_embedded") val embedded: Embedded
)

data class Embedded(
    val events: List<Event>,
    val persons: List<Person>,
    val rooms: List<Room>,
    @SerializedName("course-unit-realizations") val courseUnitRealizations: List<CourseUnitRealization>,
    @SerializedName("cycle-realizations") val cycleRealizations: List<CycleRealization>,
    @SerializedName("event-teams") val eventTeams: List<EventTeam>,
    @SerializedName("event-locations") val eventLocations: List<EventLocation>,
    @SerializedName("event-attendees") val eventAttendees: List<EventAttendee>,
    @SerializedName("event-rooms") val eventRooms: List<EventRoom>
)

data class Event(
    val id: String,
    val name: String,
    val nameShort: String?,
    @SerializedName("typeId") val type: String,
    @SerializedName("_links") val links: EventLinks,
    // ДОБАВЛЯЕМ НЕДОСТАЮЩИЕ ПОЛЯ:
    val start: String,
    val end: String,
    val startsAt: String,
    val endsAt: String
)

data class Person(val id: String, val fullName: String)
data class Room(val id: String, val name: String)
data class Link(val href: String)

data class EventLinks(
    @SerializedName("course-unit-realization") val courseUnitRealization: Link?,
    @SerializedName("cycle-realization") val cycleRealization: Link?
)

data class EventAttendee(val _links: Links) {
    data class Links(val event: Link?, val person: Link?)
}

data class EventRoom(val _links: Links) {
    data class Links(val event: Link?, val room: Link?)
}

// --- НОВЫЕ КЛАССЫ ДЛЯ ДЕТАЛЬНОЙ ИНФОРМАЦИИ ---

data class CourseUnitRealization(
    val id: String,
    val name: String,
    val nameShort: String?
)

data class CycleRealization(
    val id: String,
    val code: String?,
    @SerializedName("_links") val links: CycleRealizationLinks
)

data class CycleRealizationLinks(
    @SerializedName("course-unit-realization") val courseUnitRealization: Link
)

data class EventTeam(
    val eventId: String,
    val size: Int?
)

data class EventLocation(
    val eventId: String,
    val customLocation: String?
)

// --- ФИНАЛЬНАЯ МОДЕЛЬ ДЛЯ ОТОБРАЖЕНИЯ ---

data class ScheduleItem(
    val id: String, // Добавляем ID самого события, чтобы находить его
    val fullStartDate: Long,
    val subject: String,
    val moduleShortName: String?,
    val startTime: String,
    val endTime: String,
    val date: String,
    val teacher: String,
    val room: String,
    val type: String,
    // Новые поля для детальной информации
    val moduleFullName: String?,
    val groupCode: String?,
    val teamSize: Int?,
    val locationType: String // "Online" или "Очно"
)