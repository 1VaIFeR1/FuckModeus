package com.fuck.modeus.ui

import android.annotation.SuppressLint
import android.content.DialogInterface // ВАЖНО: Добавил для диалога
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView // ВАЖНО: Добавил
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.fuck.modeus.R
import com.fuck.modeus.data.ApiSettings
import com.fuck.modeus.data.ApiSource
import com.fuck.modeus.data.DayItem
import com.fuck.modeus.data.ScheduleItem
import com.fuck.modeus.data.ScheduleTarget
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var pinnedAdapter: SearchAdapter
    private lateinit var searchResultsAdapter: SearchAdapter
    private lateinit var weeksAdapter: WeeksAdapter
    private lateinit var daysAdapter: DaysAdapter
    private lateinit var pagerAdapter: DayPagerAdapter

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var swipeRefreshLayout: ScrollAwareSwipeRefreshLayout

    private var selectedTarget: ScheduleTarget? = null
    private var headerHeightPx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        setContentView(R.layout.activity_main)

        // --- 1. СКРЫВАЕМ СИСТЕМНЫЕ ПАНЕЛИ (ЖЕСТКО) ---
        hideSystemUI()

        drawerLayout = findViewById(R.id.drawerLayout)
        viewPager = findViewById(R.id.viewPager)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)

        // --- 2. ОТСТУПЫ ПОД КАМЕРУ (Safe Area) ---
        // Используем displayCutout, так как systemBars теперь скрыты (размер 0)
        val mainContentContainer = findViewById<View>(R.id.mainContentContainer)
        ViewCompat.setOnApplyWindowInsetsListener(mainContentContainer) { view, windowInsets ->
            // Берем insets от выреза (камеры) и системных панелей (на случай если они вылезут)
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())

            // Ставим padding, чтобы контент не налез на камеру
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)

            val density = resources.displayMetrics.density
            headerHeightPx = insets.top + (70 * density).toInt()

            WindowInsetsCompat.CONSUMED
        }

        setupMainContent()
        setupDrawer()
        observeViewModel()

        val restartId = intent.getStringExtra("RESTART_WITH_ID")
        if (restartId != null) {
            viewModel.loadSchedule(restartId)
        } else if (savedInstanceState == null) {
            viewModel.loadInitialSchedule()
        }
    }

    // Метод для скрытия интерфейса
    private fun hideSystemUI() {
        // Говорим системе, что мы сами управляем контентом
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // Скрываем и Статус-бар, и Навигацию
        controller.hide(WindowInsetsCompat.Type.systemBars())
        // При свайпе они появятся ненадолго и исчезнут
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Делаем фон прозрачным для надежности
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // Для старых API и специфичных прошивок (на всякий случай)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    // Если пользователь свернул приложение и вернулся — убеждаемся, что бар снова скрыт
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun setupMainContent() {
        pagerAdapter = DayPagerAdapter(this, viewModel)
        viewPager.adapter = pagerAdapter
        viewPager.getChildAt(0).overScrollMode = RecyclerView.OVER_SCROLL_NEVER

        swipeRefreshLayout.setOnRefreshListener {
            viewModel.refreshSchedule()
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                viewModel.onPageChanged(position)
                updateDaysList(position)
            }
        })

        viewModel.currentPagerPosition.observe(this) { pos ->
            if (viewPager.currentItem != pos) {
                viewPager.setCurrentItem(pos, false)
            }
        }

        weeksAdapter = WeeksAdapter { week ->
            val diffMillis = week.startDate.time - viewModel.semesterStartDate.time
            val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()
            viewPager.setCurrentItem(diffDays, true)
        }
        val rvWeeks = findViewById<RecyclerView>(R.id.rvWeeks)
        rvWeeks.apply {
            adapter = weeksAdapter
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
        }

        daysAdapter = DaysAdapter { dayItem ->
            val diffMillis = dayItem.date.time - viewModel.semesterStartDate.time
            val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()
            viewPager.setCurrentItem(diffDays, true)
        }
        findViewById<RecyclerView>(R.id.rvDays).apply {
            adapter = daysAdapter
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
        }

        findViewById<ImageButton>(R.id.btnOpenMenu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }

        findViewById<TextView>(R.id.tvScheduleTitle).setOnLongClickListener {
            viewModel.refreshSchedule()
            Toast.makeText(this, "Обновление...", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun updateDaysList(pagerPosition: Int) {
        val cal = Calendar.getInstance()
        cal.time = viewModel.semesterStartDate
        cal.add(Calendar.DAY_OF_YEAR, pagerPosition)
        val currentDate = cal.time

        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        if (cal.time.after(currentDate)) {
            cal.add(Calendar.DAY_OF_WEEK, -7)
        }

        val days = mutableListOf<DayItem>()
        val dateFormat = SimpleDateFormat("d", Locale("ru"))
        val dayNameFormat = SimpleDateFormat("EE", Locale("ru"))

        for (i in 0..6) {
            val date = cal.time
            val isSame = isSameDay(date, currentDate)
            days.add(DayItem(
                date = date,
                dayOfWeek = dayNameFormat.format(date).capitalize(Locale.ROOT),
                dayOfMonth = dateFormat.format(date),
                isSelected = isSame
            ))
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        daysAdapter.submitList(days)
    }

    private fun isSameDay(d1: Date, d2: Date): Boolean {
        val c1 = Calendar.getInstance().apply { time = d1 }
        val c2 = Calendar.getInstance().apply { time = d2 }
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
                c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }

    private fun setupDrawer() {
        val navigationView = findViewById<NavigationView>(R.id.navigationView)

        val layoutMain = navigationView.findViewById<View>(R.id.layout_main_menu)
        val layoutSettings = navigationView.findViewById<View>(R.id.layout_settings_menu)
        val btnGoToSettings = navigationView.findViewById<View>(R.id.btnGoToSettings)
        val btnBackToMenu = navigationView.findViewById<View>(R.id.btnBackToMenu)

        btnGoToSettings?.setOnClickListener {
            layoutMain.visibility = View.GONE
            layoutSettings.visibility = View.VISIBLE
        }

        btnBackToMenu?.setOnClickListener {
            layoutSettings.visibility = View.GONE
            layoutMain.visibility = View.VISIBLE
        }

        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: View) {
                if (layoutSettings.visibility == View.VISIBLE) {
                    layoutSettings.visibility = View.GONE
                    layoutMain.visibility = View.VISIBLE
                }
            }
        })

        val rbSfedu = navigationView.findViewById<RadioButton>(R.id.rbSfedu)
        val rbRdCenter = navigationView.findViewById<RadioButton>(R.id.rbRdCenter)
        val rgSource = navigationView.findViewById<RadioGroup>(R.id.radioGroupSource)
        val switchEmpty = navigationView.findViewById<SwitchMaterial>(R.id.switchShowEmpty)
        val btnLogout = navigationView.findViewById<View>(R.id.btnLogoutInternal)

        // --- ДОБАВЛЕНО: Кнопка редактирования URL ---
        val btnEditUrl = navigationView.findViewById<ImageView>(R.id.btnEditUrl)

        val currentSource = ApiSettings.getApiSource(this)
        if (currentSource == ApiSource.SFEDU) {
            rbSfedu.isChecked = true
            btnLogout.visibility = View.VISIBLE
        } else {
            rbRdCenter.isChecked = true
            btnLogout.visibility = View.GONE
        }

        // --- ДОБАВЛЕНО: Слушатель нажатия на карандаш ---
        btnEditUrl.setOnClickListener {
            showUrlEditDialog()
        }

        rgSource.setOnCheckedChangeListener { _, checkedId ->
            val newSource = if (checkedId == R.id.rbSfedu) ApiSource.SFEDU else ApiSource.RDCENTER
            ApiSettings.setApiSource(this, newSource)
            btnLogout.visibility = if (newSource == ApiSource.SFEDU) View.VISIBLE else View.GONE
            Toast.makeText(this, "Источник изменен. Обновите расписание.", Toast.LENGTH_SHORT).show()
        }

        viewModel.showEmptyLessons.observe(this) {
            if (switchEmpty.isChecked != it) switchEmpty.isChecked = it
        }

        switchEmpty.setOnCheckedChangeListener { _, isChecked -> viewModel.setShowEmptyLessons(isChecked) }
        btnLogout.setOnClickListener { performLogout() }

        setupSearchLogic(navigationView)
    }

    private fun setupSearchLogic(navView: NavigationView) {
        pinnedAdapter = SearchAdapter(
            onItemClick = { selectTargetAndFind(it) },
            onPinClick = { viewModel.togglePin(it) }
        )
        navView.findViewById<RecyclerView>(R.id.rvPinned).apply {
            adapter = pinnedAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        searchResultsAdapter = SearchAdapter(
            onItemClick = { target ->
                selectedTarget = target
                val etSearch = navView.findViewById<EditText>(R.id.etSearch)
                etSearch.setText(target.name)
                searchResultsAdapter.submitList(emptyList())
            },
            onPinClick = { viewModel.togglePin(it) }
        )
        navView.findViewById<RecyclerView>(R.id.rvSearchResults).apply {
            adapter = searchResultsAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        val etSearch = navView.findViewById<EditText>(R.id.etSearch)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (etSearch.hasFocus()) viewModel.search(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        navView.findViewById<ImageButton>(R.id.btnFind).setOnClickListener {
            selectedTarget?.let { selectTargetAndFind(it) }
                ?: Toast.makeText(this, "Выберите объект из списка", Toast.LENGTH_SHORT).show()
        }
    }

    fun performLogout() {
        com.fuck.modeus.data.TokenManager.clearToken(this)
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        android.webkit.WebStorage.getInstance().deleteAllData()
        viewModel.refreshSchedule()
        Toast.makeText(this, "Вы вышли из аккаунта", Toast.LENGTH_SHORT).show()
        drawerLayout.closeDrawer(GravityCompat.END)
    }

    private fun selectTargetAndFind(target: ScheduleTarget) {
        viewModel.loadSchedule(target.person_id)
        drawerLayout.closeDrawer(GravityCompat.END)
    }

    fun showLessonDetailsDialog(item: ScheduleItem) {
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

        tvTeacher.text = "🧑‍🏫 Преподаватель: ${item.teacher}"
        if (item.teacher != "не назначен") {
            tvTeacher.setTextColor(getColor(R.color.link_blue))
            tvTeacher.setOnClickListener { searchFor(item.teacher); dialog.dismiss() }
            tvTeacher.setOnLongClickListener {
                try {
                    val url = "https://www.google.com/search?q=${android.net.Uri.encode("${item.teacher} ЮФУ")}"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    dialog.dismiss()
                } catch (e: Exception) {}
                true
            }
        }

        tvRoom.text = "🚪 Аудитория: ${item.room} (${item.locationType})"
        if (!item.room.startsWith("не назначена") && item.room != "Online") {
            tvRoom.setTextColor(getColor(R.color.link_blue))
            tvRoom.setOnClickListener { searchFor(item.room); dialog.dismiss() }
        }

        tvGroup.text = "👥 Группа: ${item.groupCode ?: "не указана"} (участников: ${item.teamSize ?: "?"})"
        dialog.show()
    }

    private fun searchFor(name: String) {
        drawerLayout.openDrawer(GravityCompat.END)
        val etSearch = findViewById<NavigationView>(R.id.navigationView).findViewById<EditText>(R.id.etSearch)
        etSearch.setText(name)
        etSearch.setSelection(name.length)
        viewModel.search(name)
    }

    private fun observeViewModel() {
        val tvScheduleTitle = findViewById<TextView>(R.id.tvScheduleTitle)
        val tvLastUpdate = findViewById<TextView>(R.id.tvLastUpdate)
        val pbSearch = findViewById<NavigationView>(R.id.navigationView).findViewById<ProgressBar>(R.id.pbSearch)

        viewModel.scheduleTitle.observe(this) { tvScheduleTitle.text = it }
        viewModel.lastUpdateTime.observe(this) { tvLastUpdate.text = it }

        viewModel.weeks.observe(this) { weeks ->
            weeksAdapter.submitList(weeks) {
                val currentWeekIndex = weeks.indexOfFirst { it.isSelected }
                if (currentWeekIndex != -1) {
                    val rvWeeks = findViewById<RecyclerView>(R.id.rvWeeks)
                    (rvWeeks.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(currentWeekIndex, 0)
                }
            }
        }

        viewModel.isRefreshing.observe(this) { isRefreshing ->
            swipeRefreshLayout.isRefreshing = isRefreshing
        }

        viewModel.error.observe(this) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        viewModel.searchResults.observe(this) { searchResultsAdapter.submitList(it) }
        viewModel.pinnedTargets.observe(this) { pinnedAdapter.submitList(it) }

        viewModel.searchInProgress.observe(this) {
            pbSearch.visibility = if (it) View.VISIBLE else View.GONE
        }

        viewModel.currentPagerPosition.observe(this) { pos ->
            if (viewPager.currentItem != pos) {
                viewPager.setCurrentItem(pos, false)
            }
        }
    }
    private fun showUrlEditDialog() {
        val context = this

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, 0)
        }

        val labelBase = TextView(context).apply { text = "Base URL (Сервер):" }
        val inputBase = EditText(context).apply {
            setText(ApiSettings.getRdBaseUrl(context))
            hint = "https://schedule.rdcenter.ru/"
        }

        val labelEndpoint = TextView(context).apply {
            text = "Endpoint (Путь):"
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val inputEndpoint = EditText(context).apply {
            setText(ApiSettings.getRdEndpoint(context))
            hint = "api/Schedule"
        }

        container.addView(labelBase)
        container.addView(inputBase)
        container.addView(labelEndpoint)
        container.addView(inputEndpoint)

        AlertDialog.Builder(context)
            .setTitle("Настройка API RDCenter")
            .setView(container)
            // ИСПРАВЛЕНО: Явно указаны типы переменных
            .setPositiveButton("Сохранить") { dialog: DialogInterface, which: Int ->
                val newBase = inputBase.text.toString().trim()
                val newEndpoint = inputEndpoint.text.toString().trim()

                if (newBase.isNotEmpty() && newEndpoint.isNotEmpty()) {
                    ApiSettings.setRdSettings(context, newBase, newEndpoint)
                    if (ApiSettings.getApiSource(context) == ApiSource.RDCENTER) {
                        viewModel.refreshSchedule()
                    }
                    Toast.makeText(context, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Поля не могут быть пустыми", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            // ИСПРАВЛЕНО: Явно указаны типы переменных
            .setNeutralButton("Сброс") { dialog: DialogInterface, which: Int ->
                ApiSettings.resetRdSettings(context)
                if (ApiSettings.getApiSource(context) == ApiSource.RDCENTER) {
                    viewModel.refreshSchedule()
                }
                Toast.makeText(context, "Настройки сброшены", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}