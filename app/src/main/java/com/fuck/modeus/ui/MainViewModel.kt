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
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

enum class SwipeDirection { LEFT, RIGHT, NONE }
enum class NavigationMode { TOUCH, SWIPE }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "FuckModeus_DEBUG"

    // --- Константы семестров ---
    private val AUTUMN_START_MONTH = Calendar.SEPTEMBER // Сентябрь (8)
    private val AUTUMN_START_DAY = 1
    private val AUTUMN_END_MONTH = Calendar.FEBRUARY // Февраль (1)
    private val AUTUMN_END_DAY = 5

    private val SPRING_START_MONTH = Calendar.FEBRUARY // Февраль (1)
    private val SPRING_START_DAY = 7 // Весна начинается с 7 февраля
    private val SPRING_END_MONTH = Calendar.AUGUST // Август (7)
    private val SPRING_END_DAY = 31

    private val timeSlots = mapOf(
        "08:00" to "09:35", "09:50" to "11:25", "11:55" to "13:30",
        "13:45" to "15:20", "15:50" to "17:25", "17:35" to "19:10",
        "19:15" to "20:50", "20:55" to "21:40"
    )
    private var fullSchedule: List<ScheduleItem> = emptyList()
    private val _filteredSchedule = MutableLiveData<List<ScheduleItem>>()
    val filteredSchedule: LiveData<List<ScheduleItem>> = _filteredSchedule

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error
    private val _lastUpdateTime = MutableLiveData<String>()
    val lastUpdateTime: LiveData<String> = _lastUpdateTime

    // Search
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

    // Dates
    private val _weeks = MutableLiveData<List<WeekItem>>()
    val weeks: LiveData<List<WeekItem>> = _weeks
    private val _days = MutableLiveData<List<DayItem>>()
    val days: LiveData<List<DayItem>> = _days
    private var selectedDate: Date = Calendar.getInstance().time

    // UX
    private val _swipeDirection = MutableLiveData(SwipeDirection.NONE)
    val swipeDirection: LiveData<SwipeDirection> = _swipeDirection
    private val _navigationMode = MutableLiveData(NavigationMode.TOUCH)
    val navigationMode: LiveData<NavigationMode> = _navigationMode
    private val _showEmptyLessons = MutableLiveData(true)
    val showEmptyLessons: LiveData<Boolean> = _showEmptyLessons

    private val cacheFileName = "schedule_cache_v2.json"
    private val sharedPreferences = application.getSharedPreferences("schedule_prefs", Application.MODE_PRIVATE)

    init {
        Log.d(TAG, "ViewModel: init")
        loadAllIds()
        loadNavigationMode()
        loadShowEmptyLessonsMode()
    }

    // --- ПУБЛИЧНЫЕ МЕТОДЫ НАСТРОЕК ---

    fun setShowEmptyLessons(shouldShow: Boolean) {
        if (_showEmptyLessons.value == shouldShow) return
        _showEmptyLessons.value = shouldShow
        sharedPreferences.edit().putBoolean("show_empty_lessons", shouldShow).apply()
        filterScheduleForSelectedDate()
    }

    fun setNavigationMode(isTouchMode: Boolean) {
        val newMode = if (isTouchMode) NavigationMode.TOUCH else NavigationMode.SWIPE
        _navigationMode.postValue(newMode)
        val modeName = if (isTouchMode) NavigationMode.TOUCH.name else NavigationMode.SWIPE.name
        sharedPreferences.edit().putString("nav_mode", modeName).apply()
    }

    private fun loadShowEmptyLessonsMode() {
        val shouldShow = sharedPreferences.getBoolean("show_empty_lessons", true)
        _showEmptyLessons.postValue(shouldShow)
    }

    private fun loadNavigationMode() {
        val modeName = sharedPreferences.getString("nav_mode", NavigationMode.TOUCH.name)
        val mode = try {
            NavigationMode.valueOf(modeName ?: NavigationMode.TOUCH.name)
        } catch (e: Exception) {
            NavigationMode.TOUCH
        }
        _navigationMode.postValue(mode)
    }

    // --- ЛОГИКА СЕМЕСТРОВ ---

    data class SemesterBounds(
        val apiMinDate: String,
        val apiMaxDate: String,
        val semesterStartDate: Date,
        val description: String
    )

    private fun getSemesterBounds(): SemesterBounds {
        val cal = Calendar.getInstance()
        val currentYear = cal.get(Calendar.YEAR)
        val currentMonth = cal.get(Calendar.MONTH)
        val currentDay = cal.get(Calendar.DAY_OF_MONTH)

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
        val startCal = Calendar.getInstance()
        val endCal = Calendar.getInstance()
        val desc: String

        val isAutumnCurrentYear = currentMonth >= Calendar.AUGUST
        val isAutumnNextYearPart = (currentMonth == Calendar.JANUARY) || (currentMonth == Calendar.FEBRUARY && currentDay <= 6)

        if (isAutumnCurrentYear || isAutumnNextYearPart) {
            desc = "Осень"
            val startYear = if (isAutumnNextYearPart) currentYear - 1 else currentYear
            startCal.set(startYear, AUTUMN_START_MONTH, AUTUMN_START_DAY, 0, 0, 0)
            endCal.set(startYear + 1, AUTUMN_END_MONTH, AUTUMN_END_DAY, 23, 59, 59)
        } else {
            desc = "Весна"
            startCal.set(currentYear, SPRING_START_MONTH, SPRING_START_DAY, 0, 0, 0)
            endCal.set(currentYear, SPRING_END_MONTH, SPRING_END_DAY, 23, 59, 59)
        }

        return SemesterBounds(
            apiMinDate = sdf.format(startCal.time),
            apiMaxDate = sdf.format(endCal.time),
            semesterStartDate = startCal.time,
            description = "$desc ${startCal.get(Calendar.YEAR)}"
        )
    }

    // --- ЗАГРУЗКА РАСПИСАНИЯ ---

    fun loadSchedule(personId: String) {
        Log.d(TAG, "ViewModel: loadSchedule called for ID: $personId")

        val apiSource = ApiSettings.getApiSource(getApplication())
        Log.d(TAG, "ViewModel: Selected API Source: $apiSource")
        if (apiSource == ApiSource.SFEDU) {
            val token = TokenManager.getToken(getApplication())
            if (token == null) {
                _error.postValue("Требуется вход через Microsoft")
                openLoginActivity(personId)
                return
            }
        }

        viewModelScope.launch {
            try {
                val bounds = getSemesterBounds()
                Log.d(TAG, "ViewModel: Семестр: ${bounds.description} (${bounds.apiMinDate} - ${bounds.apiMaxDate})")

                val requestBody = JsonObject().apply {
                    addProperty("size", 3000)
                    addProperty("timeMin", bounds.apiMinDate)
                    addProperty("timeMax", bounds.apiMaxDate)
                    val ids = JsonArray()
                    ids.add(personId)
                    add("attendeePersonId", ids)
                }

                val api = RetrofitInstance.getApi(getApplication())
                val responseBody = if (apiSource == ApiSource.SFEDU) {
                    api.getScheduleSfedu(requestBody)
                } else {
                    // RDCenter может требовать старый формат (просто size, min, max без ID)
                    // Но обычно они совпадают. Попробуем отослать тот же JSON.
                    api.getScheduleRdCenter(requestBody)
                }
                val responseString = responseBody.string()

                Log.d(TAG, "ViewModel: Ответ получен. Длина: ${responseString.length}")

                if (responseString.isNotBlank()) {
                    val scheduleResponse = Gson().fromJson(responseString, ScheduleResponse::class.java)
                    val scheduleItems = parseResponseV2(scheduleResponse)
                    Log.d(TAG, "ViewModel: Распаршено пар: ${scheduleItems.size}")

                    fullSchedule = scheduleItems.sortedBy { it.fullStartDate }

                    val targetName = allTargets.find { it.person_id == personId }?.name ?: "Расписание"
                    val currentTime = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())

                    val dataToCache = CachedData(
                        targetId = personId,
                        targetName = targetName,
                        lastUpdateTime = currentTime,
                        scheduleJsonResponse = responseString
                    )
                    saveDataToFile(dataToCache)

                    _scheduleTitle.postValue(targetName)
                    _lastUpdateTime.postValue("Последнее обновление: $currentTime")
                    processNewScheduleData()
                } else {
                    Log.e(TAG, "ViewModel: Ответ сервера пустой")
                    _error.postValue("Ошибка: получен пустой ответ от сервера.")
                }
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 401) {
                    Log.w(TAG, "ViewModel: 401 Unauthorized. Сбрасываем токен.")
                    TokenManager.clearToken(getApplication())
                    _error.postValue("Сессия истекла. Пожалуйста, войдите снова.")
                    openLoginActivity(personId)
                } else {
                    _error.postValue("Ошибка сервера: ${e.code()}")
                    loadDataFromFile()
                }
            } catch (e: Exception) {
                Log.e(TAG, "ViewModel: Ошибка сети/парсинга", e)
                _error.postValue("Ошибка сети: ${e.message}")
                loadDataFromFile()
            }
        }
    }

    // --- ОБНОВЛЕНИЕ (SWIPE REFRESH) ---

    fun refreshSchedule() {
        Log.d(TAG, "ViewModel: refreshSchedule called")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileInputStream = getApplication<Application>().openFileInput(cacheFileName)
                val jsonString = fileInputStream.reader().readText()
                fileInputStream.close()
                val cachedData = Gson().fromJson(jsonString, CachedData::class.java)

                // Вызываем загрузку с ID из кеша
                loadSchedule(cachedData.targetId)
            } catch (e: Exception) {
                Log.e(TAG, "ViewModel: Не могу обновить: кеш пуст или ошибка чтения", e)
                _error.postValue("Сначала выберите расписание в поиске")
            }
        }
    }

    // --- ГЕНЕРАЦИЯ НЕДЕЛЬ (ИСПРАВЛЕНО) ---

    private fun generateWeeks() {
        if (fullSchedule.isEmpty()) {
            _weeks.postValue(emptyList())
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            val bounds = getSemesterBounds()
            val semesterStartDate = bounds.semesterStartDate

            // 1. Находим дату САМОЙ ПОСЛЕДНЕЙ ПАРЫ (в миллисекундах)
            val maxLessonTime = fullSchedule.maxOfOrNull { it.fullStartDate } ?: System.currentTimeMillis()

            // 2. Вычисляем границу цикла (конец недели, в которой эта последняя пара)
            val limitCal = Calendar.getInstance().apply { timeInMillis = maxLessonTime }
            // Устанавливаем на Воскресенье
            limitCal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            // Если из-за особенностей Calendar API (воскресенье=1) мы ушли назад во времени - добавляем 7 дней
            if (limitCal.timeInMillis < maxLessonTime) {
                limitCal.add(Calendar.DAY_OF_MONTH, 7)
            }
            // Ставим конец дня, чтобы сравнение .before() сработало корректно
            limitCal.set(Calendar.HOUR_OF_DAY, 23)
            limitCal.set(Calendar.MINUTE, 59)

            val weekList = mutableListOf<WeekItem>()

            // Календарь для итерации (начинаем с начала семестра)
            val weekCalendar = Calendar.getInstance().apply {
                time = semesterStartDate
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            }

            if (weekCalendar.time.after(semesterStartDate)) {
                weekCalendar.add(Calendar.DAY_OF_MONTH, -7)
            }

            var weekNumber = 1
            val dateFormat = SimpleDateFormat("dd", Locale("ru"))
            val today = Calendar.getInstance().time

            // 3. Цикл работает, пока начало недели меньше предельной даты (последней пары)
            while (weekCalendar.time.before(limitCal.time)) {
                val startDate = weekCalendar.time
                val endWeekCal = Calendar.getInstance().apply { time = startDate; add(Calendar.DAY_OF_MONTH, 6) }
                val endDate = endWeekCal.time

                weekList.add(
                    WeekItem(
                        weekNumber = weekNumber,
                        startDate = startDate,
                        endDate = endDate,
                        displayableString = "$weekNumber неделя (${dateFormat.format(startDate)}-${dateFormat.format(endDate)})",
                        isSelected = today in startDate..endDate
                    )
                )

                weekNumber++
                weekCalendar.add(Calendar.DAY_OF_MONTH, 7)
            }

            // Выбор активной недели, если ничего не выбрано
            if (weekList.none { it.isSelected }) {
                if (today.after(weekList.last().endDate)) weekList.last().isSelected = true
                else weekList.first().isSelected = true
            }

            _weeks.postValue(weekList)
        }
    }

    // --- ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ---

    fun loadInitialSchedule() { loadDataFromFile() }

    private fun openLoginActivity(personId: String? = null) {
        val context = getApplication<Application>()
        val intent = Intent(context, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (personId != null) intent.putExtra("TARGET_ID", personId)
        context.startActivity(intent)
    }

    private fun saveDataToFile(cachedData: CachedData) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonString = Gson().toJson(cachedData)
                val fileOutputStream = getApplication<Application>().openFileOutput(cacheFileName, Context.MODE_PRIVATE)
                fileOutputStream.write(jsonString.toByteArray())
                fileOutputStream.close()
                Log.d(TAG, "ViewModel: Кеш успешно сохранен")
            } catch (e: Exception) { Log.e(TAG, "Error saving cache", e) }
        }
    }

    private fun loadDataFromFile() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileInputStream = getApplication<Application>().openFileInput(cacheFileName)
                val jsonString = fileInputStream.reader().readText()
                fileInputStream.close()
                val cachedData = Gson().fromJson(jsonString, CachedData::class.java)
                val scheduleResponse = Gson().fromJson(cachedData.scheduleJsonResponse, ScheduleResponse::class.java)
                val scheduleItems = parseResponseV2(scheduleResponse)
                fullSchedule = scheduleItems.sortedBy { it.fullStartDate }
                _scheduleTitle.postValue(cachedData.targetName)
                _lastUpdateTime.postValue("Последнее обновление: ${cachedData.lastUpdateTime}")
                processNewScheduleData()
                Log.d(TAG, "ViewModel: Данные загружены из кеша")
            } catch (e: Exception) { Log.e(TAG, "Cache error", e) }
        }
    }

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

    private fun processNewScheduleData() {
        selectDay(DayItem(Date(), "", "", true), isInitial = true)
        generateWeeks()
    }

    private fun loadAllIds() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = getApplication<Application>().assets.open("allid.txt")
                val reader = InputStreamReader(inputStream)
                val type = object : TypeToken<List<ScheduleTarget>>() {}.type
                val targets: List<ScheduleTarget> = Gson().fromJson(reader, type)
                allTargets = targets
                loadPinnedStatus()
            } catch (e: Exception) { }
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank() || query.length < 2) {
            _searchResults.postValue(emptyList())
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            _searchInProgress.postValue(true)
            delay(300L)
            val (startsWith, contains) = allTargets.filter { it.name.contains(query, ignoreCase = true) }.partition { it.name.startsWith(query, ignoreCase = true) }
            _searchResults.postValue((startsWith + contains).take(50))
            _searchInProgress.postValue(false)
        }
    }

    private fun loadPinnedStatus() {
        val pinnedIds = sharedPreferences.getStringSet("pinned_ids", emptySet()) ?: emptySet()
        allTargets.forEach { it.isPinned = it.person_id in pinnedIds }
        updatePinnedList()
    }

    fun togglePin(target: ScheduleTarget) {
        target.isPinned = !target.isPinned
        savePinnedStatus()
        updatePinnedList()
        _searchResults.value?.let { _searchResults.postValue(it.toList()) }
    }

    private fun savePinnedStatus() {
        val pinnedIds = allTargets.filter { it.isPinned }.map { it.person_id }.toSet()
        sharedPreferences.edit().putStringSet("pinned_ids", pinnedIds).apply()
    }

    private fun updatePinnedList() {
        _pinnedTargets.postValue(allTargets.filter { it.isPinned })
    }

    fun selectWeek(week: WeekItem) {
        _weeks.value?.let { currentWeeks ->
            val updatedWeeks = currentWeeks.map { it.copy(isSelected = it == week) }
            _weeks.postValue(updatedWeeks)
        }
        val mondayOfWeek = DayItem(week.startDate, "", "", true)
        selectDay(mondayOfWeek)
    }

    fun selectDay(day: DayItem, isInitial: Boolean = false) {
        selectedDate = day.date
        val calendar = Calendar.getInstance().apply { time = day.date }
        val dayOfWeekInCalendar = calendar.get(Calendar.DAY_OF_WEEK)
        val firstDayOfWeek = if (dayOfWeekInCalendar == Calendar.SUNDAY) Calendar.MONDAY - 7 else Calendar.MONDAY
        calendar.set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        val newDays = (0..6).map {
            val date = calendar.time
            DayItem(date, SimpleDateFormat("EE", Locale("ru")).format(date).capitalize(Locale.ROOT), SimpleDateFormat("d", Locale("ru")).format(date), isSameDay(date, selectedDate)).also { calendar.add(Calendar.DAY_OF_MONTH, 1) }
        }
        _days.postValue(newDays)
        filterScheduleForSelectedDate()
        if (!isInitial) _swipeDirection.postValue(SwipeDirection.NONE)
    }

    private fun isSameDay(date1: Date, date2: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = date1 }
        val cal2 = Calendar.getInstance().apply { time = date2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun filterScheduleForSelectedDate() {
        val lessonsForDay = fullSchedule.filter { isSameDay(Date(it.fullStartDate), selectedDate) }
        if (_showEmptyLessons.value == false) { _filteredSchedule.postValue(lessonsForDay); return }
        if (lessonsForDay.isEmpty()) { _filteredSchedule.postValue(emptyList()); return }
        val lessonsMap = lessonsForDay.associateBy { it.startTime }
        var pairCounter = 1
        val fullDaySchedule = timeSlots.map { (startTime, endTime) ->
            val pairName = "${pairCounter++} пара"
            lessonsMap[startTime] ?: createEmptyLesson(startTime, endTime, pairName)
        }
        _filteredSchedule.postValue(fullDaySchedule)
    }

    private fun createEmptyLesson(startTime: String, endTime: String, pairName: String): ScheduleItem {
        return ScheduleItem(UUID.randomUUID().toString(), 0L, "Нет пары", pairName, startTime, endTime, "", "", "", "", null, null, null, "")
    }

    fun selectNextDay() {
        _swipeDirection.value = SwipeDirection.LEFT
        val calendar = Calendar.getInstance().apply { time = selectedDate }
        if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
            val currentWeekIndex = _weeks.value?.indexOfFirst { it.isSelected } ?: -1
            if (currentWeekIndex != -1 && currentWeekIndex + 1 < (_weeks.value?.size ?: 0)) {
                selectWeek(_weeks.value!![currentWeekIndex + 1]); return
            }
        }
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        selectDay(DayItem(calendar.time, "", "", false))
    }

    fun selectPreviousDay() {
        _swipeDirection.value = SwipeDirection.RIGHT
        val calendar = Calendar.getInstance().apply { time = selectedDate }
        if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY) {
            val currentWeekIndex = _weeks.value?.indexOfFirst { it.isSelected } ?: -1
            if (currentWeekIndex > 0) {
                selectWeek(_weeks.value!![currentWeekIndex - 1])
                val prevWeek = _weeks.value!![currentWeekIndex - 1]
                val sundayCalendar = Calendar.getInstance().apply { time = prevWeek.endDate }
                selectDay(DayItem(sundayCalendar.time, "", "", false))
                return
            }
        }
        calendar.add(Calendar.DAY_OF_MONTH, -1)
        selectDay(DayItem(calendar.time, "", "", false))
    }
}