package com.fuck.modeus.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.fuck.modeus.data.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "HistoryViewModel"

    private val _scheduleMap = MutableLiveData<Map<Long, List<ScheduleItem>>>()
    val scheduleMap: LiveData<Map<Long, List<ScheduleItem>>> = _scheduleMap

    private val _weeks = MutableLiveData<List<WeekItem>>()
    val weeks: LiveData<List<WeekItem>> = _weeks

    private val _currentPagerPosition = MutableLiveData<Int>()
    val currentPagerPosition: LiveData<Int> = _currentPagerPosition

    private val _historyTitle = MutableLiveData<String>()
    val historyTitle: LiveData<String> = _historyTitle

    // Копия настройки из MainViewModel (показывать пустые пары или нет)
    // В истории лучше всегда показывать, или читать из настроек.
    // Для простоты сделаем всегда true
    private val showEmptyLessons = true

    var semesterStartDate: Date = Date()

    private val timeSlots = mapOf(
        "08:00" to "09:35", "09:50" to "11:25", "11:55" to "13:30",
        "13:45" to "15:20", "15:50" to "17:25", "17:35" to "19:10",
        "19:15" to "20:50", "20:55" to "21:40"
    )

    fun loadHistoryFile(fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(getApplication<Application>().filesDir, fileName)
                if (!file.exists()) return@launch

                val jsonString = file.readText()
                val cachedData = Gson().fromJson(jsonString, CachedData::class.java)
                val scheduleResponse = Gson().fromJson(cachedData.scheduleJsonResponse, ScheduleResponse::class.java)

                val items = parseResponseV2(scheduleResponse).sortedBy { it.fullStartDate }

                if (items.isNotEmpty()) {
                    recalculateSemesterStart(items.first().fullStartDate)
                    generateWeeks(items)
                    processRawSchedule(items)
                    _currentPagerPosition.postValue(0)

                    // Красивое имя файла
                    val cleanName = fileName
                        .replace("history_", "")
                        .replace(".json", "")
                        .replace("_", " ")
                    _historyTitle.postValue(cleanName)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    // --- ПАРСИНГ И ЛОГИКА (Копия из MainViewModel) ---

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

    private fun recalculateSemesterStart(firstLessonMillis: Long) {
        val cal = Calendar.getInstance()
        cal.timeInMillis = firstLessonMillis
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)

        val newSemesterStart = Calendar.getInstance()
        if (month in Calendar.FEBRUARY..Calendar.AUGUST) {
            newSemesterStart.set(year, Calendar.FEBRUARY, 7, 0, 0, 0)
        } else {
            val startYear = if (month == Calendar.JANUARY) year - 1 else year
            newSemesterStart.set(startYear, Calendar.SEPTEMBER, 1, 0, 0, 0)
        }
        newSemesterStart.set(Calendar.MILLISECOND, 0)
        semesterStartDate = newSemesterStart.time
    }

    private fun generateWeeks(items: List<ScheduleItem>) {
        if (items.isEmpty()) return
        val weekList = mutableListOf<WeekItem>()
        val maxTime = items.maxOf { it.fullStartDate }

        val limitCal = Calendar.getInstance().apply { timeInMillis = maxTime }
        while (limitCal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) limitCal.add(Calendar.DAY_OF_MONTH, 1)
        limitCal.set(Calendar.HOUR_OF_DAY, 23); limitCal.set(Calendar.MINUTE, 59)
        val hardLimit = limitCal.time

        val weekCalendar = Calendar.getInstance().apply {
            time = semesterStartDate
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        var weekNumber = 1
        val dateFormat = SimpleDateFormat("dd", Locale("ru"))

        while (weekCalendar.time.before(hardLimit) || weekCalendar.time.time <= hardLimit.time) {
            weekCalendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            val startDate = weekCalendar.time
            weekCalendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            if (startDate.after(weekCalendar.time)) weekCalendar.add(Calendar.DAY_OF_MONTH, 7)
            val endDate = weekCalendar.time

            // В истории всегда выделяем 1-ю неделю по умолчанию
            var isSelected = false

            weekList.add(WeekItem(weekNumber, startDate, endDate, "$weekNumber неделя", isSelected))

            if (endDate.time >= hardLimit.time) break
            weekNumber++
            weekCalendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        _weeks.postValue(weekList)
    }

    private fun processRawSchedule(rawItems: List<ScheduleItem>) {
        if (rawItems.isEmpty()) {
            _scheduleMap.postValue(emptyMap())
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            val map = mutableMapOf<Long, List<ScheduleItem>>()
            val grouped = rawItems.groupBy { getStartOfDay(it.fullStartDate) }

            if (showEmptyLessons) {
                grouped.forEach { (dayMillis, items) ->
                    val filledList = mutableListOf<ScheduleItem>()
                    val itemsByTime = items.associateBy { it.startTime }
                    var pairCounter = 1
                    timeSlots.forEach { (start, end) ->
                        val pairName = "${pairCounter++} пара"
                        val item = itemsByTime[start]
                        if (item != null) filledList.add(item)
                        else filledList.add(createEmptyLesson(start, end, pairName))
                    }
                    map[dayMillis] = filledList
                }
            } else {
                map.putAll(grouped)
            }
            _scheduleMap.postValue(map)
        }
    }

    private fun createEmptyLesson(startTime: String, endTime: String, pairName: String): ScheduleItem {
        return ScheduleItem(UUID.randomUUID().toString(), 0L, "Нет пары", pairName, startTime, endTime, "", "", "", "", null, null, null, "", null, null)
    }

    private fun getStartOfDay(time: Long): Long {
        val cal = Calendar.getInstance(); cal.timeInMillis = time; toStartOfDay(cal); return cal.timeInMillis
    }

    private fun toStartOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    }

    fun onPageChanged(position: Int) {
        _currentPagerPosition.value = position

        val cal = Calendar.getInstance()
        cal.time = semesterStartDate
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, position)

        val currentDateInMillis = cal.timeInMillis

        val currentWeeks = _weeks.value ?: return

        // Проверяем, изменилась ли неделя
        val alreadySelectedWeek = currentWeeks.find { it.isSelected }
        if (alreadySelectedWeek != null &&
            currentDateInMillis >= alreadySelectedWeek.startDate.time &&
            currentDateInMillis <= alreadySelectedWeek.endDate.time) {
            return
        }

        val updated = currentWeeks.map { week ->
            val isSelected = currentDateInMillis >= (week.startDate.time - 3600000) &&
                    currentDateInMillis <= (week.endDate.time + 3600000)
            week.copy(isSelected = isSelected)
        }
        _weeks.postValue(updated)
    }

    private fun updateSelectedWeek(currentDate: Date) {
        val currentWeeks = _weeks.value ?: return
        val updated = currentWeeks.map { week ->
            // Проверяем, попадает ли текущая дата в диапазон этой недели
            val isSelected = currentDate.time >= week.startDate.time && currentDate.time <= week.endDate.time
            week.copy(isSelected = isSelected)
        }
        _weeks.postValue(updated)
    }
}