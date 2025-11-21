package com.fuck.modeus.network

import com.google.gson.JsonObject
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

interface ApiService {
    // Для SFEDU
    @POST("schedule-calendar-v2/api/calendar/events/search")
    suspend fun getScheduleSfedu(
        @Body body: JsonObject,
        @Query("tz") timeZone: String = "Europe/Moscow"
    ): ResponseBody

    // Для RDCenter (Старый путь)
    @POST("api/Schedule")
    suspend fun getScheduleRdCenter(
        @Body body: JsonObject
    ): ResponseBody
}