package com.fuck.modeus.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.fuck.modeus.R
import com.fuck.modeus.data.ScheduleItem
import com.fuck.modeus.data.ScheduleTarget
import com.google.android.material.navigation.NavigationView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.MotionEvent
import kotlin.math.abs
import android.view.GestureDetector
import android.widget.LinearLayout
import android.content.Intent
import android.net.Uri

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Адаптеры
    private lateinit var scheduleAdapter: ScheduleAdapter
    private lateinit var pinnedAdapter: SearchAdapter
    private lateinit var searchResultsAdapter: SearchAdapter
    private lateinit var weeksAdapter: WeeksAdapter
    private lateinit var daysAdapter: DaysAdapter

    // View элементы - объявлены здесь
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView

    private val animationDuration = 200L
    private var selectedTarget: ScheduleTarget? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- ФИНАЛЬНЫЙ КОД ДЛЯ ПОЛНОЭКРАННОГО РЕЖИМА ---

        // Шаг 1: Принудительно включаем ночную тему
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        // Шаг 2: Устанавливаем layout
        setContentView(R.layout.activity_main)

        // Шаг 3: Используем WindowInsetsController для современных API (Android 11+)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Шаг 4: Явно устанавливаем флаги для старых API и делаем бары прозрачными
        // Это может быть избыточно, но часто решает проблемы на кастомных оболочках
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // --- КОНЕЦ КОДА ДЛЯ ПОЛНОЭКРАННОГО РЕЖИМА ---

        // --- Дальше идет ваша обычная логика инициализации ---
        drawerLayout = findViewById(R.id.drawerLayout)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        recyclerView = findViewById(R.id.recyclerView)

        setupMainContent()
        setupDrawer()
        observeViewModel()

        if (savedInstanceState == null) {
            viewModel.loadInitialSchedule()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMainContent() {
        // 1. Настройка списка расписания
        scheduleAdapter = ScheduleAdapter()
        recyclerView.apply {
            adapter = scheduleAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)

            // ВЕШАЕМ СЛУШАТЕЛЬ СВАЙПОВ

        }
        scheduleAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (positionStart == 0 && itemCount > 0) {
                    recyclerView.post { recyclerView.scrollToPosition(0) }
                }
            }
        })
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                // canScrollVertically(-1) возвращает true, если можно скроллить вверх.
                // Если скроллить вверх нельзя - значит, мы в самом верху.
                swipeRefreshLayout.isEnabled = !recyclerView.canScrollVertically(-1)
            }
        })

        // 2. Настройка списка недель
        weeksAdapter = WeeksAdapter { week -> viewModel.selectWeek(week) }
        val rvWeeks = findViewById<RecyclerView>(R.id.rvWeeks)
        rvWeeks.apply {
            adapter = weeksAdapter
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
        }
        weeksAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                val currentWeekIndex = viewModel.weeks.value?.indexOfFirst { it.isSelected } ?: -1
                if (currentWeekIndex != -1) {
                    rvWeeks.post { (rvWeeks.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(currentWeekIndex, 0) }
                }
            }
        })

        // 3. Настройка списка дней
        daysAdapter = DaysAdapter { day -> viewModel.selectDay(day) }
        findViewById<RecyclerView>(R.id.rvDays).apply {
            adapter = daysAdapter
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
        }

        // 4. Настройка "потяни для обновления"
        swipeRefreshLayout.setOnRefreshListener {
            viewModel.refreshSchedule()
        }

        // 5. Настройка кнопки открытия меню
        findViewById<ImageButton>(R.id.btnOpenMenu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }
    }
    private fun setupDrawer() {
        // ... (этот метод остается без изменений)
        val navigationView = findViewById<NavigationView>(R.id.navigationView)
        val switchShowEmpty = navigationView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchShowEmptyLessons)
        val containerShowEmpty = navigationView.findViewById<LinearLayout>(R.id.containerShowEmpty)
        containerShowEmpty.setOnClickListener {
            // При клике инвертируем состояние свитча и вызываем ViewModel
            val newState = !switchShowEmpty.isChecked
            switchShowEmpty.isChecked = newState
            viewModel.setShowEmptyLessons(newState)
        }

        viewModel.showEmptyLessons.observe(this) { shouldShow ->
            if (switchShowEmpty.isChecked != shouldShow) {
                switchShowEmpty.isChecked = shouldShow
            }
        }
        val switchNavMode = navigationView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchNavigationMode)
        val containerNavMode = navigationView.findViewById<LinearLayout>(R.id.containerNavMode)
        containerNavMode.setOnClickListener {
            val newState = !switchNavMode.isChecked
            switchNavMode.isChecked = newState
            viewModel.setNavigationMode(newState)
        }

        viewModel.navigationMode.observe(this) { mode ->
            if (switchNavMode.isChecked != (mode == NavigationMode.TOUCH)) {
                switchNavMode.isChecked = (mode == NavigationMode.TOUCH)
            }
        }
        val etSearch = navigationView.findViewById<EditText>(R.id.etSearch)

        viewModel.showEmptyLessons.observe(this) { shouldShow ->
            if (switchShowEmpty.isChecked != shouldShow) {
                switchShowEmpty.isChecked = shouldShow
            }
        }
        switchShowEmpty.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowEmptyLessons(isChecked)
        }

        switchNavMode.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setNavigationMode(isChecked)
        }
        viewModel.navigationMode.observe(this) { mode ->
            // Устанавливаем состояние свитча без вызова setOnCheckedChangeListener
            switchNavMode.isChecked = (mode == NavigationMode.TOUCH)
        }

        pinnedAdapter = SearchAdapter(
            onItemClick = { selectTargetAndFind(it) },
            onPinClick = { viewModel.togglePin(it) }
        )
        navigationView.findViewById<RecyclerView>(R.id.rvPinned).apply {
            adapter = pinnedAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        searchResultsAdapter = SearchAdapter(
            onItemClick = { target ->
                selectedTarget = target
                etSearch.setText(target.name)
                searchResultsAdapter.submitList(emptyList())
            },
            onPinClick = { viewModel.togglePin(it) }
        )
        navigationView.findViewById<RecyclerView>(R.id.rvSearchResults).apply {
            adapter = searchResultsAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (etSearch.hasFocus()) {
                    viewModel.search(s.toString())
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        navigationView.findViewById<ImageButton>(R.id.btnFind).setOnClickListener {
            selectedTarget?.let {
                selectTargetAndFind(it)
            } ?: Toast.makeText(this, "Сначала выберите элемент из списка", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectTargetAndFind(target: ScheduleTarget) {
        swipeRefreshLayout.isRefreshing = true
        viewModel.loadSchedule(target.person_id)
        // Строки с SharedPreferences больше не нужны
        // ...
        drawerLayout.closeDrawer(GravityCompat.END)
    }

    private fun observeViewModel() {
        // Здесь мы можем найти View один раз и использовать их
        val tvScheduleTitle = findViewById<TextView>(R.id.tvScheduleTitle)
        val tvNoLessons = findViewById<TextView>(R.id.tvNoLessons)
        val tvLastUpdate = findViewById<TextView>(R.id.tvLastUpdate)
        val navigationView = findViewById<NavigationView>(R.id.navigationView)
        val pbSearch = navigationView.findViewById<ProgressBar>(R.id.pbSearch)

        viewModel.filteredSchedule.observe(this) { scheduleItems ->
            swipeRefreshLayout.isRefreshing = false
            val tvNoLessons = findViewById<TextView>(R.id.tvNoLessons)

            val direction = viewModel.swipeDirection.value ?: SwipeDirection.NONE

            // Если анимация не нужна (клик по дню, первая загрузка, обновление)
            if (direction == SwipeDirection.NONE) {
                updateScheduleData(scheduleItems, tvNoLessons)
                return@observe
            }

            // Если был свайп, запускаем анимацию
            val screenWidth = resources.displayMetrics.widthPixels.toFloat()
            val slideOutX = if (direction == SwipeDirection.LEFT) -screenWidth else screenWidth
            val slideInX = -slideOutX

            recyclerView.animate()
                .translationX(slideOutX)
                .alpha(0f)
                .setDuration(animationDuration)
                .withEndAction {
                    updateScheduleData(scheduleItems, tvNoLessons)
                    recyclerView.translationX = slideInX // Мгновенный перенос за экран
                    recyclerView.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(animationDuration)
                        .start()
                }
                .start()
        }

        viewModel.weeks.observe(this) { weeks ->
            weeksAdapter.submitList(weeks)
        }

        viewModel.days.observe(this) { days ->
            daysAdapter.submitList(days)
        }

        viewModel.error.observe(this) { errorMessage ->
            swipeRefreshLayout.isRefreshing = false // Используем свойство класса
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
        }

        viewModel.lastUpdateTime.observe(this) { updateTime ->
            tvLastUpdate.text = updateTime
        }

        viewModel.scheduleTitle.observe(this) { title ->
            tvScheduleTitle.text = title
        }

        viewModel.searchResults.observe(this) { results ->
            searchResultsAdapter.submitList(results)
        }

        viewModel.pinnedTargets.observe(this) { pinnedItems ->
            pinnedAdapter.submitList(pinnedItems)
        }

        viewModel.searchInProgress.observe(this) { isInProgress ->
            pbSearch.visibility = if (isInProgress) View.VISIBLE else View.GONE
        }

        viewModel.navigationMode.observe(this) { mode ->
            setupNavigationListeners(mode)
        }
    }

    private fun updateScheduleData(scheduleItems: List<ScheduleItem>, tvNoLessons: TextView) {
        scheduleAdapter.submitList(scheduleItems) {
            // Эта прокрутка сработает после того, как адаптер закончит свои вычисления
            if (scheduleItems.isNotEmpty()) {
                recyclerView.scrollToPosition(0)
            }
        }
        tvNoLessons.visibility = if (scheduleItems.isEmpty()) View.VISIBLE else View.GONE
    }
    private fun showLessonDetailsDialog(item: ScheduleItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_lesson_details, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Закрыть", null)
            .create()

        val tvSubject = dialogView.findViewById<TextView>(R.id.tvDetailSubject)
        val tvModuleFull = dialogView.findViewById<TextView>(R.id.tvDetailModuleFull)
        val tvTeacher = dialogView.findViewById<TextView>(R.id.tvDetailTeacher)
        val tvRoom = dialogView.findViewById<TextView>(R.id.tvDetailRoom)
        val tvGroup = dialogView.findViewById<TextView>(R.id.tvDetailGroup)

        tvSubject.text = item.subject
        tvModuleFull.text = "📚 Модуль: ${item.moduleFullName ?: "не указан"}"

        // --- УПРАВЛЯЕМ КЛИКАБЕЛЬНОСТЬЮ И ЦВЕТОМ ---

        // Преподаватель
        tvTeacher.text = "🧑‍🏫 Преподаватель: ${item.teacher}"
        if (item.teacher != "не назначен") {
            tvTeacher.setTextColor(getColor(R.color.link_blue)) // Делаем синим

            // Обычный клик - поиск внутри приложения
            tvTeacher.setOnClickListener {
                searchFor(item.teacher)
                dialog.dismiss()
            }

            // [FIX 1.4.1] Восстанавливаем поиск в браузере
            tvTeacher.setOnLongClickListener {
                try {
                    val query = "${item.teacher} ЮФУ"
                    val url = "https://www.google.com/search?q=${android.net.Uri.encode(query)}"
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                    intent.data = android.net.Uri.parse(url)
                    startActivity(intent)
                    dialog.dismiss()
                } catch (e: Exception) {
                    // На случай, если нет браузера (маловероятно, но безопасно)
                    Toast.makeText(this, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
                }
                true // Важно вернуть true
            }
        }

        // Аудитория
        tvRoom.text = "🚪 Аудитория: ${item.room} (${item.locationType})"
        if (!item.room.startsWith("не назначена")) {
            tvRoom.setTextColor(getColor(R.color.link_blue)) // Делаем синим
            tvRoom.setOnClickListener {
                searchFor(item.room)
                dialog.dismiss()
            }
        }

        // Группа - НЕ кликабельна
        tvGroup.text = "👥 Группа: ${item.groupCode ?: "не указана"} (участников: ${item.teamSize ?: "?"})"

        dialog.show()
    }
    private fun searchFor(name: String) {
        // Проверяем, что это не "пустые" значения
        if (name == "не назначен" || name.startsWith("не назначена")) return

        drawerLayout.openDrawer(GravityCompat.END) // Открываем боковое меню
        val etSearch = findViewById<NavigationView>(R.id.navigationView).findViewById<EditText>(R.id.etSearch)

        // Устанавливаем текст в поле
        etSearch.setText(name)
        // Перемещаем курсор в конец текста
        etSearch.setSelection(name.length)
        // Явно запускаем поиск в ViewModel
        viewModel.search(name)
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun setupNavigationListeners(mode: NavigationMode) {
        // Универсальный GestureDetector, который умеет делать ВСЁ
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

            // --- Долгое нажатие (работает всегда) ---
            override fun onLongPress(e: MotionEvent) {
                val childView = recyclerView.findChildViewUnder(e.x, e.y)
                if (childView != null) {
                    val position = recyclerView.getChildAdapterPosition(childView)
                    if (position != RecyclerView.NO_POSITION) {
                        val item = scheduleAdapter.currentList[position]
                        if (item.subject != "Нет пары") {
                            showLessonDetailsDialog(item)
                        }
                    }
                }
            }

            // --- Тап по краю (работает только в режиме TOUCH) ---
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (mode == NavigationMode.TOUCH) {
                    val screenWidth = resources.displayMetrics.widthPixels
                    if (e.x < screenWidth * 0.35) {
                        viewModel.selectPreviousDay()
                        return true
                    }
                    if (e.x > screenWidth * 0.65) {
                        viewModel.selectNextDay()
                        return true
                    }
                }
                return false
            }

            // --- Свайп (работает только в режиме SWIPE) ---
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (mode == NavigationMode.SWIPE && e1 != null) {
                    val diffX = e2.x - e1.x
                    val diffY = e2.y - e1.y
                    if (abs(diffX) > abs(diffY) * 1.5) {
                        if (abs(diffX) > 100 && abs(velocityX) > 100) {
                            if (diffX > 0) {
                                viewModel.selectPreviousDay()
                            } else {
                                viewModel.selectNextDay()
                            }
                            return true
                        }
                    }
                }
                return false
            }
        })

        // Применяем наш универсальный детектор к RecyclerView
        recyclerView.setOnTouchListener { _, event ->
            // Передаем событие в детектор, но не "поглощаем" его,
            // чтобы скролл и SwipeRefreshLayout продолжали работать.
            gestureDetector.onTouchEvent(event)
            false
        }
    }

}