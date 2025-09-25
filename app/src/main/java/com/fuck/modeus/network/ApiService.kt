package com.fuck.modeus.network

import com.fuck.modeus.data.ScheduleResponse
import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("api/Schedule")
    suspend fun getSchedule(@Body body: JsonObject): Response<ScheduleResponse>
}