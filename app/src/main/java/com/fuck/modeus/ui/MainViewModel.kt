package com.fuck.modeus.ui

import android.app.Application
import android.util.Log
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.fuck.modeus.data.CachedData
import com.fuck.modeus.data.DayItem
import com.fuck.modeus.data.ScheduleItem
import com.fuck.modeus.data.ScheduleResponse
import com.fuck.modeus.data.ScheduleTarget
import com.fuck.modeus.data.WeekItem
import com.fuck.modeus.network.RetrofitInstance
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

enum class SwipeDirection {
    LEFT, RIGHT, NONE // NONE для начальной загрузки
}
enum class NavigationMode { TOUCH, SWIPE }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // --- LiveData для Расписания ---
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

    // --- LiveData для Поиска ---
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

    // --- LiveData для Дат ---
    private val _weeks = MutableLiveData<List<WeekItem>>()
    val weeks: LiveData<List<WeekItem>> = _weeks
    private val _days = MutableLiveData<List<DayItem>>()
    val days: LiveData<List<DayItem>> = _days
    private var selectedDate: Date = Calendar.getInstance().time
    // Новая LiveData для управления анимацией
    private val _swipeDirection = MutableLiveData(SwipeDirection.NONE)
    val swipeDirection: LiveData<SwipeDirection> = _swipeDirection
    private val _navigationMode = MutableLiveData(NavigationMode.TOUCH)
    val navigationMode: LiveData<NavigationMode> = _navigationMode
    private val _showEmptyLessons = MutableLiveData(true) // По умолчанию включено
    val showEmptyLessons: LiveData<Boolean> = _showEmptyLessons

    private val cacheFileName = "schedule_cache.json"
    private val sharedPreferences =
        application.getSharedPreferences("schedule_prefs", Application.MODE_PRIVATE)

    init {
        loadAllIds()
        loadNavigationMode()
        loadShowEmptyLessonsMode()
    }

    private fun loadShowEmptyLessonsMode() {
        val shouldShow = sharedPreferences.getBoolean("show_empty_lessons", true) // По умолчанию true
        _showEmptyLessons.postValue(shouldShow)
    }


    fun setShowEmptyLessons(shouldShow: Boolean) {
        // Проверяем, изменилось ли значение, чтобы избежать лишней работы
        if (_showEmptyLessons.value == shouldShow) return

        _showEmptyLessons.value = shouldShow
        sharedPreferences.edit().putBoolean("show_empty_lessons", shouldShow).apply()

        // Явно перезапускаем фильтрацию с уже обновленным значением
        filterScheduleForSelectedDate()
    }
    private fun loadNavigationMode() {
        // Загружаем сохраненное значение, по умолчанию - TOUCH
        val mode = sharedPreferences.getString("nav_mode", NavigationMode.TOUCH.name)
        _navigationMode.postValue(NavigationMode.valueOf(mode ?: NavigationMode.TOUCH.name))
    }
    fun setNavigationMode(isTouchMode: Boolean) {
        val newMode = if (isTouchMode) NavigationMode.TOUCH else NavigationMode.SWIPE
        _navigationMode.postValue(newMode)
        sharedPreferences.edit().putString("nav_mode", newMode.name).apply()
    }
    // --- Логика Расписания ---
    fun loadSchedule(personId: String) {
        viewModelScope.launch {
            try {
                val requestBody = JsonObject().apply {
                    addProperty("size", 10000)
                    addProperty("timeMin", "2024-08-31T21:00:00.000Z")
                    addProperty("timeMax", "2026-07-06T20:59:59.999Z")
                    add("attendeePersonId", Gson().toJsonTree(listOf(personId)))
                }

                // --- ИСПОЛЬЗУЕМ Retrofit для получения СТРОКИ, а не готового объекта ---
                val responseString = RetrofitInstance.api.getScheduleAsString(requestBody).string()

                // --- ПРОВЕРЯЕМ, НЕ ПУСТАЯ ЛИ СТРОКА ---
                if (responseString.isNotBlank()) {
                    // Парсим строку в наш объект ScheduleResponse
                    val scheduleResponse = Gson().fromJson(responseString, ScheduleResponse::class.java)

                    val scheduleItems = parseResponse(scheduleResponse)
                    fullSchedule = scheduleItems.sortedBy { it.fullStartDate }

                    val targetName = allTargets.find { it.person_id == personId }?.name ?: "Расписание"
                    val currentTime = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())

                    // Создаем объект для кеширования
                    val dataToCache = CachedData(
                        targetId = personId,
                        targetName = targetName,
                        lastUpdateTime = currentTime,
                        scheduleJsonResponse = responseString // Сохраняем исходную строку
                    )
                    saveDataToFile(dataToCache) // Сохраняем в файл

                    // Обновляем UI
                    _scheduleTitle.postValue(targetName)
                    _lastUpdateTime.postValue("Последнее обновление: $currentTime")
                    processNewScheduleData()
                } else {
                    _error.postValue("Ошибка: получен пустой ответ от сервера.")
                }
            } catch (e: Exception) {
                _error.postValue("Ошибка сети: ${e.message}")
                // В случае ошибки сети, пытаемся загрузить старые данные из файла
                loadDataFromFile()
            }
        }
    }

    fun loadInitialSchedule() {
        loadDataFromFile()
    }
    private fun saveDataToFile(cachedData: CachedData) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonString = Gson().toJson(cachedData)
                val fileOutputStream = getApplication<Application>().openFileOutput(cacheFileName, Context.MODE_PRIVATE)
                fileOutputStream.write(jsonString.toByteArray())
                fileOutputStream.close()
            } catch (e: Exception) {
                Log.e("ViewModel", "Ошибка сохранения кеша", e)
            }
        }
    }

    private fun loadDataFromFile() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileInputStream = getApplication<Application>().openFileInput(cacheFileName)
                val jsonString = fileInputStream.reader().readText()
                fileInputStream.close()

                val cachedData = Gson().fromJson(jsonString, CachedData::class.java)

                // Парсим JSON из кеша
                val scheduleResponse = Gson().fromJson(cachedData.scheduleJsonResponse, ScheduleResponse::class.java)
                val scheduleItems = parseResponse(scheduleResponse)
                fullSchedule = scheduleItems.sortedBy { it.fullStartDate }

                // Обновляем UI из кеша
                _scheduleTitle.postValue(cachedData.targetName)
                _lastUpdateTime.postValue("Последнее обновление: ${cachedData.lastUpdateTime}")
                processNewScheduleData()

            } catch (e: Exception) {
                Log.e("ViewModel", "Файл кеша не найден или поврежден", e)
                _scheduleTitle.postValue("Расписание не загружено")

                // ДОБАВЛЯЕМ ОПОВЕЩЕНИЕ ДЛЯ ПОЛЬЗОВАТЕЛЯ
                _error.postValue("Сохраненное расписание не найдено. Выберите объект для поиска в меню.")
            }
        }
    }
    private fun processNewScheduleData() {
        // Устанавливаем сегодняшний день как выбранный по умолчанию
        selectDay(DayItem(Date(), "", "", true), isInitial = true)
        // Генерируем недели (пока пустая функция)
        generateWeeks()
    }

    private fun parseResponse(response: ScheduleResponse): List<ScheduleItem> {
        val embedded = response.embedded
        val scheduleItems = mutableListOf<ScheduleItem>()

        // Создаем карты для быстрого доступа
        val personsMap = embedded.persons.associateBy { it.id }
        val roomsMap = embedded.rooms.associateBy { it.id }
        val courseUnitRealizationsMap = embedded.courseUnitRealizations.associateBy { it.id }
        val cycleRealizationsMap = embedded.cycleRealizations.associateBy { it.id }
        val eventTeamsMap = embedded.eventTeams.associateBy { it.eventId }
        val eventLocationsMap = embedded.eventLocations.associateBy { it.eventId }

        val eventToPersonIds = mutableMapOf<String, MutableList<String>>()
        embedded.eventAttendees.forEach { attendee ->
            val eventId = attendee._links.event?.href?.substring(1)
            val personId = attendee._links.person?.href?.substring(1)
            if (eventId != null && personId != null) {
                eventToPersonIds.getOrPut(eventId) { mutableListOf() }.add(personId)
            }
        }

        val eventToRoomId = mutableMapOf<String, String>()
        embedded.eventRooms.forEach { eventRoom ->
            val eventId = eventRoom._links.event?.href?.substring(1)
            val roomHref = eventRoom._links.room?.href
            if (eventId != null && roomHref != null) {
                eventToRoomId[eventId] = roomHref.split("/").last()
            }
        }

        // Этот парсер ожидает часовой пояс (XXX)
        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val timeFormat = SimpleDateFormat("HH:mm", Locale("ru"))
        val dateFormat = SimpleDateFormat("d MMMM", Locale("ru"))

        for (event in embedded.events) {
            val teacherNames = eventToPersonIds[event.id]
                ?.mapNotNull { personId -> personsMap[personId]?.fullName }
                ?.joinToString(separator = "\n") ?: "не назначен"

            val roomName = eventToRoomId[event.id]
                ?.let { roomId -> roomsMap[roomId]?.name } ?: "не назначена/онлайн"

            val courseUnitId = event.links.courseUnitRealization?.href?.substring(1)
            val moduleShortName = courseUnitId?.let { courseUnitRealizationsMap[it]?.nameShort }
            val moduleFullName = courseUnitId?.let { courseUnitRealizationsMap[it]?.name }

            val cycleId = event.links.cycleRealization?.href?.substring(1)
            val groupCode = cycleId?.let { cycleRealizationsMap[it]?.code }

            val teamSize = eventTeamsMap[event.id]?.size
            val locationType = if (eventLocationsMap[event.id]?.customLocation == "Online") "Online" else "Очно"

            // ИСПОЛЬЗУЕМ ПОЛЯ С ЧАСОВЫМ ПОЯСОМ: event.start и event.end
            val startDate = inputFormat.parse(event.start)
            val endDate = inputFormat.parse(event.end) // <-- ИСПРАВЛЕНИЕ ЗДЕСЬ

            val startTimeStr = startDate?.let { timeFormat.format(it) } ?: ""
            val endTimeStr = endDate?.let { timeFormat.format(it) } ?: "" // <-- И ИСПРАВЛЕНИЕ ЗДЕСЬ
            val dateStr = startDate?.let { dateFormat.format(it) } ?: ""

            val lessonType = when (event.type) {
                "LECT" -> "Лекция"
                "SEMI" -> "Практика"
                "LAB" -> "Лабораторная"
                "EXAM" -> "Экзамен"
                else -> event.type
            }

            scheduleItems.add(
                ScheduleItem(
                    id = event.id,
                    fullStartDate = startDate?.time ?: 0L,
                    subject = event.name,
                    moduleShortName = moduleShortName,
                    startTime = startTimeStr,
                    endTime = endTimeStr,
                    date = dateStr,
                    teacher = teacherNames,
                    room = roomName,
                    type = lessonType,
                    moduleFullName = moduleFullName,
                    groupCode = groupCode,
                    teamSize = teamSize,
                    locationType = locationType
                )
            )
        }
        return scheduleItems
    }

    private fun saveScheduleToPrefs(schedule: List<ScheduleItem>) {
        val json = Gson().toJson(schedule)
        sharedPreferences.edit().putString("schedule_data", json).apply()
    }

    private fun updateLastUpdateTime() {
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        val currentTime = sdf.format(Date())
        sharedPreferences.edit().putString("last_update_time", currentTime).apply()
        _lastUpdateTime.postValue("Последнее обновление: $currentTime")
    }

    // --- Логика Поиска и Закрепления ---

    private fun loadAllIds() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = getApplication<Application>().assets.open("allid.txt")
                val reader = InputStreamReader(inputStream)
                val type = object : TypeToken<List<ScheduleTarget>>() {}.type
                val targets: List<ScheduleTarget> = Gson().fromJson(reader, type)
                allTargets = targets
                loadPinnedStatus() // Загружаем статусы после загрузки всех ID
            } catch (e: Exception) {
                Log.e("ViewModel", "Ошибка чтения allid.txt", e)
                _error.postValue("Критическая ошибка: Не удалось прочитать allid.txt. ${e.message}")
            }
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank() || query.length < 2) { // Будем искать от 2-х символов
            _searchResults.postValue(emptyList())
            return
        }

        searchJob = viewModelScope.launch(Dispatchers.Default) {
            _searchInProgress.postValue(true) // Показываем ProgressBar
            delay(300L) // Ждем 300 мс

            val (startsWith, contains) = allTargets.filter {
                it.name.contains(query, ignoreCase = true)
            }.partition {
                it.name.startsWith(query, ignoreCase = true)
            }

            // Берем только первые 50 результатов
            _searchResults.postValue((startsWith + contains).take(50))
            _searchInProgress.postValue(false) // Скрываем ProgressBar
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

        // Обновляем результаты поиска, чтобы иконка там тоже изменилась
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
        // Выделяем выбранную неделю
        _weeks.value?.let { currentWeeks ->
            val updatedWeeks = currentWeeks.map { it.copy(isSelected = it == week) }
            _weeks.postValue(updatedWeeks)
        }

        // Выбираем понедельник этой недели
        val mondayOfWeek = DayItem(week.startDate, "", "", true)
        selectDay(mondayOfWeek)
    }
    fun selectDay(day: DayItem, isInitial: Boolean = false) {
        selectedDate = day.date
        val calendar = Calendar.getInstance().apply { time = day.date }
        val dayOfWeekInCalendar = calendar.get(Calendar.DAY_OF_WEEK)
        // Корректируем, чтобы неделя начиналась с понедельника
        val firstDayOfWeek = if (dayOfWeekInCalendar == Calendar.SUNDAY) Calendar.MONDAY - 7 else Calendar.MONDAY
        calendar.set(Calendar.DAY_OF_WEEK, firstDayOfWeek)

        val newDays = (0..6).map {
            val date = calendar.time
            DayItem(
                date = date,
                dayOfWeek = SimpleDateFormat("EE", Locale("ru")).format(date).capitalize(Locale.ROOT),
                dayOfMonth = SimpleDateFormat("d", Locale("ru")).format(date),
                isSelected = isSameDay(date, selectedDate)
            ).also {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        _days.postValue(newDays)
        // --- ФИНАЛЬНЫЙ БЛОК ДЛЯ ОБНОВЛЕНИЯ НЕДЕЛЬ ---
        filterScheduleForSelectedDate()

        if (!isInitial) {
            _swipeDirection.postValue(SwipeDirection.NONE)
        }
    }
    private fun isSameDay(date1: Date, date2: Date): Boolean {
        // ... (этот метод без изменений)
        val cal1 = Calendar.getInstance().apply { time = date1 }
        val cal2 = Calendar.getInstance().apply { time = date2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
    private fun filterScheduleForSelectedDate() {
        val lessonsForDay = fullSchedule.filter { isSameDay(Date(it.fullStartDate), selectedDate) }

        if (_showEmptyLessons.value == false) {
            _filteredSchedule.postValue(lessonsForDay)
            return
        }

        if (lessonsForDay.isEmpty()) {
            _filteredSchedule.postValue(emptyList())
            return
        }

        val lessonsMap = lessonsForDay.associateBy { it.startTime }
        var pairCounter = 1

        val fullDaySchedule = timeSlots.map { (startTime, endTime) ->
            val pairName = "${pairCounter++} пара"
            lessonsMap[startTime] ?: createEmptyLesson(startTime, endTime, pairName)
        }

        _filteredSchedule.postValue(fullDaySchedule)
    }
    private fun createEmptyLesson(startTime: String, endTime: String, pairName: String): ScheduleItem {
        return ScheduleItem(
            id = UUID.randomUUID().toString(),
            fullStartDate = 0L,
            subject = "Нет пары",
            moduleShortName = pairName, // "1 пара", "2 пара"...
            startTime = startTime,
            endTime = endTime, // <-- ТЕПЕРЬ ПЕРЕДАЕМ ВРЕМЯ ОКОНЧАНИЯ
            date = "",
            teacher = "",
            room = "",
            type = "",
            moduleFullName = null,
            groupCode = null,
            teamSize = null,
            locationType = ""
        )
    }
    private fun generateWeeks() {
        if (fullSchedule.isEmpty()) {
            _weeks.postValue(emptyList())
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            val calendar = Calendar.getInstance()
            val today = calendar.time

            // Определяем дату начала первого семестра (1 сентября текущего или прошлого года)
            calendar.time = today
            val currentMonth = calendar.get(Calendar.MONTH)
            val currentYear = calendar.get(Calendar.YEAR)

            val firstSemesterStartCalendar = Calendar.getInstance().apply {
                set(Calendar.MONTH, Calendar.SEPTEMBER)
                set(Calendar.DAY_OF_MONTH, 1)
                if (currentMonth < Calendar.SEPTEMBER) { // Если сейчас январь-август, то учебный год начался в прошлом году
                    set(Calendar.YEAR, currentYear - 1)
                } else {
                    set(Calendar.YEAR, currentYear)
                }
            }

            // Определяем дату начала второго семестра (8 февраля)
            val secondSemesterStartCalendar = Calendar.getInstance().apply {
                set(Calendar.YEAR, firstSemesterStartCalendar.get(Calendar.YEAR))
                if (today.after(firstSemesterStartCalendar.time)) {
                    // Если мы после 1 сентября, то второй семестр будет в следующем году
                    set(Calendar.YEAR, firstSemesterStartCalendar.get(Calendar.YEAR) + 1)
                }
                set(Calendar.MONTH, Calendar.FEBRUARY)
                set(Calendar.DAY_OF_MONTH, 8)
            }

            // Определяем, в каком мы семестре и дату его начала
            val semesterStartDate = if (today.after(secondSemesterStartCalendar.time)) {
                secondSemesterStartCalendar.time
            } else {
                firstSemesterStartCalendar.time
            }

            // Находим первую и последнюю пару в расписании, чтобы определить диапазон недель
            val firstLessonDate = Date(fullSchedule.minOf { it.fullStartDate })
            val lastLessonDate = Date(fullSchedule.maxOf { it.fullStartDate })

            val weekList = mutableListOf<WeekItem>()
            val weekCalendar = Calendar.getInstance().apply { time = semesterStartDate }
            var weekNumber = 1

            val dateFormat = SimpleDateFormat("dd", Locale("ru"))

            while (weekCalendar.time.before(lastLessonDate) || isSameDay(weekCalendar.time, lastLessonDate)) {
                // --- НАЧАЛО НОВОГО БЛОКА ---

                // Находим понедельник текущей недели
                weekCalendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                val startDate = weekCalendar.time

                // Находим воскресенье ЭТОЙ ЖЕ недели
                weekCalendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                // ВАЖНО: если понедельник был позже воскресенья (например, 30 декабря и 5 января),
                // значит, воскресенье относится к следующей неделе, нужно откатиться на 7 дней.
                if (startDate.after(weekCalendar.time)) {
                    weekCalendar.add(Calendar.DAY_OF_MONTH, -7)
                }
                val endDate = weekCalendar.time

                // Пропускаем недели, которые были до начала реального расписания
                if (endDate.before(firstLessonDate)) {
                    weekCalendar.add(Calendar.DAY_OF_MONTH, 1) // Переходим к следующему дню, чтобы начать новую неделю
                    continue
                }

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
                weekCalendar.add(Calendar.DAY_OF_MONTH, 1) // Переходим к следующему дню, чтобы начать новую неделю

                // --- КОНЕЦ НОВОГО БЛОКА ---
            }
            _weeks.postValue(weekList)
        }
    }
    fun selectNextDay() {
        _swipeDirection.value = SwipeDirection.LEFT
        val calendar = Calendar.getInstance().apply { time = selectedDate }

        // ПРОВЕРКА: Если текущий день - ВОСКРЕСЕНЬЕ
        if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
            // Находим индекс текущей выбранной недели
            val currentWeekIndex = _weeks.value?.indexOfFirst { it.isSelected } ?: -1
            if (currentWeekIndex != -1 && currentWeekIndex + 1 < (_weeks.value?.size ?: 0)) {
                // Выбираем СЛЕДУЮЩУЮ неделю
                selectWeek(_weeks.value!![currentWeekIndex + 1])
                return // Выходим, чтобы не вызывать selectDay дважды
            }
        }

        // Стандартная логика для всех остальных дней
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        selectDay(DayItem(calendar.time, "", "", false))
    }


    fun selectPreviousDay() {
        _swipeDirection.value = SwipeDirection.RIGHT
        val calendar = Calendar.getInstance().apply { time = selectedDate }

        // ПРОВЕРКА: Если текущий день - ПОНЕДЕЛЬНИК
        if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY) {
            val currentWeekIndex = _weeks.value?.indexOfFirst { it.isSelected } ?: -1
            if (currentWeekIndex > 0) {
                // Выбираем ПРЕДЫДУЩУЮ неделю
                selectWeek(_weeks.value!![currentWeekIndex - 1])
                // ВАЖНО: После выбора недели, нужно вручную выбрать последний день (воскресенье)
                val prevWeek = _weeks.value!![currentWeekIndex - 1]
                val sundayCalendar = Calendar.getInstance().apply { time = prevWeek.endDate }
                selectDay(DayItem(sundayCalendar.time, "", "", false))
                return // Выходим
            }
        }

        // Стандартная логика для всех остальных дней
        calendar.add(Calendar.DAY_OF_MONTH, -1)
        selectDay(DayItem(calendar.time, "", "", false))
    }
    fun refreshSchedule() {
        // Пытаемся найти ID в нашем кеше
        try {
            val fileInputStream = getApplication<Application>().openFileInput(cacheFileName)
            val jsonString = fileInputStream.reader().readText()
            fileInputStream.close()
            val cachedData = Gson().fromJson(jsonString, CachedData::class.java)
            loadSchedule(cachedData.targetId) // Запускаем обновление для сохраненного ID
        } catch (e: Exception) {
            // Если кеша нет, ничего не делаем
            Log.e("ViewModel", "Не могу обновить: кеш пуст")
        }
    }
}