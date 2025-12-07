package com.fuck.modeus.network

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
}