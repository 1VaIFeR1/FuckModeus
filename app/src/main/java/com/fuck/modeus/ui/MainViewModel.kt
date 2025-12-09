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
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

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

    private fun loadAllIds() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val dbFile = File(context.filesDir, dbFileName)

                if (!dbFile.exists()) {
                    Log.d(TAG, "DB: Локальная база не найдена. Копируем allid.txt из assets...")
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

                val type = object : TypeToken<List<ScheduleTarget>>() {}.type
                val targets: List<ScheduleTarget> = Gson().fromJson(jsonString, type)

                // Фильтруем дубликаты
                allTargets = targets.distinctBy { it.id }
                Log.d(TAG, "DB: Загружено ${allTargets.size} записей.")

                loadPinnedStatus()
            } catch (e: Exception) {
                Log.e(TAG, "DB: Критическая ошибка", e)
                _error.postValue("Ошибка базы данных: ${e.message}")
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

            // Ищем по имени ИЛИ по описанию (тегам)
            val filtered = allTargets.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.description.contains(query, ignoreCase = true)
            }

            // Сортируем: сначала те, у кого совпадение в начале имени
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
                    saveDataToFile(dataToCache)

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
        if (items.isEmpty()) {
            val start = semesterStartDate
            val cal = Calendar.getInstance().apply { time = start }
            while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) cal.add(Calendar.DAY_OF_MONTH, -1)
            val monday = cal.time
            cal.add(Calendar.DAY_OF_MONTH, 6)
            val sunday = cal.time
            val dateFormat = SimpleDateFormat("dd", Locale("ru"))
            weekList.add(WeekItem(1, monday, sunday, "1 неделя (${dateFormat.format(monday)}-${dateFormat.format(sunday)})", true))
            _weeks.postValue(weekList)
            return
        }
        val maxTime = items.maxOf { it.fullStartDate }
        val limitCal = Calendar.getInstance().apply { timeInMillis = maxTime }
        while (limitCal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) limitCal.add(Calendar.DAY_OF_MONTH, 1)
        limitCal.set(Calendar.HOUR_OF_DAY, 23); limitCal.set(Calendar.MINUTE, 59); limitCal.set(Calendar.SECOND, 59)
        val hardLimit = limitCal.time
        val weekCalendar = Calendar.getInstance().apply {
            time = semesterStartDate; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        var weekNumber = 1
        val dateFormat = SimpleDateFormat("dd", Locale("ru"))
        val today = Calendar.getInstance().time
        while (weekCalendar.time.before(hardLimit) || weekCalendar.time.time == hardLimit.time) {
            weekCalendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            val startDate = weekCalendar.time
            weekCalendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            if (startDate.after(weekCalendar.time)) weekCalendar.add(Calendar.DAY_OF_MONTH, 7)
            val endDate = weekCalendar.time
            val isSelected = (today.time >= startDate.time && today.time <= endDate.time)
            weekList.add(WeekItem(weekNumber, startDate, endDate, "$weekNumber неделя (${dateFormat.format(startDate)}-${dateFormat.format(endDate)})", isSelected))
            if (endDate.time >= hardLimit.time) break
            weekNumber++
            weekCalendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        if (weekList.isNotEmpty() && weekList.none { it.isSelected }) {
            if (today.after(weekList.last().endDate)) weekList.last().isSelected = true else weekList.first().isSelected = true
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
        _currentPagerPosition.value = position
        val startCal = Calendar.getInstance().apply { time = semesterStartDate }
        toStartOfDay(startCal); startCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        if (startCal.time.after(semesterStartDate)) startCal.add(Calendar.DAY_OF_MONTH, -7)
        startCal.add(Calendar.DAY_OF_YEAR, position)
        val currentDate = startCal.time
        updateSelectedWeek(currentDate)
    }

    fun getLessonsForDate(dateMillis: Long): List<ScheduleItem> {
        val dayStart = getStartOfDay(dateMillis)
        return _scheduleMap.value?.get(dayStart) ?: emptyList()
    }

    private fun updateSelectedWeek(currentDate: Date) {
        val currentWeeks = _weeks.value ?: return
        val updated = currentWeeks.map { week ->
            val isSelected = currentDate.time >= week.startDate.time && currentDate.time <= week.endDate.time
            week.copy(isSelected = isSelected)
        }
        _weeks.postValue(updated)
    }

    // --- Helpers ---

    private fun toStartOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    }
    private fun getStartOfDay(time: Long): Long {
        val cal = Calendar.getInstance(); cal.timeInMillis = time; toStartOfDay(cal); return cal.timeInMillis
    }
    private fun createEmptyLesson(startTime: String, endTime: String, pairName: String): ScheduleItem {
        return ScheduleItem(UUID.randomUUID().toString(), 0L, "Нет пары", pairName, startTime, endTime, "", "", "", "", null, null, null, "")
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
    private fun saveDataToFile(data: CachedData) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = getCacheFileName() // Получаем динамическое имя
                val jsonString = Gson().toJson(data)
                val fileOutputStream = getApplication<Application>().openFileOutput(fileName, Context.MODE_PRIVATE)
                fileOutputStream.write(jsonString.toByteArray())
                fileOutputStream.close()
                Log.d(TAG, "Saved to $fileName")
            } catch (e: Exception) {}
        }
    }
    private fun loadDataFromFile(keepCurrentPosition: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = getCacheFileName() // Получаем имя (например schedule_cache_v2_1.json)

                // ИСПРАВЛЕНО: используем fileName, а не cacheFileName
                val fileInputStream = getApplication<Application>().openFileInput(fileName)
                val jsonString = fileInputStream.reader().readText()
                fileInputStream.close()

                val cachedData = Gson().fromJson(jsonString, CachedData::class.java)
                val scheduleResponse = Gson().fromJson(cachedData.scheduleJsonResponse, ScheduleResponse::class.java)
                val items = parseResponseV2(scheduleResponse).sortedBy { it.fullStartDate }

                _scheduleTitle.postValue(cachedData.targetName)
                _lastUpdateTime.postValue("Последнее обновление: ${cachedData.lastUpdateTime}")

                processRawSchedule(items)

                // ИСПРАВЛЕНО: Теперь переменная keepCurrentPosition доступна
                if (!keepCurrentPosition) {
                    setInitialPage()
                }
            } catch (e: Exception) {
                // Если файла нет (пустой профиль) - чистим UI
                _scheduleTitle.postValue("Расписание не выбрано")
                _lastUpdateTime.postValue("")
                processRawSchedule(emptyList())

                if (!keepCurrentPosition) {
                    setInitialPage()
                }
                // Не кидаем ошибку в тост, это нормальная ситуация
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
            val moduleShort = coursesMap[courseRef]?.nameShort
            val moduleFull = coursesMap[courseRef]?.name
            val cycleRef = event.links.cycleRealization?.href
            val groupCode = cyclesMap[cycleRef]?.code
            val teamSize = eventTeamsMap[event.id]?.size
            try {
                val startDate = inputFormat.parse(event.start)
                val endDate = inputFormat.parse(event.end)
                if (startDate != null && endDate != null) {
                    val humanType = EventTypeMapper.getHumanReadableType(event.typeId)
                    scheduleItems.add(ScheduleItem(event.id, startDate.time, event.name, moduleShort, timeFormat.format(startDate), timeFormat.format(endDate), dateFormat.format(startDate), teacherStr, roomStr, humanType, moduleFull, groupCode, teamSize, locationType))
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
}