package com.fuck.modeus.data

import android.content.Context

enum class ApiSource {
    SFEDU,      // Новый (с токеном)
    RDCENTER    // Старый (без токена)
}

object ApiSettings {
    private const val PREFS_NAME = "modeus_global_prefs"
    private const val KEY_API_SOURCE = "api_source"

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
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_API_SOURCE, source.name).apply()
    }
}