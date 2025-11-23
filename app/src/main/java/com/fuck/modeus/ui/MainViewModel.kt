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

enum class NavigationMode { TOUCH, SWIPE }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "FuckModeus_DEBUG"

    private val timeSlots = mapOf(
        "08:00" to "09:35", "09:50" to "11:25", "11:55" to "13:30",
        "13:45" to "15:20", "15:50" to "17:25", "17:35" to "19:10",
        "19:15" to "20:50", "20:55" to "21:40"
    )

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

    private val cacheFileName = "schedule_cache_v2.json"
    private val sharedPreferences = application.getSharedPreferences("schedule_prefs", Application.MODE_PRIVATE)

    init {
        Log.d(TAG, "ViewModel: init")
        calculateLegacySemesterStart()
        loadAllIds()
        loadShowEmptyLessonsMode()
        loadNavigationMode()
    }

    private fun calculateLegacySemesterStart() {
        val calendar = Calendar.getInstance()
        val today = calendar.time

        calendar.time = today
        val currentMonth = calendar.get(Calendar.MONTH)
        val currentYear = calendar.get(Calendar.YEAR)

        val firstSemesterStartCalendar = Calendar.getInstance().apply {
            set(Calendar.MONTH, Calendar.SEPTEMBER)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (currentMonth < Calendar.SEPTEMBER) {
                set(Calendar.YEAR, currentYear - 1)
            } else {
                set(Calendar.YEAR, currentYear)
            }
        }

        val secondSemesterStartCalendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, firstSemesterStartCalendar.get(Calendar.YEAR))
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (today.after(firstSemesterStartCalendar.time)) {
                set(Calendar.YEAR, firstSemesterStartCalendar.get(Calendar.YEAR) + 1)
            }
            set(Calendar.MONTH, Calendar.FEBRUARY)
            set(Calendar.DAY_OF_MONTH, 8)
        }

        semesterStartDate = if (today.after(secondSemesterStartCalendar.time)) {
            secondSemesterStartCalendar.time
        } else {
            firstSemesterStartCalendar.time
        }
    }

    private fun generateWeeks(items: List<ScheduleItem>) {
        val weekList = mutableListOf<WeekItem>()

        // Если пар нет - показываем только 1 пустую неделю
        if (items.isEmpty()) {
            val start = semesterStartDate
            val cal = Calendar.getInstance().apply { time = start }
            // Находим понедельник
            while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
                cal.add(Calendar.DAY_OF_MONTH, -1)
            }
            val monday = cal.time
            cal.add(Calendar.DAY_OF_MONTH, 6)
            val sunday = cal.time
            val dateFormat = SimpleDateFormat("dd", Locale("ru"))
            weekList.add(WeekItem(1, monday, sunday, "1 неделя (${dateFormat.format(monday)}-${dateFormat.format(sunday)})", true))
            _weeks.postValue(weekList)
            return
        }

        // 1. Находим точное время последней пары
        val maxTime = items.maxOf { it.fullStartDate }

        // 2. Определяем конец последней недели
        val limitCal = Calendar.getInstance().apply { timeInMillis = maxTime }
        // Докручиваем до конца воскресенья этой недели
        while (limitCal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
            limitCal.add(Calendar.DAY_OF_MONTH, 1)
        }
        // Ставим конец дня
        limitCal.set(Calendar.HOUR_OF_DAY, 23)
        limitCal.set(Calendar.MINUTE, 59)
        limitCal.set(Calendar.SECOND, 59)
        val hardLimit = limitCal.time

        // 3. Настраиваем итератор на начало семестра
        val weekCalendar = Calendar.getInstance().apply {
            time = semesterStartDate
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        var weekNumber = 1
        val dateFormat = SimpleDateFormat("dd", Locale("ru"))
        val today = Calendar.getInstance().time

        // 4. Генерируем недели, пока не упремся в hardLimit
        while (weekCalendar.time.before(hardLimit) || weekCalendar.time.time == hardLimit.time) {
            // Ищем понедельник
            weekCalendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            val startDate = weekCalendar.time

            // Ищем воскресенье
            weekCalendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            if (startDate.after(weekCalendar.time)) {
                weekCalendar.add(Calendar.DAY_OF_MONTH, 7)
            }
            val endDate = weekCalendar.time

            val isSelected = (today.time >= startDate.time && today.time <= endDate.time)

            weekList.add(
                WeekItem(
                    weekNumber = weekNumber,
                    startDate = startDate,
                    endDate = endDate,
                    displayableString = "$weekNumber неделя (${dateFormat.format(startDate)}-${dateFormat.format(endDate)})",
                    isSelected = isSelected
                )
            )

            // Если мы достигли недели, содержащей hardLimit (последнюю пару), прерываем
            if (endDate.time >= hardLimit.time) {
                break
            }

            weekNumber++
            // Переход к следующему понедельнику
            weekCalendar.add(Calendar.DAY_OF_MONTH, 1)
        }

        if (weekList.isNotEmpty() && weekList.none { it.isSelected }) {
            if (today.after(weekList.last().endDate)) {
                weekList.last().isSelected = true
            } else {
                weekList.first().isSelected = true
            }
        }

        _weeks.postValue(weekList)
    }

    fun loadSchedule(personId: String) {
        _isRefreshing.postValue(true)

        val apiSource = ApiSettings.getApiSource(getApplication())
        if (apiSource == ApiSource.SFEDU) {
            val token = TokenManager.getToken(getApplication())
            if (token == null) {
                _isRefreshing.postValue(false)
                _error.postValue("Требуется вход через Microsoft")
                openLoginActivity(personId)
                return
            }
        }

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
                    ids.add(personId)
                    add("attendeePersonId", ids)
                }

                val api = RetrofitInstance.getApi(getApplication())
                val responseBody = if (apiSource == ApiSource.SFEDU) {
                    api.getScheduleSfedu(requestBody)
                } else {
                    api.getScheduleRdCenter(requestBody)
                }
                val responseString = responseBody.string()

                if (responseString.isNotBlank()) {
                    val scheduleResponse = Gson().fromJson(responseString, ScheduleResponse::class.java)
                    val scheduleItems = parseResponseV2(scheduleResponse)
                    val sortedItems = scheduleItems.sortedBy { it.fullStartDate }

                    val targetName = allTargets.find { it.person_id == personId }?.name ?: "Расписание"
                    val currentTime = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())

                    val dataToCache = CachedData(personId, targetName, currentTime, responseString)
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
                    openLoginActivity(personId)
                } else {
                    _error.postValue("Ошибка обновления: ${e.message}")
                    loadDataFromFile()
                }
            } finally {
                _isRefreshing.postValue(false)
            }
        }
    }

    private fun processRawSchedule(rawItems: List<ScheduleItem>) {
        fullRawSchedule = rawItems

        generateWeeks(rawItems)

        if (rawItems.isEmpty()) {
            _scheduleMap.postValue(emptyMap())
            return
        }

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
                        if (item != null) {
                            filledList.add(item)
                        } else {
                            filledList.add(createEmptyLesson(start, end, pairName))
                        }
                    }
                    map[dayMillis] = filledList
                }
            } else {
                map.putAll(grouped)
            }
            _scheduleMap.postValue(map)
        }
    }

    private fun setInitialPage() {
        val today = getStartOfDay(System.currentTimeMillis())

        val startCal = Calendar.getInstance().apply { time = semesterStartDate }
        toStartOfDay(startCal)
        startCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        if (startCal.time.after(semesterStartDate)) {
            startCal.add(Calendar.DAY_OF_MONTH, -7)
        }
        val pagerStart = startCal.timeInMillis

        val diff = today - pagerStart
        val days = (diff / (1000 * 60 * 60 * 24)).toInt()

        if (days >= 0) {
            _currentPagerPosition.postValue(days)
        } else {
            _currentPagerPosition.postValue(0)
        }
    }

    fun onPageChanged(position: Int) {
        _currentPagerPosition.value = position

        val startCal = Calendar.getInstance().apply { time = semesterStartDate }
        toStartOfDay(startCal)
        startCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        if (startCal.time.after(semesterStartDate)) {
            startCal.add(Calendar.DAY_OF_MONTH, -7)
        }

        startCal.add(Calendar.DAY_OF_YEAR, position)
        val currentDate = startCal.time

        updateSelectedWeek(currentDate)
    }

    private fun toStartOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    private fun getStartOfDay(time: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = time
        toStartOfDay(cal)
        return cal.timeInMillis
    }

    private fun updateSelectedWeek(currentDate: Date) {
        val currentWeeks = _weeks.value ?: return
        val updated = currentWeeks.map { week ->
            val isSelected = currentDate.time >= week.startDate.time && currentDate.time <= week.endDate.time
            week.copy(isSelected = isSelected)
        }
        _weeks.postValue(updated)
    }

    private fun createEmptyLesson(startTime: String, endTime: String, pairName: String): ScheduleItem {
        return ScheduleItem(UUID.randomUUID().toString(), 0L, "Нет пары", pairName, startTime, endTime, "", "", "", "", null, null, null, "")
    }

    fun getLessonsForDate(dateMillis: Long): List<ScheduleItem> {
        val dayStart = getStartOfDay(dateMillis)
        return _scheduleMap.value?.get(dayStart) ?: emptyList()
    }

    fun refreshSchedule() {
        _isRefreshing.postValue(true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileInputStream = getApplication<Application>().openFileInput(cacheFileName)
                val jsonString = fileInputStream.reader().readText()
                fileInputStream.close()
                val cachedData = Gson().fromJson(jsonString, CachedData::class.java)
                loadSchedule(cachedData.targetId)
            } catch (e: Exception) {
                _isRefreshing.postValue(false)
                _error.postValue("Сначала выберите расписание")
            }
        }
    }

    fun loadInitialSchedule() { loadDataFromFile() }

    private fun openLoginActivity(personId: String?) {
        val intent = Intent(getApplication(), LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if(personId!=null) intent.putExtra("TARGET_ID", personId)
        getApplication<Application>().startActivity(intent)
    }

    private fun saveDataToFile(data: CachedData) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonString = Gson().toJson(data)
                val fileOutputStream = getApplication<Application>().openFileOutput(cacheFileName, Context.MODE_PRIVATE)
                fileOutputStream.write(jsonString.toByteArray())
                fileOutputStream.close()
            } catch (e: Exception) {}
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
                val items = parseResponseV2(scheduleResponse).sortedBy { it.fullStartDate }

                _scheduleTitle.postValue(cachedData.targetName)
                _lastUpdateTime.postValue("Последнее обновление: ${cachedData.lastUpdateTime}")

                processRawSchedule(items)
                setInitialPage()
            } catch (e: Exception) { _error.postValue("Кеш пуст") }
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

    fun setShowEmptyLessons(shouldShow: Boolean) {
        if (_showEmptyLessons.value == shouldShow) return
        _showEmptyLessons.value = shouldShow
        sharedPreferences.edit().putBoolean("show_empty_lessons", shouldShow).apply()
        processRawSchedule(fullRawSchedule)
    }

    fun setNavigationMode(isTouchMode: Boolean) {
        val newMode = if (isTouchMode) NavigationMode.TOUCH else NavigationMode.SWIPE
        _navigationMode.postValue(newMode)
        val modeName = newMode.name
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
}