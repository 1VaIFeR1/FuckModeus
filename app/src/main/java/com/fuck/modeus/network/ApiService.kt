package com.fuck.modeus.network

import com.fuck.modeus.data.Attendee
import com.google.gson.JsonObject
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

interface ApiService {
    // SFEDU (Стандартный)
    @POST("schedule-calendar-v2/api/calendar/events/search")
    suspend fun getScheduleSfedu(
        @Body body: JsonObject,
        @Query("tz") timeZone: String = "Europe/Moscow"
    ): ResponseBody

    // RDCenter (Динамический)
    // Мы убрали путь из @POST и передаем его через @Url
    @POST
    suspend fun getScheduleRdCenter(
        @Url url: String,
        @Body body: JsonObject
    ): ResponseBody

    // --- НОВЫЕ МЕТОДЫ ДЛЯ ОБНОВЛЕНИЯ БАЗЫ ---

    // 1. Получить всех людей (размер 10000+, чтобы выкачать всех)
    @POST("schedule-calendar-v2/api/people/persons/search")
    suspend fun getAllPersons(
        @Body body: JsonObject = JsonObject().apply {
            addProperty("size", 20000)
            addProperty("fullName", "")
        }
    ): ResponseBody

    // 2. Получить все аудитории
    @POST("schedule-calendar-v2/api/campus/rooms/search")
    suspend fun getAllRooms(
        @Body body: JsonObject = JsonObject().apply {
            addProperty("size", 5000)
            addProperty("name", "")
        }
    ): ResponseBody
    @retrofit2.http.GET("schedule-calendar-v2/api/calendar/events/{id}/attendees")
    suspend fun getEventAttendees(
        @retrofit2.http.Path("id") eventId: String
    ): List<Attendee>
    // НОВЫЙ МЕТОД: Получение баллов
    @POST("students-app/api/pages/student-card/my/academic-period-results-table/secondary")
    suspend fun getGrades(@Body body: JsonObject): com.fuck.modeus.data.GradeResponse
}