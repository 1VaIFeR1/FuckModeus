package com.fuck.modeus.data

import android.content.Context

enum class ApiSource { SFEDU, RDCENTER }

// НОВЫЙ ENUM
enum class ProfileDisplayMode { BAR, DROPDOWN }

object ApiSettings {
    private const val PREFS_NAME = "modeus_global_prefs"
    private const val KEY_API_SOURCE = "api_source"

    private const val KEY_RD_BASE_URL = "rd_base_url"
    private const val KEY_RD_ENDPOINT = "rd_endpoint"
    private const val DEFAULT_RD_BASE_URL = "https://schedule.rdcenter.ru/"
    private const val DEFAULT_RD_ENDPOINT = "api/Schedule"

    private const val KEY_PARALLEL_ENABLED = "parallel_enabled"
    private const val KEY_PARALLEL_COUNT = "parallel_count"
    private const val KEY_CURRENT_PROFILE = "current_profile_index"

    // Новые ключи
    private const val KEY_PROFILE_DISPLAY_MODE = "profile_display_mode"
    private const val KEY_PROFILE_NAME_PREFIX = "profile_name_"

    // ... (Существующие методы getApiSource, setApiSource, getRd... setRd... оставляем) ...
    // Вставь их сюда, если копируешь, или просто добавь новые методы ниже:

    fun getApiSource(context: Context): ApiSource {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_API_SOURCE, ApiSource.SFEDU.name)
        return try { ApiSource.valueOf(name ?: ApiSource.SFEDU.name) } catch (e: Exception) { ApiSource.SFEDU }
    }
    fun setApiSource(context: Context, source: ApiSource) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_API_SOURCE, source.name).apply()
    }
    fun getRdBaseUrl(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_RD_BASE_URL, DEFAULT_RD_BASE_URL) ?: DEFAULT_RD_BASE_URL
    fun getRdEndpoint(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_RD_ENDPOINT, DEFAULT_RD_ENDPOINT) ?: DEFAULT_RD_ENDPOINT
    fun setRdSettings(context: Context, baseUrl: String, endpoint: String) {
        val validBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val validEndpoint = if (endpoint.startsWith("/")) endpoint.substring(1) else endpoint
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_RD_BASE_URL, validBase).putString(KEY_RD_ENDPOINT, validEndpoint).apply()
    }
    fun resetRdSettings(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_RD_BASE_URL, DEFAULT_RD_BASE_URL).putString(KEY_RD_ENDPOINT, DEFAULT_RD_ENDPOINT).apply()
    }

    // --- ПАРАЛЛЕЛЬНЫЕ РАСПИСАНИЯ ---
    fun isParallelEnabled(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_PARALLEL_ENABLED, false)
    fun setParallelEnabled(context: Context, enabled: Boolean) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_PARALLEL_ENABLED, enabled).apply()
    fun getParallelCount(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_PARALLEL_COUNT, 2)
    fun setParallelCount(context: Context, count: Int) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_PARALLEL_COUNT, count.coerceIn(2, 10)).apply()
    fun getCurrentProfile(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_CURRENT_PROFILE, 0)
    fun setCurrentProfile(context: Context, index: Int) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_CURRENT_PROFILE, index).apply()

    // --- НОВОЕ: РЕЖИМ ОТОБРАЖЕНИЯ ---
    fun getProfileDisplayMode(context: Context): ProfileDisplayMode {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROFILE_DISPLAY_MODE, ProfileDisplayMode.BAR.name)
        return try { ProfileDisplayMode.valueOf(name ?: ProfileDisplayMode.BAR.name) } catch(e: Exception) { ProfileDisplayMode.BAR }
    }

    fun setProfileDisplayMode(context: Context, mode: ProfileDisplayMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILE_DISPLAY_MODE, mode.name).apply()
    }

    // --- НОВОЕ: ИМЕНА ПРОФИЛЕЙ ---
    fun saveProfileTargetName(context: Context, profileIndex: Int, targetName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILE_NAME_PREFIX + profileIndex, targetName).apply()
    }

    fun getProfileTargetName(context: Context, profileIndex: Int): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROFILE_NAME_PREFIX + profileIndex, null)
    }
}