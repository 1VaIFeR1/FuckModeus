package com.fuck.modeus.network

import com.fuck.modeus.data.ScheduleResponse
import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import okhttp3.ResponseBody

interface ApiService {
    @POST("api/Schedule")
    // suspend fun getSchedule(@Body body: JsonObject): Response<ScheduleResponse>
    suspend fun getScheduleAsString(@Body body: JsonObject): ResponseBody
}