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
    private const val KEY_PROFILE_MAX_DATE_PREFIX = "profile_max_date_"
    private const val KEY_FIRST_RUN = "is_first_run_v3_2"
    private const val KEY_HISTORY_ENABLED = "history_enabled"
    private const val KEY_HISTORY_ALLOWED_PROFILES = "history_allowed_profiles" // Set<String>

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

    // Сохранить дату последней пары (в миллисекундах) для конкретного профиля
    fun saveProfileMaxDate(context: Context, profileIndex: Int, maxDate: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_PROFILE_MAX_DATE_PREFIX + profileIndex, maxDate).apply()
    }

    // Получить максимальную дату среди ВСЕХ активных профилей
    fun getGlobalMaxDate(context: Context): Long {
        val count = getParallelCount(context)
        var globalMax = 0L

        for (i in 0 until count) {
            val date = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_PROFILE_MAX_DATE_PREFIX + i, 0L)
            if (date > globalMax) {
                globalMax = date
            }
        }
        return globalMax
    }

    fun isFirstRun(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FIRST_RUN, true)
    }

    fun setFirstRunCompleted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FIRST_RUN, false).apply()
    }

    // 1. По умолчанию FALSE (как ты просил)
    fun isHistoryEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HISTORY_ENABLED, false)
    }

    fun setHistoryEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HISTORY_ENABLED, enabled).apply()
    }

    // 2. Методы для списка профилей, которые надо сохранять
    // Храним индексы профилей в виде строк ("0", "1", "3")
    fun getHistoryAllowedProfiles(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_HISTORY_ALLOWED_PROFILES, emptySet()) ?: emptySet()
    }

    fun setHistoryAllowedProfiles(context: Context, indexes: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_HISTORY_ALLOWED_PROFILES, indexes).apply()
    }
}