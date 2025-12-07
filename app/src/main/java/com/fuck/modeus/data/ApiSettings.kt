package com.fuck.modeus.data

import android.content.Context

enum class ApiSource {
    SFEDU,
    RDCENTER
}

object ApiSettings {
    private const val PREFS_NAME = "modeus_global_prefs"
    private const val KEY_API_SOURCE = "api_source"

    // Новые ключи
    private const val KEY_RD_BASE_URL = "rd_base_url"
    private const val KEY_RD_ENDPOINT = "rd_endpoint"

    // Дефолтные значения
    private const val DEFAULT_RD_BASE_URL = "https://schedule.rdcenter.ru/"
    private const val DEFAULT_RD_ENDPOINT = "api/proxy/events/search"
    // ВНИМАНИЕ: Если у тебя сейчас рабочий эндпоинт другой, поправь тут.
    // Ты писал api/proxy/events/search или api/Schedule?
    // В старой версии было api/Schedule. Если новый сервер требует другое - поменяй тут.
    // Я оставлю старый рабочий вариант, а новый ты можешь вписать вручную в приложении.

    fun getApiSource(context: Context): ApiSource {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_API_SOURCE, ApiSource.SFEDU.name)
        return try {
            ApiSource.valueOf(name ?: ApiSource.SFEDU.name)
        } catch (e: Exception) {
            ApiSource.SFEDU
        }
    }

    fun setApiSource(context: Context, source: ApiSource) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_API_SOURCE, source.name).apply()
    }

    // --- GETTERS ---
    fun getRdBaseUrl(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RD_BASE_URL, DEFAULT_RD_BASE_URL) ?: DEFAULT_RD_BASE_URL
    }

    fun getRdEndpoint(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RD_ENDPOINT, DEFAULT_RD_ENDPOINT) ?: DEFAULT_RD_ENDPOINT
    }

    // --- SETTERS ---
    fun setRdSettings(context: Context, baseUrl: String, endpoint: String) {
        // Retrofit требует, чтобы Base URL заканчивался на "/"
        val validBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        // Endpoint лучше без слеша в начале
        val validEndpoint = if (endpoint.startsWith("/")) endpoint.substring(1) else endpoint

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_RD_BASE_URL, validBase)
            .putString(KEY_RD_ENDPOINT, validEndpoint)
            .apply()
    }

    // --- RESET ---
    fun resetRdSettings(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_RD_BASE_URL, DEFAULT_RD_BASE_URL)
            .putString(KEY_RD_ENDPOINT, DEFAULT_RD_ENDPOINT)
            .apply()
    }
}