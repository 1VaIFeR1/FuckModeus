package com.fuck.modeus.network

import android.content.Context
import com.fuck.modeus.data.ApiSettings
import com.fuck.modeus.data.ApiSource
import com.fuck.modeus.data.TokenManager
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitInstance {

    // URL-адреса
    private const val SFEDU_URL = "https://sfedu.modeus.org/"
    private const val RDCENTER_URL = "https://schedule.rdcenter.ru/"

    private var retrofitSfedu: Retrofit? = null
    private var retrofitRdCenter: Retrofit? = null

    fun getApi(context: Context): ApiService {
        val source = ApiSettings.getApiSource(context)

        return when (source) {
            ApiSource.SFEDU -> getSfeduRetrofit(context).create(ApiService::class.java)
            ApiSource.RDCENTER -> getRdCenterRetrofit().create(ApiService::class.java)
        }
    }

    // Клиент для SFEDU (С ТОКЕНОМ)
    private fun getSfeduRetrofit(context: Context): Retrofit {
        if (retrofitSfedu == null) {
            val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }

            val authInterceptor = okhttp3.Interceptor { chain ->
                val original = chain.request()
                val token = TokenManager.getToken(context)
                val requestBuilder = original.newBuilder()
                if (token != null) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }
                chain.proceed(requestBuilder.build())
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .addInterceptor(authInterceptor) // <-- Тут есть токен
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            retrofitSfedu = Retrofit.Builder()
                .baseUrl(SFEDU_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofitSfedu!!
    }

    // Клиент для RDCenter (БЕЗ ТОКЕНА)
    private fun getRdCenterRetrofit(): Retrofit {
        if (retrofitRdCenter == null) {
            val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }

            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                // НЕТ authInterceptor
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            retrofitRdCenter = Retrofit.Builder()
                .baseUrl(RDCENTER_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofitRdCenter!!
    }
}