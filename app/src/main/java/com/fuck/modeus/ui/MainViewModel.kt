package com.fuck.modeus.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.fuck.modeus.data.*
import com.fuck.modeus.network.RetrofitInstance
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

enum class NavigationMode { SWIPE, TOUCH, BOTH }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "FuckModeus_DEBUG"

    // --- Календарь ---
    private val AUTUMN_START_MONTH = Calendar.SEPTEMBER
    private val AUTUMN_START_DAY = 1
    private val AUTUMN_END_MONTH = Calendar.FEBRUARY
    private val AUTUMN_END_DAY = 5
    private val SPRING_START_MONTH = Calendar.FEBRUARY
    private val SPRING_START_DAY = 7
    private val SPRING_END_MONTH = Calendar.AUGUST
    private val SPRING_END_DAY = 31

    private val timeSlots = mapOf(
        "08:00" to "09:35", "09:50" to "11:25", "11:55" to "13:30",
        "13:45" to "15:20", "15:50" to "17:25", "17:35" to "19:10",
        "19:15" to "20:50", "20:55" to "21:40"
    )

    // --- LiveData ---
    private val _scheduleMap = MutableLiveData<Map<Long, List<ScheduleItem>>>()
    val scheduleMap: LiveData<Map<Long, List<ScheduleItem>>> = _scheduleMap
    private var fullRawSchedule: List<ScheduleItem> = emptyList()

    var semesterStartDate: Date = Calendar.getInstance().time
        private set

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error
    private val _lastUpdateTime = MutableLiveData<String>()
    val lastUpdateTime: LiveData<String> = _lastUpdateTime

    private var allTargets = listOf<ScheduleTarget>()
    private val _searchResults = MutableLiveData<List<ScheduleTarget>>()
    val searchResults: LiveData<List<ScheduleTarget>> = _searchResults
    private val _pinnedTargets = MutableLiveData<List<ScheduleTarget>>()
    val pinnedTargets: LiveData<List<ScheduleTarget>> = _pinnedTargets
    private val _scheduleTitle = MutableLiveData<String>("Расписание не выбрано")
    val scheduleTitle: LiveData<String> = _scheduleTitle

    private var searchJob: Job? = null
    private val _searchInProgress = MutableLiveData<Boolean>()
    val searchInProgress: LiveData<Boolean> = _searchInProgress

    private val _weeks = MutableLiveData<List<WeekItem>>()
    val weeks: LiveData<List<WeekItem>> = _weeks
    private val _currentPagerPosition = MutableLiveData<Int>()
    val currentPagerPosition: LiveData<Int> = _currentPagerPosition
    private val _showEmptyLessons = MutableLiveData(true)
    val showEmptyLessons: LiveData<Boolean> = _showEmptyLessons
    private val _isRefreshing = MutableLiveData<Boolean>()
    val isRefreshing: LiveData<Boolean> = _isRefreshing
    private val _navigationMode = MutableLiveData(NavigationMode.TOUCH)
    val navigationMode: LiveData<NavigationMode> = _navigationMode

    // --- Attendees LiveData ---
    private val _attendeesList = MutableLiveData<List<Attendee>>()
    val attendeesList: LiveData<List<Attendee>> = _attendeesList
    private val _attendeesLoading = MutableLiveData<Boolean>()
    val attendeesLoading: LiveData<Boolean> = _attendeesLoading
    private val _gradeResult = MutableLiveData<String?>()
    private val _gradeData = MutableLiveData<Triple<String, List<GradeUiItem>, String?>?>()
    val gradeData: LiveData<Triple<String, List<GradeUiItem>, String?>?> = _gradeData

    private val _gradesLoading = MutableLiveData<Boolean>()
    val gradesLoading: LiveData<Boolean> = _gradesLoading

    val authRequired = MutableLiveData<Pair<String, String?>>()

    private val _gradebookData = MutableLiveData<List<GradebookEntry>>()
    val gradebookData: LiveData<List<GradebookEntry>> = _gradebookData

    // Запоминаем ID текущего человека, чье расписание смотрим
    var currentTargetId: String? = null
        private set

    // --- Файлы ---
    private val dbFileName = "allid_v2.json"
    private val sharedPreferences = application.getSharedPreferences("schedule_prefs", Application.MODE_PRIVATE)

    init {
        Log.d(TAG, "ViewModel: init")
        calculateLegacySemesterStart()
        loadShowEmptyLessonsMode()
        loadNavigationMode()
        loadAllIds()
    }

    // =================================================================================
    // МИГРАЦИЯ И ЗАГРУЗКА БАЗЫ
    // =================================================================================
    private data class LegacyTarget(
        @SerializedName("name") val name: String,
        @SerializedName("person_id") val id: String
    )
    private fun loadAllIds() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val dbFile = File(context.filesDir, dbFileName)

                if (!dbFile.exists()) {
                    try {
                        context.assets.open("allid.txt").use { input ->
                            dbFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "DB: Ошибка миграции", e)
                    }
                }

                val jsonString = if (dbFile.exists()) {
                    dbFile.readText()
                } else {
                    context.assets.open("allid.txt").bufferedReader().use { it.readText() }
                }

                val gson = Gson()
                val targets = mutableListOf<ScheduleTarget>()

                try {
                    // ПОПЫТКА 1: Читаем как новую структуру (v2)
                    // Пытаемся понять, есть ли там поле type, которое есть только в v2
                    if (jsonString.contains("\"type\"")) {
                        val type = object : TypeToken<List<ScheduleTarget>>() {}.type
                        val v2Targets: List<ScheduleTarget> = gson.fromJson(jsonString, type)
                        targets.addAll(v2Targets)
                    } else {
                        throw Exception("Old format detected")
                    }
                } catch (e: Exception) {
                    // ПОПЫТКА 2: Читаем как старую структуру (Legacy)
                    Log.w(TAG, "DB: Читаем как Legacy формат (старая база)...")
                    try {
                        val legacyType = object : TypeToken<List<LegacyTarget>>() {}.type
                        val legacyList: List<LegacyTarget> = gson.fromJson(jsonString, legacyType)

                        // Маппим старое в новое
                        val mapped = legacyList.map { legacy ->
                            ScheduleTarget(
                                name = legacy.name,
                                id = legacy.id,
                                type = TargetType.PERSON,
                                description = "", // Явно задаем пустоту
                                isPinned = false
                            )
                        }
                        targets.addAll(mapped)
                    } catch (e2: Exception) {
                        Log.e(TAG, "DB: Фатальная ошибка парсинга", e2)
                        _error.postValue("Ошибка чтения базы данных")
                    }
                }

                allTargets = targets.distinctBy { it.id }
                Log.d(TAG, "DB: Итого загружено ${allTargets.size} записей.")

                loadPinnedStatus()
            } catch (e: Exception) {
                Log.e(TAG, "DB: Критическая ошибка", e)
            }
        }
    }

    fun updateDatabase() {
        val apiSource = ApiSettings.getApiSource(getApplication())
        if (apiSource != ApiSource.SFEDU) {
            _error.postValue("Обновление доступно только через SFEDU Modeus")
            return
        }

        if (TokenManager.getToken(getApplication()) == null) {
            _error.postValue("Требуется вход в аккаунт")
            openLoginActivity(null)
            return
        }

        // 1. Запоминаем текущий заголовок (например "Иванов И.И.")
        val previousTitle = _scheduleTitle.value ?: "Расписание не выбрано"

        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.postValue(true)
            _scheduleTitle.postValue("Обновление базы...") // Меняем на статус

            try {
                val api = RetrofitInstance.getApi(getApplication())
                val newTargets = mutableListOf<ScheduleTarget>()
                val gson = Gson()

                // --- 1. ЛЮДИ ---
                val personsBody = JsonObject().apply {
                    addProperty("size", 100000)
                    addProperty("fullName", "")
                }

                Log.d(TAG, "DB Update: Скачиваем людей...")
                val personsResp = api.getAllPersons(personsBody).string()
                val personsJson = gson.fromJson(personsResp, JsonObject::class.java)
                val totalPersons = personsJson.getAsJsonObject("page")?.get("totalElements")?.asInt ?: 0
                val personsList = personsJson.getAsJsonObject("_embedded")?.getAsJsonArray("persons")

                personsList?.forEach { element ->
                    val obj = element.asJsonObject
                    val name = obj.get("fullName").asString
                    val id = obj.get("id").asString
                    newTargets.add(ScheduleTarget(name, id, TargetType.PERSON))
                }
                Log.d(TAG, "DB Update: Получено людей: ${personsList?.size()}")

                // --- 2. АУДИТОРИИ ---
                val roomsBody = JsonObject().apply {
                    addProperty("size", 10000)
                    addProperty("name", "")
                }

                Log.d(TAG, "DB Update: Скачиваем аудитории...")
                val roomsResp = api.getAllRooms(roomsBody).string()
                val roomsJson = gson.fromJson(roomsResp, JsonObject::class.java)
                val roomsList = roomsJson.getAsJsonObject("_embedded")?.getAsJsonArray("rooms")

                roomsList?.forEach { element ->
                    val obj = element.asJsonObject
                    val name = obj.get("name").asString
                    val id = obj.get("id").asString
                    newTargets.add(ScheduleTarget(name, id, TargetType.ROOM))
                }
                Log.d(TAG, "DB Update: Получено аудиторий: ${roomsList?.size()}")

                // --- 3. СОХРАНЕНИЕ ---
                if (newTargets.isNotEmpty()) {
                    val dbFile = File(getApplication<Application>().filesDir, dbFileName)
                    val jsonToWrite = gson.toJson(newTargets)
                    dbFile.writeText(jsonToWrite)

                    allTargets = newTargets.distinctBy { it.id }
                    loadPinnedStatus()

                    // Показываем результат в Toast (через error channel)
                    _error.postValue("База обновлена. Записей: ${allTargets.size}. (Людей: $totalPersons)")
                } else {
                    _error.postValue("Ошибка: Сервер вернул 0 записей.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "DB Update Error", e)
                _error.postValue("Ошибка обновления: ${e.message}")
            } finally {
                _isRefreshing.postValue(false)
                // 2. Возвращаем старый заголовок на место
                _scheduleTitle.postValue(previousTitle)
            }
        }
    }

    // =================================================================================
    // ATTENDEES (УЧАСТНИКИ)
    // =================================================================================

    fun loadEventAttendees(eventId: String) {
        val apiSource = ApiSettings.getApiSource(getApplication())

        if (apiSource != ApiSource.SFEDU) {
            _error.postValue("Просмотр участников доступен только в режиме SFEDU")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _attendeesLoading.postValue(true)
            try {
                val api = RetrofitInstance.getApi(getApplication())
                val list = api.getEventAttendees(eventId)

                val sortedList = list.sortedWith(
                    compareBy<Attendee> { it.roleName != "Преподаватель" }
                        .thenBy { it.fullName }
                )

                _attendeesList.postValue(sortedList)
            } catch (e: Exception) {
                Log.e(TAG, "Attendees Error", e)
                _attendeesList.postValue(emptyList())
                if (e is retrofit2.HttpException && e.code() == 401) {
                    _error.postValue("Ошибка авторизации. Обновите токен.")
                }
            } finally {
                _attendeesLoading.postValue(false)
            }
        }
    }

    // =================================================================================
    // ПОИСК (ОБНОВЛЕННЫЙ)
    // =================================================================================

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank() || query.length < 2) {
            _searchResults.postValue(emptyList())
            return
        }

        searchJob = viewModelScope.launch(Dispatchers.Default) {
            _searchInProgress.postValue(true)
            delay(300L) // Debounce

            // ИСПРАВЛЕНИЕ КРАША ЗДЕСЬ:
            // Используем (it.description ?: "") чтобы избежать NullPointerException,
            // если Gson вернул null из старой базы.
            val filtered = allTargets.filter { target ->
                target.name.contains(query, ignoreCase = true) ||
                        (target.description ?: "").contains(query, ignoreCase = true)
            }

            val (startsWithName, others) = filtered.partition {
                it.name.startsWith(query, ignoreCase = true)
            }

            _searchResults.postValue((startsWithName + others).take(50))
            _searchInProgress.postValue(false)
        }
    }

    fun findTargetIdByName(name: String): String? {
        return allTargets.find { it.name.equals(name, ignoreCase = true) }?.id
    }

    // =================================================================================
    // ЗАГРУЗКА РАСПИСАНИЯ
    // =================================================================================

    fun loadSchedule(targetId: String) {
        currentTargetId = targetId
        _isRefreshing.postValue(true)

        val apiSource = ApiSettings.getApiSource(getApplication())
        if (apiSource == ApiSource.SFEDU && TokenManager.getToken(getApplication()) == null) {
            _isRefreshing.postValue(false)
            _error.postValue("Требуется вход через Microsoft")
            openLoginActivity(targetId)
            return
        }

        val target = allTargets.find { it.id == targetId }
        val targetType = target?.type ?: TargetType.PERSON

        viewModelScope.launch {
            try {
                calculateLegacySemesterStart()
                val reqCal = Calendar.getInstance().apply { time = semesterStartDate }
                val minDateStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()).format(reqCal.time)
                reqCal.add(Calendar.YEAR, 1)
                val maxDateStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()).format(reqCal.time)

                val requestBody = JsonObject().apply {
                    addProperty("size", 3000)
                    addProperty("timeMin", minDateStr)
                    addProperty("timeMax", maxDateStr)

                    val ids = JsonArray()
                    ids.add(targetId)

                    if (targetType == TargetType.ROOM) {
                        add("roomId", ids)
                    } else {
                        add("attendeePersonId", ids)
                    }
                }

                val api = RetrofitInstance.getApi(getApplication())
                val responseBody = if (apiSource == ApiSource.SFEDU) {
                    api.getScheduleSfedu(requestBody)
                } else {
                    val endpoint = ApiSettings.getRdEndpoint(getApplication())
                    api.getScheduleRdCenter(endpoint, requestBody)
                }
                val responseString = responseBody.string()

                if (responseString.isNotBlank()) {
                    val scheduleResponse = Gson().fromJson(responseString, ScheduleResponse::class.java)
                    val scheduleItems = parseResponseV2(scheduleResponse)
                    val sortedItems = scheduleItems.sortedBy { it.fullStartDate }

                    if (sortedItems.isNotEmpty()) {
                        recalculateSemesterStart(sortedItems.first().fullStartDate)
                    }

                    updateProfileMaxDateInSettings(sortedItems)

                    // Определяем имя (ФИО препода или номер аудитории)
                    val targetName = target?.name ?: "Расписание"
                    val currentTime = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())

                    // --- НОВОЕ: Сохраняем имя для текущего профиля (для списка) ---
                    if (ApiSettings.isParallelEnabled(getApplication())) {
                        val currentProfile = ApiSettings.getCurrentProfile(getApplication())
                        ApiSettings.saveProfileTargetName(getApplication(), currentProfile, targetName)
                    }
                    // -------------------------------------------------------------

                    val dataToCache = CachedData(targetId, targetName, currentTime, responseString)
                    saveDataToFile(dataToCache, sortedItems)

                    _scheduleTitle.postValue(targetName)
                    _lastUpdateTime.postValue("Последнее обновление: $currentTime")

                    processRawSchedule(sortedItems)
                    setInitialPage()
                } else {
                    _error.postValue("Ошибка: получен пустой ответ")
                }
            } catch (e: Exception) {
                if (e is retrofit2.HttpException && e.code() == 401) {
                    TokenManager.clearToken(getApplication())
                    _error.postValue("Сессия истекла")
                    openLoginActivity(targetId)
                } else {
                    _error.postValue("Ошибка: ${e.message}")
                    loadDataFromFile()
                }
            } finally {
                _isRefreshing.postValue(false)
            }
        }
    }

    // =================================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ (ОСТАЛЬНЫЕ)
    // =================================================================================

    private fun calculateLegacySemesterStart() {
        val calendar = Calendar.getInstance()
        val today = calendar.time
        val currentMonth = calendar.get(Calendar.MONTH)
        val currentYear = calendar.get(Calendar.YEAR)

        val firstSemesterStartCalendar = Calendar.getInstance().apply {
            set(Calendar.MONTH, Calendar.SEPTEMBER); set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (currentMonth < Calendar.SEPTEMBER) set(Calendar.YEAR, currentYear - 1) else set(Calendar.YEAR, currentYear)
        }
        val secondSemesterStartCalendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, firstSemesterStartCalendar.get(Calendar.YEAR))
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (today.after(firstSemesterStartCalendar.time)) set(Calendar.YEAR, firstSemesterStartCalendar.get(Calendar.YEAR) + 1)
            set(Calendar.MONTH, Calendar.FEBRUARY); set(Calendar.DAY_OF_MONTH, 8)
        }
        semesterStartDate = if (today.after(secondSemesterStartCalendar.time)) secondSemesterStartCalendar.time else firstSemesterStartCalendar.time
    }

    private fun generateWeeks(items: List<ScheduleItem>) {
        val weekList = mutableListOf<WeekItem>()

        // 1. ЛОГИКА ОПРЕДЕЛЕНИЯ КОНЦА СЕМЕСТРА
        // Мы берем максимальную дату из трех источников:
        // А) Последняя пара в ТЕКУЩЕМ списке
        // Б) Последняя пара в ДРУГИХ профилях (сохраненная в настройках)
        // В) Сегодняшний день (чтобы не показывать меньше, чем уже прожито)

        val context = getApplication<Application>()
        val globalMaxDate = ApiSettings.getGlobalMaxDate(context)
        val currentMaxDate = if (items.isNotEmpty()) items.maxOf { it.fullStartDate } else 0L
        val todayTime = System.currentTimeMillis()

        // Выбираем самую дальнюю дату
        // kotlin.math.maxOf требует import, либо используем цепочку max
        val targetMaxTime = kotlin.math.max(globalMaxDate, kotlin.math.max(currentMaxDate, todayTime))

        // Если данных вообще нет нигде (первый запуск) - показываем 1 неделю
        if (targetMaxTime == 0L) {
            val start = semesterStartDate
            val cal = Calendar.getInstance().apply { time = start }
            // Ищем понедельник
            while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) cal.add(Calendar.DAY_OF_MONTH, -1)
            val monday = cal.time
            cal.add(Calendar.DAY_OF_MONTH, 6)
            val sunday = cal.time
            val dateFormat = SimpleDateFormat("dd", Locale("ru"))
            weekList.add(WeekItem(1, monday, sunday, "1 неделя (${dateFormat.format(monday)}-${dateFormat.format(sunday)})", true))
            _weeks.postValue(weekList)
            return
        }

        // 2. Устанавливаем границу генерации
        val limitCal = Calendar.getInstance().apply { timeInMillis = targetMaxTime }

        // Докручиваем до конца недели (Воскресенье 23:59), чтобы неделя не обрывалась
        while (limitCal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
            limitCal.add(Calendar.DAY_OF_MONTH, 1)
        }
        limitCal.set(Calendar.HOUR_OF_DAY, 23)
        limitCal.set(Calendar.MINUTE, 59)
        limitCal.set(Calendar.SECOND, 59)
        val hardLimit = limitCal.time

        // 3. Цикл генерации недель (от начала семестра до hardLimit)
        val weekCalendar = Calendar.getInstance().apply {
            time = semesterStartDate
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }

        var weekNumber = 1
        val dateFormat = SimpleDateFormat("dd", Locale("ru"))
        val today = Calendar.getInstance().time

        // Генерируем, пока не дойдем до лимита
        while (weekCalendar.time.before(hardLimit) || weekCalendar.time.time <= hardLimit.time) {
            // Находим понедельник текущей недели
            weekCalendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            val startDate = weekCalendar.time

            // Находим воскресенье
            weekCalendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            // Фикс для перехода через год/месяц (стандартный календарь иногда тупит с днями недели)
            if (startDate.after(weekCalendar.time)) weekCalendar.add(Calendar.DAY_OF_MONTH, 7)
            val endDate = weekCalendar.time

            // Проверяем, выделена ли неделя (попадает ли сегодня в этот диапазон)
            val isSelected = (today.time >= startDate.time && today.time <= endDate.time)

            weekList.add(WeekItem(
                weekNumber,
                startDate,
                endDate,
                "$weekNumber неделя (${dateFormat.format(startDate)}-${dateFormat.format(endDate)})",
                isSelected
            ))

            // Если эта неделя заканчивается позже или в то же время, что и лимит - выходим
            if (endDate.time >= hardLimit.time) break

            weekNumber++
            weekCalendar.add(Calendar.DAY_OF_MONTH, 1) // Переходим на след. день, чтобы цикл переключил неделю
        }

        // 4. Если ни одна неделя не выбрана (сегодняшний день за пределами семестра)
        if (weekList.isNotEmpty() && weekList.none { it.isSelected }) {
            if (today.after(weekList.last().endDate)) {
                weekList.last().isSelected = true
            } else {
                weekList.first().isSelected = true
            }
        }

        _weeks.postValue(weekList)
    }

    private fun processRawSchedule(rawItems: List<ScheduleItem>) {
        fullRawSchedule = rawItems
        generateWeeks(rawItems)
        if (rawItems.isEmpty()) { _scheduleMap.postValue(emptyMap()); return }
        viewModelScope.launch(Dispatchers.Default) {
            val map = mutableMapOf<Long, List<ScheduleItem>>()
            val showEmpty = _showEmptyLessons.value ?: true
            val grouped = rawItems.groupBy { getStartOfDay(it.fullStartDate) }
            if (showEmpty) {
                grouped.forEach { (dayMillis, items) ->
                    val filledList = mutableListOf<ScheduleItem>()
                    val itemsByTime = items.associateBy { it.startTime }
                    var pairCounter = 1
                    timeSlots.forEach { (start, end) ->
                        val pairName = "${pairCounter++} пара"
                        val item = itemsByTime[start]
                        if (item != null) filledList.add(item) else filledList.add(createEmptyLesson(start, end, pairName))
                    }
                    map[dayMillis] = filledList
                }
            } else { map.putAll(grouped) }
            _scheduleMap.postValue(map)
        }
    }

    // --- ВОТ ОНИ, ПРОПАВШИЕ МЕТОДЫ ---

    private fun setInitialPage() {
        val today = getStartOfDay(System.currentTimeMillis())
        val startCal = Calendar.getInstance().apply { time = semesterStartDate }
        toStartOfDay(startCal); startCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        if (startCal.time.after(semesterStartDate)) startCal.add(Calendar.DAY_OF_MONTH, -7)
        val pagerStart = startCal.timeInMillis
        val diff = today - pagerStart
        val days = (diff / (1000 * 60 * 60 * 24)).toInt()
        if (days >= 0) _currentPagerPosition.postValue(days) else _currentPagerPosition.postValue(0)
    }

    fun onPageChanged(position: Int) {
        _currentPagerPosition.postValue(position)

        // 1. Вычисляем дату, на которую мы перелистнули
        val cal = Calendar.getInstance()
        cal.time = semesterStartDate
        // Сбрасываем время в 00:00:00 для чистоты сравнения
        cal.set(Calendar.HOUR_OF_DAY, 12) // Берем полдень, чтобы избежать проблем с переходом суток
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, position)

        val currentDateInMillis = cal.timeInMillis

        // 2. Ищем неделю, которая содержит эту дату
        val currentWeeks = _weeks.value ?: return

        // Оптимизация: проверяем, нужно ли вообще обновлять список
        val alreadySelectedWeek = currentWeeks.find { it.isSelected }

        // Если текущая дата уже внутри выделенной недели — выходим (не тратим ресурсы)
        if (alreadySelectedWeek != null &&
            currentDateInMillis >= alreadySelectedWeek.startDate.time &&
            currentDateInMillis <= alreadySelectedWeek.endDate.time) {
            return
        }

        // 3. Если неделя сменилась — обновляем список
        val updated = currentWeeks.map { week ->
            // Проверка: Дата попадает в диапазон [Начало недели; Конец недели]
            // Добавляем небольшой буфер (1 час), чтобы компенсировать возможные сдвиги
            val isSelected = currentDateInMillis >= (week.startDate.time - 3600000) &&
                    currentDateInMillis <= (week.endDate.time + 3600000)
            week.copy(isSelected = isSelected)
        }
        _weeks.postValue(updated)
    }

    fun getLessonsForDate(dateMillis: Long): List<ScheduleItem> {
        val dayStart = getStartOfDay(dateMillis)
        return _scheduleMap.value?.get(dayStart) ?: emptyList()
    }


    // --- Helpers ---

    private fun toStartOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    }
    private fun getStartOfDay(time: Long): Long {
        val cal = Calendar.getInstance(); cal.timeInMillis = time; toStartOfDay(cal); return cal.timeInMillis
    }
    private fun createEmptyLesson(startTime: String, endTime: String, pairName: String): ScheduleItem {
        return ScheduleItem(UUID.randomUUID().toString(), 0L, "Нет пары", pairName, startTime, endTime, "", "", "", "", null, null, null, "", null, null)
    }

    fun refreshSchedule() {
        _isRefreshing.postValue(true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Используем getCacheFileName() вместо переменной
                val fileName = getCacheFileName()
                val fileInputStream = getApplication<Application>().openFileInput(fileName)
                val jsonString = fileInputStream.reader().readText()
                fileInputStream.close()

                val cachedData = Gson().fromJson(jsonString, CachedData::class.java)
                loadSchedule(cachedData.targetId)
            } catch (e: Exception) {
                _isRefreshing.postValue(false)
                // Очищаем экран, если профиль пустой
                _scheduleTitle.postValue("Расписание не выбрано")
                _lastUpdateTime.postValue("")
                processRawSchedule(emptyList()) // Очищаем список
                _error.postValue("В этом профиле нет расписания. Найдите кого-нибудь.")
            }
        }
    }

    fun loadInitialSchedule(keepCurrentPosition: Boolean = false) {
        loadDataFromFile(keepCurrentPosition)
    }
    private fun openLoginActivity(personId: String?) {
        val intent = Intent(getApplication(), LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if(personId!=null) intent.putExtra("TARGET_ID", personId)
        getApplication<Application>().startActivity(intent)
    }
    private fun saveDataToFile(data: CachedData, items: List<ScheduleItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = getCacheFileName()
                val context = getApplication<Application>()

                // --- ПРОВЕРКА ИСТОРИИ ПЕРЕД ЗАПИСЬЮ ---
                checkAndArchiveHistory(context, items, fileName)
                // ---------------------------------------

                val jsonString = Gson().toJson(data)
                val fileOutputStream = context.openFileOutput(fileName, Context.MODE_PRIVATE)
                fileOutputStream.write(jsonString.toByteArray())
                fileOutputStream.close()
                Log.d(TAG, "Saved to $fileName")
            } catch (e: Exception) {}
        }
    }
    private fun loadDataFromFile(keepCurrentPosition: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = getCacheFileName()
                val fileInputStream = getApplication<Application>().openFileInput(fileName)
                val jsonString = fileInputStream.reader().readText()
                fileInputStream.close()

                val cachedData = Gson().fromJson(jsonString, CachedData::class.java)
                val scheduleResponse = Gson().fromJson(cachedData.scheduleJsonResponse, ScheduleResponse::class.java)
                val items = parseResponseV2(scheduleResponse).sortedBy { it.fullStartDate }
                currentTargetId = cachedData.targetId

                // Пересчитываем старт семестра
                if (items.isNotEmpty()) {
                    recalculateSemesterStart(items.first().fullStartDate)
                }
                updateProfileMaxDateInSettings(items)

                _scheduleTitle.postValue(cachedData.targetName)
                _lastUpdateTime.postValue("Последнее обновление: ${cachedData.lastUpdateTime}")

                processRawSchedule(items)

                if (!keepCurrentPosition) {
                    setInitialPage()
                } else {
                    // ИСПРАВЛЕНИЕ: Восстанавливаем неделю МАТЕМАТИЧЕСКИ
                    val currentPos = _currentPagerPosition.value ?: 0

                    // Формула: (Позиция / 7 дней) + 1 = Номер недели
                    val targetWeekNumber = (currentPos / 7) + 1

                    onPageChanged(currentPos)
                }
            } catch (e: Exception) {
                // Если файла нет (пустой профиль) - чистим UI
                _scheduleTitle.postValue("Расписание не выбрано")
                _lastUpdateTime.postValue("")
                processRawSchedule(emptyList())

                if (!keepCurrentPosition) {
                    setInitialPage()
                } else {
                    // Аналогично для catch блока
                    val currentPos = _currentPagerPosition.value ?: 0
                    val targetWeekNumber = (currentPos / 7) + 1
                    onPageChanged(currentPos)
                }
            }
        }
    }

    // ПАРСЕР (БЕЗ ИЗМЕНЕНИЙ)
    private fun parseResponseV2(response: ScheduleResponse): List<ScheduleItem> {
        val embedded = response.embedded ?: return emptyList()
        val scheduleItems = mutableListOf<ScheduleItem>()
        val personsMap = embedded.persons?.associateBy { "/${it.id}" } ?: emptyMap()
        val roomsMap = embedded.rooms?.associateBy { "/${it.id}" } ?: emptyMap()
        val coursesMap = embedded.courseUnits?.associateBy { "/${it.id}" } ?: emptyMap()
        val cyclesMap = embedded.cycleRealizations?.associateBy { "/${it.id}" } ?: emptyMap()
        val eventTeachers = mutableMapOf<String, MutableList<String>>()
        embedded.eventAttendees?.forEach { att ->
            val eventHref = att._links.event.href
            val personHref = att._links.person.href
            val eventId = eventHref.substringAfterLast("/")
            personsMap[personHref]?.let { person ->
                eventTeachers.getOrPut(eventId) { mutableListOf() }.add(person.fullName)
            }
        }
        val eventLocationsMap = embedded.eventLocations?.associateBy { it.eventId } ?: emptyMap()
        val eventRoomsMap = embedded.eventRooms?.associateBy { "/${it.id}" } ?: emptyMap()
        val eventTeamsMap = embedded.eventTeams?.associateBy { it.eventId } ?: emptyMap()
        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale("ru"))
        val dateFormat = SimpleDateFormat("d MMMM", Locale("ru"))

        embedded.events?.forEach { event ->
            val teacherStr = eventTeachers[event.id]?.joinToString("\n") ?: "не назначен"
            var roomStr = "не назначена"
            var locationType = "Очно"
            val loc = eventLocationsMap[event.id]
            if (loc != null) {
                if (loc.customLocation == "Online") {
                    roomStr = "Online"; locationType = "Online"
                } else {
                    val evRoomHref = loc._links.eventRooms?.href
                    if (evRoomHref != null) {
                        val evRoom = eventRoomsMap[evRoomHref]
                        val roomHref = evRoom?._links?.room?.href
                        if (roomHref != null) roomStr = roomsMap[roomHref]?.name ?: "неизвестно"
                    }
                }
            }
            val courseRef = event.links.courseUnit?.href
            val courseUnitId = courseRef?.substringAfterLast("/")
            val moduleShort = coursesMap[courseRef]?.nameShort
            val moduleFull = coursesMap[courseRef]?.name
            val cycleRef = event.links.cycleRealization?.href
            val prototypeId = coursesMap[courseRef]?.prototypeId
            val groupCode = cyclesMap[cycleRef]?.code
            val teamSize = eventTeamsMap[event.id]?.size
            try {
                val startDate = inputFormat.parse(event.start)
                val endDate = inputFormat.parse(event.end)
                if (startDate != null && endDate != null) {
                    val humanType = EventTypeMapper.getHumanReadableType(event.typeId)
                    scheduleItems.add(ScheduleItem(event.id, startDate.time, event.name, moduleShort, timeFormat.format(startDate), timeFormat.format(endDate), dateFormat.format(startDate), teacherStr, roomStr, humanType, moduleFull, groupCode, teamSize, locationType, courseUnitId, prototypeId))
                }
            } catch (e: Exception) { }
        }
        return scheduleItems
    }

    private fun loadPinnedStatus() {
        val pinnedIds = sharedPreferences.getStringSet("pinned_ids", emptySet()) ?: emptySet()
        allTargets.forEach { it.isPinned = it.id in pinnedIds }
        updatePinnedList()
    }
    fun togglePin(target: ScheduleTarget) {
        target.isPinned = !target.isPinned; savePinnedStatus(); updatePinnedList()
        _searchResults.value?.let { _searchResults.postValue(it.toList()) }
    }
    private fun getCacheFileName(): String {
        val context = getApplication<Application>()
        if (!ApiSettings.isParallelEnabled(context)) {
            return "schedule_cache_v2.json"
        }
        val index = ApiSettings.getCurrentProfile(context)
        return "schedule_cache_v2_$index.json"
    }
    private fun savePinnedStatus() {
        val pinnedIds = allTargets.filter { it.isPinned }.map { it.id }.toSet()
        sharedPreferences.edit().putStringSet("pinned_ids", pinnedIds).apply()
    }
    private fun updatePinnedList() { _pinnedTargets.postValue(allTargets.filter { it.isPinned }) }
    fun setNavigationMode(mode: NavigationMode) {
        _navigationMode.postValue(mode)
        sharedPreferences.edit().putString("nav_mode", mode.name).apply()
    }
    private fun loadNavigationMode() {
        val modeName = sharedPreferences.getString("nav_mode", NavigationMode.BOTH.name)
        val mode = try { NavigationMode.valueOf(modeName ?: NavigationMode.BOTH.name) } catch(e:Exception){NavigationMode.BOTH}
        _navigationMode.postValue(mode)
    }
    private fun loadShowEmptyLessonsMode() {
        val shouldShow = sharedPreferences.getBoolean("show_empty_lessons", true)
        _showEmptyLessons.postValue(shouldShow)
    }
    fun setShowEmptyLessons(shouldShow: Boolean) {
        if (_showEmptyLessons.value == shouldShow) return
        _showEmptyLessons.value = shouldShow
        sharedPreferences.edit().putBoolean("show_empty_lessons", shouldShow).apply()
        processRawSchedule(fullRawSchedule)
    }
    fun loadGrades(courseUnitId: String, prototypeId: String?) {
        // personId берем из currentTargetId, но в запросе он не нужен (сервер берет из токена)

        viewModelScope.launch(Dispatchers.IO) {
            _gradesLoading.postValue(true)
            try {
                val api = RetrofitInstance.getApi(getApplication())

                // 1. ЗАПРОС ОЦЕНОК (как было)
                val body = JsonObject().apply {
                    val ids = JsonArray()
                    ids.add(courseUnitId)
                    add("courseUnitRealizationId", ids)
                }
                val gradeResponse = api.getGrades(body)

                // Обработка оценок (тот же код)
                val summaryObj = gradeResponse.summaryObjects?.find {
                    it.typeName.contains("Итог текущ", ignoreCase = true)
                } ?: gradeResponse.summaryObjects?.firstOrNull()
                val totalScore = summaryObj?.resultCurrent?.resultValue ?: "0"

                val uiList = mutableListOf<GradeUiItem>()
                gradeResponse.lessonObjects?.forEach { lesson ->
                    val scoreStr = lesson.result?.resultValue
                    if (scoreStr != null) {
                        val scoreDouble = scoreStr.toDoubleOrNull() ?: 0.0
                        uiList.add(GradeUiItem(
                            name = lesson.typeName,
                            score = scoreStr,
                            isZero = scoreDouble == 0.0
                        ))
                    }
                }

                // 2. ЗАПРОС ТИПА КОНТРОЛЯ (Новое)
                var controlType: String? = null
                if (prototypeId != null) {
                    try {
                        val techResponse = api.getCourseTechnology(prototypeId)
                        // Берем ПОСЛЕДНИЙ элемент списка
                        val lastLesson = techResponse.lessons?.lastOrNull()
                        // Берем его имя (обычно там "Экзамен", "Зачет")
                        controlType = lastLesson?.lessonName
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка получения типа контроля: ${e.message}")
                    }
                }

                if (uiList.isEmpty() && totalScore == "0") {
                    _error.postValue("Баллы не найдены")
                    _gradeData.postValue(null)
                } else {
                    // Отправляем Тройку: (Балл, Список, ТипКонтроля)
                    _gradeData.postValue(Triple(totalScore, uiList, controlType))
                }

            } catch (e: Exception) {
                if (e is retrofit2.HttpException && e.code() == 401) {
                    // Не показываем ошибку, а триггерим событие авторизации
                    // Сохраняем данные, чтобы повторить запрос после входа
                    authRequired.postValue(Pair(courseUnitId, prototypeId))
                } else {
                    Log.e(TAG, "Grades Error", e)
                    _error.postValue("Не удалось получить баллы")
                    _gradeData.postValue(null)
                }
            } finally {
                _gradesLoading.postValue(false)
            }
        }
    }

    // Метод очистки тоже обнови
    fun clearGradeResult() {
        _gradeData.value = null
    }

    fun loadGlobalGradebook() {
        val personId = currentTargetId ?: return

        // 1. Собираем уникальные предметы из расписания
        // Берем fullRawSchedule, фильтруем те, у которых есть courseUnitId
        val uniqueSubjects = fullRawSchedule
            .filter { it.courseUnitId != null }
            .groupBy { it.courseUnitId!! } // Группируем: ID -> Список пар
            .map { (id, lessons) ->
                // Пытаемся найти нормальное название модуля (предмета)
                // Если moduleFullName везде null (редко, но бывает), берем имя первой пары
                val realName = lessons.firstNotNullOfOrNull { it.moduleFullName }
                    ?: lessons.first().subject

                realName to id
            }

        if (uniqueSubjects.isEmpty()) {
            _error.postValue("В расписании нет предметов для проверки.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _gradesLoading.postValue(true) // Используем тот же лоадер
            try {
                val api = RetrofitInstance.getApi(getApplication())

                // 2. Запускаем параллельные запросы (Async)
                val deferredResults = uniqueSubjects.map { (name, id) ->
                    async {
                        try {
                            val body = JsonObject().apply {
                                val ids = JsonArray()
                                ids.add(id)
                                add("courseUnitRealizationId", ids)
                            }
                            val response = api.getGrades(body)

                            // 1. Итог
                            val summaryObj = response.summaryObjects?.find {
                                it.typeName.contains("Итог текущ", ignoreCase = true)
                            } ?: response.summaryObjects?.firstOrNull()

                            val score = summaryObj?.resultCurrent?.resultValue ?: "0"

                            // 2. Детали (парсим список работ)
                            val detailsList = mutableListOf<GradeUiItem>()
                            response.lessonObjects?.forEach { lesson ->
                                val scoreStr = lesson.result?.resultValue
                                if (scoreStr != null) {
                                    val scoreDouble = scoreStr.toDoubleOrNull() ?: 0.0
                                    detailsList.add(GradeUiItem(
                                        name = lesson.typeName,
                                        score = scoreStr,
                                        isZero = scoreDouble == 0.0
                                    ))
                                }
                            }

                            // Возвращаем объект с деталями
                            GradebookEntry(name, score, detailsList)

                        } catch (e: Exception) {
                            GradebookEntry(name, "Ошибка", emptyList())
                        }
                    }
                }

                // 3. Ждем выполнения всех запросов
                val results = deferredResults.awaitAll()

                // 4. Сортируем: сначала с оценками, потом нули
                val sortedResults = results.sortedByDescending {
                    it.score.toDoubleOrNull() ?: -1.0
                }

                _gradebookData.postValue(sortedResults)

            } catch (e: Exception) {
                Log.e(TAG, "Gradebook Error", e)
                _error.postValue("Ошибка загрузки зачетки")
            } finally {
                _gradesLoading.postValue(false)
            }
        }
    }

    // Очистка после показа
    fun clearGradebookData() {
        _gradebookData.value = emptyList() // или null, если поменяешь тип LiveData на nullable
    }

    private fun updateProfileMaxDateInSettings(items: List<ScheduleItem>) {
        if (items.isEmpty()) return

        // Находим дату самой последней пары в этом списке
        val maxTime = items.maxOf { it.fullStartDate }

        // Определяем индекс текущего профиля
        val context = getApplication<Application>()
        val currentIndex = if (ApiSettings.isParallelEnabled(context)) {
            ApiSettings.getCurrentProfile(context)
        } else {
            0
        }

        // Сохраняем в настройки
        ApiSettings.saveProfileMaxDate(context, currentIndex, maxTime)
    }

    // --- НОВАЯ ЛОГИКА: Определение начала семестра по первой паре ---
    private fun recalculateSemesterStart(firstLessonMillis: Long) {
        val cal = Calendar.getInstance()
        cal.timeInMillis = firstLessonMillis

        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) // 0 = Январь, 1 = Февраль ...

        val newSemesterStart = Calendar.getInstance()

        // Логика:
        // Если пара в Феврале(1) ... Августе(7) -> Это Весенний семестр (Начало ~7-9 Февраля)
        // Иначе (Сентябрь...Январь) -> Это Осенний семестр (Начало 1 Сентября)

        if (month in Calendar.FEBRUARY..Calendar.AUGUST) {
            // ВЕСНА
            newSemesterStart.set(year, Calendar.FEBRUARY, 7, 0, 0, 0)
            // Если вдруг 7 февраля это выходной или середина недели, можно корректировать,
            // но для отсчета недель 7 число подходит идеально (Modeus обычно ставит начало 2-го семестра тут).
        } else {
            // ОСЕНЬ
            // Если месяц Январь(0), значит это конец осеннего семестра, который начался в ПРОШЛОМ году
            val startYear = if (month == Calendar.JANUARY) year - 1 else year
            newSemesterStart.set(startYear, Calendar.SEPTEMBER, 1, 0, 0, 0)
        }

        newSemesterStart.set(Calendar.MILLISECOND, 0)

        // Применяем, только если дата изменилась
        if (semesterStartDate.time != newSemesterStart.timeInMillis) {
            semesterStartDate = newSemesterStart.time
            Log.d(TAG, "Semester Start Updated to: $semesterStartDate based on first lesson: ${Date(firstLessonMillis)}")
        }
    }

    private fun checkAndArchiveHistory(context: Context, newItems: List<ScheduleItem>, currentCacheFileName: String) {
        // 1. Глобальная проверка: Включена ли история вообще?
        if (!ApiSettings.isHistoryEnabled(context) || newItems.isEmpty()) return

        // 2. Проверка профиля (если мультипрофиль включен)
        if (ApiSettings.isParallelEnabled(context)) {
            val currentProfileIndex = ApiSettings.getCurrentProfile(context).toString()
            val allowedProfiles = ApiSettings.getHistoryAllowedProfiles(context)

            // Если текущего профиля нет в списке разрешенных -> Выходим
            if (!allowedProfiles.contains(currentProfileIndex)) {
                return
            }
        }

        val dbFile = File(context.filesDir, currentCacheFileName)
        if (!dbFile.exists()) return

        try {
            // 1. Читаем СТАРЫЙ кэш (который сейчас будем перезаписывать)
            val oldJson = dbFile.readText()
            val oldCachedData = Gson().fromJson(oldJson, CachedData::class.java)
            val oldResponse = Gson().fromJson(oldCachedData.scheduleJsonResponse, ScheduleResponse::class.java)
            val oldItems = parseResponseV2(oldResponse).sortedBy { it.fullStartDate }

            if (oldItems.isEmpty()) return

            // 2. Сравниваем семестры
            // Берем первую пару старого и нового расписания
            val oldFirst = Calendar.getInstance().apply { timeInMillis = oldItems.first().fullStartDate }
            val newFirst = Calendar.getInstance().apply { timeInMillis = newItems.first().fullStartDate }

            // Если разница между первыми парами больше 3 месяцев (примерно), считаем это новым семестром
            // Или проверяем, перешли ли мы границу "Февраль/Сентябрь"
            val oldMonth = oldFirst.get(Calendar.MONTH)
            val newMonth = newFirst.get(Calendar.MONTH)

            // Простая проверка: если мы перепрыгнули из Осени (Сен-Янв) в Весну (Фев-Авг) или наоборот
            val isOldSpring = oldMonth in Calendar.FEBRUARY..Calendar.AUGUST
            val isNewSpring = newMonth in Calendar.FEBRUARY..Calendar.AUGUST

            if (isOldSpring != isNewSpring || Math.abs(newFirst.get(Calendar.YEAR) - oldFirst.get(Calendar.YEAR)) > 0) {
                // СЕМЕСТР СМЕНИЛСЯ! Сохраняем старый файл в историю.

                val semesterName = if (isOldSpring) "Весна" else "Осень"
                val year = oldFirst.get(Calendar.YEAR)
                val targetName = oldCachedData.targetName.replace(" ", "_") // Убираем пробелы

                // Имя файла: history_Иванов_ИИ_Осень_2025.json
                val historyFileName = "history_${targetName}_${semesterName}_$year.json"
                val historyFile = File(context.filesDir, historyFileName)

                if (!historyFile.exists()) {
                    historyFile.writeText(oldJson)
                    Log.d(TAG, "History archived: $historyFileName")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive history", e)
        }
    }

    fun renameHistoryFile(oldName: String, newName: String): Boolean {
        try {
            val oldFile = File(getApplication<Application>().filesDir, oldName)
            // Формируем правильное имя файла (history_Name_...)
            // Но пользователь вводит просто "Осень 2023", нам надо сохранить префикс history_
            // Проще всего: сохранить как есть, но добавить .json если нет
            val safeName = if (newName.endsWith(".json")) newName else "$newName.json"
            val prefixName = if (safeName.startsWith("history_")) safeName else "history_$safeName"

            val newFile = File(getApplication<Application>().filesDir, prefixName)
            return oldFile.renameTo(newFile)
        } catch (e: Exception) {
            return false
        }
    }

    fun downloadCustomSchedule(saveName: String, targetId: String, targetName: String, dateStart: String, dateEnd: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _searchInProgress.postValue(true) // Используем лоадер поиска
            try {
                val api = RetrofitInstance.getApi(getApplication())
                val apiSource = ApiSettings.getApiSource(getApplication())

                // Тело запроса
                val body = JsonObject().apply {
                    addProperty("size", 3000)
                    addProperty("timeMin", dateStart)
                    addProperty("timeMax", dateEnd)

                    val ids = JsonArray()
                    ids.add(targetId)
                    // Пытаемся угадать тип по ID (или просто шлем оба поля, сервер разберется, если повезет)
                    // Но лучше знать тип. Давай считать всех людьми по умолчанию,
                    // или искать targetId в базе allTargets
                    val target = allTargets.find { it.id == targetId }
                    if (target?.type == TargetType.ROOM) {
                        add("roomId", ids)
                    } else {
                        add("attendeePersonId", ids)
                    }
                }

                // Запрос
                val responseBody = if (apiSource == ApiSource.SFEDU) {
                    api.getScheduleSfedu(body)
                } else {
                    val endpoint = ApiSettings.getRdEndpoint(getApplication())
                    api.getScheduleRdCenter(endpoint, body)
                }
                val responseString = responseBody.string()

                // Сохранение
                val currentTime = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
                val dataToCache = CachedData(targetId, targetName, currentTime, responseString)

                // Формируем имя файла
                val safeName = if (saveName.endsWith(".json")) saveName else "$saveName.json"
                val fileName = if (safeName.startsWith("history_")) safeName else "history_$safeName"

                val context = getApplication<Application>()
                val jsonToWrite = Gson().toJson(dataToCache)
                context.openFileOutput(fileName, Context.MODE_PRIVATE).use {
                    it.write(jsonToWrite.toByteArray())
                }

                _error.postValue("Успешно скачано: $fileName")

            } catch (e: Exception) {
                Log.e(TAG, "Custom DL Error", e)
                _error.postValue("Ошибка скачивания: ${e.message}")
            } finally {
                _searchInProgress.postValue(false)
            }
        }
    }

    fun filterTargetsSync(query: String): List<ScheduleTarget> {
        if (query.length < 2) return emptyList()
        return allTargets.filter {
            it.name.contains(query, ignoreCase = true)
        }.take(10) // Ограничим 10 результатами, чтобы список не был бесконечным
    }

    fun debugSwitchSemester() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Определяем "Противоположный" семестр
                val cal = Calendar.getInstance()
                cal.time = semesterStartDate
                val currentMonth = cal.get(Calendar.MONTH)

                val targetDate: Date
                val targetName: String

                // Если сейчас Весна (Фев-Авг) -> Делаем Осень (Сентябрь)
                if (currentMonth in Calendar.FEBRUARY..Calendar.AUGUST) {
                    cal.set(Calendar.MONTH, Calendar.SEPTEMBER)
                    cal.set(Calendar.DAY_OF_MONTH, 1)
                    targetName = "Debug Осень ${cal.get(Calendar.YEAR)}"
                } else {
                    // Если сейчас Осень -> Делаем Весну (Февраль следующего года)
                    cal.add(Calendar.YEAR, 1)
                    cal.set(Calendar.MONTH, Calendar.FEBRUARY)
                    cal.set(Calendar.DAY_OF_MONTH, 10)
                    targetName = "Debug Весна ${cal.get(Calendar.YEAR)}"
                }

                // Ставим время пары на 10:00
                cal.set(Calendar.HOUR_OF_DAY, 10)
                cal.set(Calendar.MINUTE, 0)
                targetDate = cal.time

                // 2. Создаем Фейковую Пару
                val fakeItem = ScheduleItem(
                    id = UUID.randomUUID().toString(),
                    fullStartDate = targetDate.time,
                    subject = "ТЕСТ СМЕНЫ СЕМЕСТРА",
                    moduleShortName = "DEBUG",
                    startTime = "10:00",
                    endTime = "11:35",
                    date = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(targetDate),
                    teacher = "System",
                    room = "Server",
                    type = "TEST",
                    moduleFullName = "Тестирование архивации",
                    groupCode = "000",
                    teamSize = 1,
                    locationType = "Debug",
                    courseUnitId = null,
                    coursePrototypeId = null
                )

                // 3. Создаем Фейковый JSON ответа (чтобы парсер не упал при следующем запуске)
                // Нам нужно, чтобы внутри была хотя бы одна пара с нужной датой
                val fakeJson = """
                    {
                      "_embedded": {
                        "events": [
                          {
                            "id": "${fakeItem.id}",
                            "name": "${fakeItem.subject}",
                            "start": "${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()).format(targetDate)}",
                            "end": "${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()).format(Date(targetDate.time + 5400000))}",
                            "typeId": "LECT",
                            "_links": { "course-unit-realization": { "href": "null" }, "cycle-realization": { "href": "null" } }
                          }
                        ]
                      }
                    }
                """.trimIndent()

                // 4. Формируем кэш
                val currentId = currentTargetId ?: "debug_id"
                val fakeCache = CachedData(currentId, targetName, "DEBUG GENERATED", fakeJson)
                val newItems = listOf(fakeItem)

                // 5. СОХРАНЯЕМ (Это вызовет checkAndArchiveHistory, так как даты сильно отличаются)
                saveDataToFile(fakeCache, newItems)

                // 6. Перезагружаем (чтобы увидеть изменения на экране)
                // Небольшая задержка, чтобы файл успел записаться
                delay(500)
                loadDataFromFile()

                _error.postValue("Сгенерирован новый семестр: $targetName. Старый должен быть в архиве.")

            } catch (e: Exception) {
                Log.e(TAG, "Debug Error", e)
                _error.postValue("Ошибка дебага: ${e.message}")
            }
        }
    }
}