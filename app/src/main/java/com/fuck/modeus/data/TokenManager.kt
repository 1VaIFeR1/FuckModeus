package com.fuck.modeus.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object TokenManager {
    private const val PREFS_NAME = "modeus_auth_prefs"
    private const val KEY_TOKEN = "auth_token"
    private const val TAG = "FuckModeus_DEBUG" // Общий тег для фильтрации

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveToken(context: Context, token: String) {
        Log.d(TAG, "TokenManager: Пытаюсь сохранить токен (длина: ${token.length})")
        getPrefs(context).edit().putString(KEY_TOKEN, token).apply()
        Log.d(TAG, "TokenManager: Токен успешно сохранен на диск")
    }

    fun getToken(context: Context): String? {
        val token = getPrefs(context).getString(KEY_TOKEN, null)
        if (token == null) {
            Log.d(TAG, "TokenManager: Запрошен токен, но он NULL")
        } else {
            Log.d(TAG, "TokenManager: Токен получен из памяти (длина: ${token.length})")
        }
        return token
    }

    fun clearToken(context: Context) {
        Log.d(TAG, "TokenManager: Удаление токена")
        getPrefs(context).edit().remove(KEY_TOKEN).apply()
    }
}