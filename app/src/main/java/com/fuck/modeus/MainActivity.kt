package com.fuck.modeus.ui

import android.annotation.SuppressLint
import android.content.DialogInterface
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
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
import com.fuck.modeus.data.GradeUiItem
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

    private lateinit var gestureDetector: GestureDetector
    private var selectedTarget: ScheduleTarget? = null
    private var headerHeightPx = 0
    private var activeProfilePopup: android.widget.ListPopupWindow? = null
    private var lastProfileDismissTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        setContentView(R.layout.activity_main)

        hideSystemUI()

        drawerLayout = findViewById(R.id.drawerLayout)
        viewPager = findViewById(R.id.viewPager)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)

        val mainContentContainer = findViewById<View>(R.id.mainContentContainer)
        ViewCompat.setOnApplyWindowInsetsListener(mainContentContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            val density = resources.displayMetrics.density
            headerHeightPx = insets.top + (70 * density).toInt()
            WindowInsetsCompat.CONSUMED
        }

        initGestureDetector()

        // Сначала настраиваем UI, потом бар
        setupMainContent()
        setupDrawer()

        // Бар профилей инициализируем после drawer, чтобы настройки успели подгрузиться
        setupProfileBar()

        observeViewModel()

        val restartId = intent.getStringExtra("RESTART_WITH_ID")
        val pendingCourseId = intent.getStringExtra("PENDING_COURSE_ID")
        val pendingProtoId = intent.getStringExtra("PENDING_PROTO_ID")
        if (restartId != null) {
            viewModel.loadSchedule(restartId)
            intent.removeExtra("RESTART_WITH_ID")
            // Если есть отложенный запрос баллов — выполняем его
            if (pendingCourseId != null) {
                // Небольшая задержка, чтобы UI успел прогрузиться
                swipeRefreshLayout.postDelayed({
                    viewModel.loadGrades(pendingCourseId, pendingProtoId)
                    Toast.makeText(this, "Повторная загрузка баллов...", Toast.LENGTH_SHORT).show()
                }, 500)
            }
        } else {
            // Во всех остальных случаях (обычный запуск, восстановление из фона)
            // Проверяем: если данные уже есть в ViewModel (например, при повороте экрана) - ничего не делаем.
            // Если данных нет (холодный старт или восстановление процесса) - грузим КЭШ.
            if (viewModel.scheduleMap.value == null) {
                viewModel.loadInitialSchedule(keepCurrentPosition = savedInstanceState != null)
            }
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun initGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val mode = viewModel.navigationMode.value
                if (mode == NavigationMode.TOUCH || mode == NavigationMode.BOTH) {
                    val width = resources.displayMetrics.widthPixels
                    if (e.x < width * 0.35) {
                        viewPager.currentItem = viewPager.currentItem - 1
                        return true
                    }
                    if (e.x > width * 0.65) {
                        viewPager.currentItem = viewPager.currentItem + 1
                        return true
                    }
                }
                return false
            }
        })
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
                hideKeyboard()
                if (layoutSettings.visibility == View.VISIBLE) {
                    layoutSettings.visibility = View.GONE
                    layoutMain.visibility = View.VISIBLE
                }
            }
            override fun onDrawerStateChanged(newState: Int) {
                if (newState == DrawerLayout.STATE_DRAGGING) hideKeyboard()
            }
        })

        val rbSfedu = navigationView.findViewById<RadioButton>(R.id.rbSfedu)
        val rbRdCenter = navigationView.findViewById<RadioButton>(R.id.rbRdCenter)
        val btnEditUrl = navigationView.findViewById<ImageView>(R.id.btnEditUrl)

        val switchEmpty = navigationView.findViewById<SwitchMaterial>(R.id.switchShowEmpty)
        val spinnerNav = navigationView.findViewById<android.widget.Spinner>(R.id.spinnerNavMode)

        val switchParallel = navigationView.findViewById<SwitchMaterial>(R.id.switchParallel)
        val containerParallel = navigationView.findViewById<LinearLayout>(R.id.containerParallelSettings)
        val etCount = navigationView.findViewById<EditText>(R.id.etParallelCount)
        val btnSaveCount = navigationView.findViewById<View>(R.id.btnSaveParallel)

        val btnUpdateDb = navigationView.findViewById<View>(R.id.btnUpdateDb)
        val btnExportDb = navigationView.findViewById<View>(R.id.btnExportDb)
        val btnLogout = navigationView.findViewById<View>(R.id.btnLogoutInternal)

        val btnToggleAdv = navigationView.findViewById<TextView>(R.id.btnToggleAdvanced)
        val containerAdv = navigationView.findViewById<LinearLayout>(R.id.containerAdvanced)
        val rgProfileMode = navigationView.findViewById<RadioGroup>(R.id.rgProfileMode)
        val rbBar = navigationView.findViewById<RadioButton>(R.id.rbModeBar)
        val rbDropdown = navigationView.findViewById<RadioButton>(R.id.rbModeDropdown)
        val btnCorruptToken = navigationView.findViewById<View>(R.id.btnCorruptToken)
        val btnGradebook = navigationView.findViewById<View>(R.id.btnGlobalGradebook)

        if (ApiSettings.getProfileDisplayMode(this) == com.fuck.modeus.data.ProfileDisplayMode.BAR) {
            rbBar.isChecked = true
        } else {
            rbDropdown.isChecked = true
        }

        // Слушатели
        rbBar.setOnClickListener {
            ApiSettings.setProfileDisplayMode(this, com.fuck.modeus.data.ProfileDisplayMode.BAR)
            setupProfileBar() // Перерисовываем на главном экране
        }
        rbDropdown.setOnClickListener {
            ApiSettings.setProfileDisplayMode(this, com.fuck.modeus.data.ProfileDisplayMode.DROPDOWN)
            setupProfileBar()
        }
        // API Source
        val currentSource = ApiSettings.getApiSource(this)
        if (currentSource == ApiSource.SFEDU) {
            rbSfedu.isChecked = true
            btnLogout.visibility = View.VISIBLE
        } else {
            rbRdCenter.isChecked = true
            btnLogout.visibility = View.GONE
        }

        btnCorruptToken.setOnClickListener {
            // 1. Сначала узнаем наш реальный ID, пока токен еще жив
            val currentId = com.fuck.modeus.data.TokenManager.getPersonIdFromToken(this)

            if (currentId != null) {
                // 2. Создаем "куклу" - JSON с нашим ID
                val fakeJson = """{"person_id": "$currentId"}"""

                // 3. Кодируем в Base64 (как в настоящем JWT)
                val fakePayload = android.util.Base64.encodeToString(
                    fakeJson.toByteArray(),
                    android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
                )

                // 4. Склеиваем фейковый токен: Header.Payload.BadSignature
                // Для приложения он выглядит валидным (ID читается),
                // но сервер его отвергнет из-за подписи.
                val badToken = "eyJhbGciOiJIUzI1NiJ9.$fakePayload.I_AM_BAD_SIGNATURE"

                com.fuck.modeus.data.TokenManager.saveToken(this, badToken)
                Toast.makeText(this, "Токен подменен (ID сохранен, но подпись неверна). Жми 'Баллы'!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Сначала войдите в аккаунт, чтобы было что портить!", Toast.LENGTH_SHORT).show()
            }

            drawerLayout.closeDrawer(GravityCompat.END)
        }

        btnGradebook.setOnClickListener {
            // Проверка на "Свой профиль"
            val myId = com.fuck.modeus.data.TokenManager.getPersonIdFromToken(this)
            val currentId = viewModel.currentTargetId

            if (myId != null && currentId != null && myId == currentId) {
                viewModel.loadGlobalGradebook()
                Toast.makeText(this, "Собираем оценки по всем предметам...", Toast.LENGTH_SHORT).show()
                drawerLayout.closeDrawer(GravityCompat.END)
            } else {
                Toast.makeText(this, "Зачётка доступна только в вашем профиле SFEDU", Toast.LENGTH_LONG).show()
            }
        }

        rbSfedu.setOnClickListener {
            ApiSettings.setApiSource(this, ApiSource.SFEDU)
            rbSfedu.isChecked = true; rbRdCenter.isChecked = false
            btnLogout.visibility = View.VISIBLE
            Toast.makeText(this, "Источник: SFEDU Modeus", Toast.LENGTH_SHORT).show()
        }
        rbRdCenter.setOnClickListener {
            ApiSettings.setApiSource(this, ApiSource.RDCENTER)
            rbSfedu.isChecked = false; rbRdCenter.isChecked = true
            btnLogout.visibility = View.GONE
            Toast.makeText(this, "Источник: ИКТИБ (RDCenter)", Toast.LENGTH_SHORT).show()
        }
        btnEditUrl.setOnClickListener { showUrlEditDialog() }

        // --- ИСПРАВЛЕННЫЙ СПИННЕР ---
        val modes = arrayOf("Только свайпы", "Только касания", "Свайпы и касания (Both)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        spinnerNav.adapter = adapter

        // Важно: Сначала вешаем слушатель, потом в Observer будем обновлять
        spinnerNav.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                (view as? TextView)?.setTextColor(Color.WHITE)

                val newMode = when(position) {
                    0 -> NavigationMode.SWIPE
                    1 -> NavigationMode.TOUCH
                    else -> NavigationMode.BOTH
                }

                // Сохраняем ТОЛЬКО если отличается от текущего в VM (чтобы избежать цикла при старте)
                if (newMode != viewModel.navigationMode.value) {
                    viewModel.setNavigationMode(newMode)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Наблюдаем за VM, чтобы выставить правильное начальное значение
        viewModel.navigationMode.observe(this) { mode ->
            val selectionIndex = when(mode) {
                NavigationMode.SWIPE -> 0
                NavigationMode.TOUCH -> 1
                else -> 2 // BOTH
            }
            if (spinnerNav.selectedItemPosition != selectionIndex) {
                spinnerNav.setSelection(selectionIndex, false)
            }

            // Обновляем ViewPager
            val swipeEnabled = (mode == NavigationMode.SWIPE || mode == NavigationMode.BOTH)
            viewPager.isUserInputEnabled = swipeEnabled
        }

        // --- ОСТАЛЬНЫЕ НАСТРОЙКИ ---
        viewModel.showEmptyLessons.observe(this) {
            if (switchEmpty.isChecked != it) switchEmpty.isChecked = it
        }
        switchEmpty.setOnCheckedChangeListener { _, isChecked -> viewModel.setShowEmptyLessons(isChecked) }

        // Мультипрофиль
        val isParallel = ApiSettings.isParallelEnabled(this)
        switchParallel.isChecked = isParallel
        containerParallel.visibility = if (isParallel) View.VISIBLE else View.GONE
        etCount.setText(ApiSettings.getParallelCount(this).toString())

        switchParallel.setOnCheckedChangeListener { _, isChecked ->
            ApiSettings.setParallelEnabled(this, isChecked)
            containerParallel.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                ApiSettings.setCurrentProfile(this, 0)
                setupProfileBar()
                // При выключении возвращаемся к дефолтному
                viewModel.loadInitialSchedule(keepCurrentPosition = true)
            } else {
                setupProfileBar()
                // При включении остаемся на текущем или грузим 0-й
                viewModel.loadInitialSchedule(keepCurrentPosition = true)
            }
        }

        btnSaveCount.setOnClickListener {
            val countStr = etCount.text.toString()
            val count = countStr.toIntOrNull()?.coerceIn(2, 10) ?: 2
            ApiSettings.setParallelCount(this, count)
            etCount.setText(count.toString())
            hideKeyboard()
            setupProfileBar()
            Toast.makeText(this, "Профилей: $count", Toast.LENGTH_SHORT).show()
        }

        // Advanced Options
        btnToggleAdv.setOnClickListener {
            if (containerAdv.visibility == View.VISIBLE) {
                containerAdv.visibility = View.GONE
                btnToggleAdv.text = "Дополнительные опции..."
            } else {
                containerAdv.visibility = View.VISIBLE
                btnToggleAdv.text = "Скрыть дополнительные опции"
            }
        }

        btnUpdateDb.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Обновление базы")
                .setMessage("Скачать актуальные данные?")
                .setPositiveButton("Да") { _, _ ->
                    drawerLayout.closeDrawer(GravityCompat.END)
                    viewModel.updateDatabase()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        btnExportDb.setOnClickListener { exportDatabaseFile() }
        btnLogout.setOnClickListener { performLogout() }

        setupSearchLogic(navigationView)
    }
    private var profileDropdownTrigger: TextView? = null
    private fun setupProfileBar() {
        val containerProfiles = findViewById<LinearLayout>(R.id.containerProfiles)
        containerProfiles.removeAllViews()

        if (!ApiSettings.isParallelEnabled(this)) {
            containerProfiles.visibility = View.GONE
            return
        }
        containerProfiles.visibility = View.VISIBLE

        val count = ApiSettings.getParallelCount(this)
        val current = ApiSettings.getCurrentProfile(this)
        val mode = ApiSettings.getProfileDisplayMode(this)

        if (mode == com.fuck.modeus.data.ProfileDisplayMode.BAR) {
            // --- СТАРЫЙ РЕЖИМ (КНОПКИ) ---
            val useFullText = count <= 3
            for (i in 0 until count) {
                val btn = TextView(this)
                btn.text = if (useFullText) "Профиль ${i + 1}" else "${i + 1}"
                btn.textSize = 14f
                btn.gravity = android.view.Gravity.CENTER
                btn.maxLines = 1

                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (i < count - 1) params.marginEnd = (4 * resources.displayMetrics.density).toInt()
                btn.layoutParams = params
                val padding = (8 * resources.displayMetrics.density).toInt()
                btn.setPadding(0, padding, 0, padding)

                val drawable = android.graphics.drawable.GradientDrawable()
                drawable.cornerRadius = (8 * resources.displayMetrics.density)

                if (i == current) {
                    btn.setTextColor(Color.BLACK)
                    drawable.setColor(Color.parseColor("#FFC107"))
                    btn.setTypeface(null, android.graphics.Typeface.BOLD)
                } else {
                    btn.setTextColor(Color.WHITE)
                    drawable.setColor(Color.parseColor("#444444"))
                    drawable.setStroke(2, Color.parseColor("#666666"))
                    btn.setTypeface(null, android.graphics.Typeface.NORMAL)
                }
                btn.background = drawable

                btn.setOnClickListener {
                    if (ApiSettings.getCurrentProfile(this) != i) {
                        ApiSettings.setCurrentProfile(this, i)
                        setupProfileBar()

                        // ИЗМЕНЕНИЕ: Передаем true, чтобы сохранить день
                        viewModel.loadInitialSchedule(keepCurrentPosition = true)
                    }
                }
                containerProfiles.addView(btn)
            }
        } else {
            // --- НОВЫЙ РЕЖИМ (СПИСОК) ---
            val trigger = TextView(this)

            // ИСПРАВЛЕНИЕ: Пишем просто "Профиль X", без имени объекта
            val profileTitle = "Профиль ${current + 1}"
            trigger.text = profileTitle

            trigger.textSize = 16f
            trigger.setTextColor(Color.BLACK)
            trigger.gravity = android.view.Gravity.CENTER
            trigger.setTypeface(null, android.graphics.Typeface.BOLD)

            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            trigger.layoutParams = params

            val padding = (10 * resources.displayMetrics.density).toInt()
            trigger.setPadding(padding, padding, padding, padding)

            val drawable = android.graphics.drawable.GradientDrawable()
            drawable.cornerRadius = (8 * resources.displayMetrics.density)
            drawable.setColor(Color.parseColor("#FFC107"))
            trigger.background = drawable

            val arrow = resources.getDrawable(android.R.drawable.arrow_down_float, null)
            arrow.setTint(Color.BLACK)
            trigger.setCompoundDrawablesWithIntrinsicBounds(null, null, arrow, null)
            trigger.compoundDrawablePadding = padding

            trigger.setOnClickListener { showProfilePopup(trigger, count) }

            containerProfiles.addView(trigger)
        }
    }

    // Метод показа Popup

    private fun showProfilePopup(anchor: View, count: Int) {
        // 1. ПРОВЕРКА НА НЕДАВНЕЕ ЗАКРЫТИЕ (защита от повторного открытия при клике)
        // Если список закрылся менее 300мс назад, значит пользователь нажал на кнопку,
        // чтобы закрыть его. Игнорируем этот клик.
        if (System.currentTimeMillis() - lastProfileDismissTime < 300) {
            return
        }

        // 2. Если вдруг он все еще считается открытым в системе
        if (activeProfilePopup != null && activeProfilePopup!!.isShowing) {
            activeProfilePopup!!.dismiss()
            return
        }

        val listPopupWindow = android.widget.ListPopupWindow(this)
        listPopupWindow.anchorView = anchor
        activeProfilePopup = listPopupWindow

        val profiles = mutableListOf<Pair<String, String>>()
        for (i in 0 until count) {
            val name = ApiSettings.getProfileTargetName(this, i) ?: "(Пусто)"
            profiles.add("Профиль ${i + 1}" to name)
        }

        val adapter = object : android.widget.ArrayAdapter<Pair<String, String>>(this, R.layout.item_profile_dropdown, profiles) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_profile_dropdown, parent, false)
                val title = view.findViewById<TextView>(R.id.tvProfileTitle)
                val subtitle = view.findViewById<TextView>(R.id.tvProfileSubtitle)

                val item = getItem(position)
                title.text = item?.first
                subtitle.text = item?.second

                if (position == ApiSettings.getCurrentProfile(context)) {
                    title.setTextColor(Color.parseColor("#FFC107"))
                } else {
                    title.setTextColor(Color.WHITE)
                }
                return view
            }
        }

        listPopupWindow.setAdapter(adapter)

        listPopupWindow.setOnItemClickListener { _, _, position, _ ->
            if (ApiSettings.getCurrentProfile(this) != position) {
                ApiSettings.setCurrentProfile(this, position)
                setupProfileBar()
                viewModel.loadInitialSchedule(keepCurrentPosition = true)
            }
            listPopupWindow.dismiss()
        }

        // 3. ФИКСАЦИЯ ВРЕМЕНИ ЗАКРЫТИЯ
        listPopupWindow.setOnDismissListener {
            activeProfilePopup = null
            lastProfileDismissTime = System.currentTimeMillis() // <--- ЗАПОМИНАЕМ ВРЕМЯ
        }

        listPopupWindow.show()
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
        viewModel.loadSchedule(target.id)
        drawerLayout.closeDrawer(GravityCompat.END)
    }

    fun showLessonDetailsDialog(item: ScheduleItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_lesson_details, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            //.setPositiveButton("Закрыть", null)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvSubject = dialogView.findViewById<TextView>(R.id.tvDetailSubject)
        val tvModuleFull = dialogView.findViewById<TextView>(R.id.tvDetailModuleFull)
        val containerTeachers = dialogView.findViewById<LinearLayout>(R.id.containerTeachersList)
        val tvRoom = dialogView.findViewById<TextView>(R.id.tvDetailRoom)
        val tvGroup = dialogView.findViewById<TextView>(R.id.tvDetailGroup)
        val teachersList = item.teacher.split("\n").filter { it.isNotBlank() }

        tvSubject.text = item.subject
        tvModuleFull.text = "📚 Модуль: ${item.moduleFullName ?: "не указан"}"

        if (teachersList.isEmpty() || (teachersList.size == 1 && teachersList[0] == "не назначен")) {
            val tv = TextView(this)
            tv.text = "не назначен"
            tv.setTextColor(Color.GRAY)
            tv.textSize = 16f
            containerTeachers.addView(tv)
        } else {
            // Для каждого препода создаем свой TextView
            for (teacherName in teachersList) {
                val tv = TextView(this)
                tv.text = teacherName.trim()
                tv.textSize = 16f
                tv.setTextColor(getColor(R.color.link_blue)) // Синий цвет ссылки

                // Отступы и фон нажатия
                tv.setPadding(0, 8, 0, 8)
                val outValue =android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                tv.setBackgroundResource(outValue.resourceId)

                // Клик - Поиск в приложении
                tv.setOnClickListener {
                    searchFor(teacherName.trim())
                    dialog.dismiss()
                }

                // Долгий клик - Гугл
                tv.setOnLongClickListener {
                    try {
                        val url = "https://www.google.com/search?q=${android.net.Uri.encode("${teacherName.trim()} ЮФУ")}"
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        dialog.dismiss()
                    } catch (e: Exception) {}
                    true
                }

                containerTeachers.addView(tv)
            }
        }

        tvRoom.text = "🚪 Аудитория: ${item.room} (${item.locationType})"
        if (!item.room.startsWith("не назначена") && item.room != "Online") {
            tvRoom.setTextColor(getColor(R.color.link_blue))
            tvRoom.setOnClickListener { searchFor(item.room); dialog.dismiss() }
        }

        tvGroup.text = "👥 Группа: ${item.groupCode ?: "не указана"} (участников: ${item.teamSize ?: "?"})"
        dialog.show()

        val btnAttendees = dialogView.findViewById<View>(R.id.btnShowAttendees)
        if (ApiSettings.getApiSource(this) == ApiSource.SFEDU) {
            btnAttendees.visibility = View.VISIBLE
            btnAttendees.setOnClickListener {
                showAttendeesDialog(item.id)
            }
        } else {
            btnAttendees.visibility = View.GONE
        }
        val btnGrades = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShowGrades)
        val apiSource = ApiSettings.getApiSource(this)

        // 1. Кто залогинен? (ID из токена)
        val myPersonId = com.fuck.modeus.data.TokenManager.getPersonIdFromToken(this)

        // 2. Кого смотрим? (ID текущего расписания)
        val targetId = viewModel.currentTargetId

        // 3. Проверка: Это SFEDU? Предмет имеет ID? Мы смотрим СЕБЯ?
        // (myPersonId == targetId) гарантирует, что мы не увидим свои оценки у друга
        val canShowGrades = apiSource == ApiSource.SFEDU
                && item.courseUnitId != null
                && myPersonId != null
                && targetId != null
                && myPersonId == targetId

        if (canShowGrades) {
            btnGrades.visibility = View.VISIBLE
            btnGrades.setOnClickListener {
                // Передаем теперь два параметра!
                viewModel.loadGrades(item.courseUnitId!!, item.coursePrototypeId)
                Toast.makeText(this, "Загрузка баллов...", Toast.LENGTH_SHORT).show()
            }
        } else {
            btnGrades.visibility = View.GONE
        }
        dialog.show()
    }

    private fun searchFor(name: String) {
        // 1. Сначала ищем ID по имени
        val targetId = viewModel.findTargetIdByName(name)

        if (targetId != null) {
            // Если нашли - грузим
            viewModel.loadSchedule(targetId)
            Toast.makeText(this, "Загрузка: $name", Toast.LENGTH_SHORT).show()
        } else {
            // Если нет - открываем меню и поиск
            drawerLayout.openDrawer(GravityCompat.END)
            val etSearch = findViewById<NavigationView>(R.id.navigationView).findViewById<EditText>(R.id.etSearch)
            etSearch.setText(name)
            etSearch.setSelection(name.length)
            viewModel.search(name)
        }
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

        viewModel.navigationMode.observe(this) { mode ->
            val swipeEnabled = (mode == NavigationMode.SWIPE || mode == NavigationMode.BOTH)
            viewPager.isUserInputEnabled = swipeEnabled

            if (!::gestureDetector.isInitialized) initGestureDetector()
        }

        viewModel.authRequired.observe(this) { params ->
            val (courseId, protoId) = params

            // Запускаем LoginActivity с данными для возврата
            val intent = Intent(this, LoginActivity::class.java)
            // Передаем текущий ID пользователя, чтобы LoginActivity знала, кого перезагружать
            val currentPersonId = viewModel.currentTargetId ?: com.fuck.modeus.data.TokenManager.getPersonIdFromToken(this)

            intent.putExtra("TARGET_ID", currentPersonId)
            intent.putExtra("PENDING_COURSE_ID", courseId)
            intent.putExtra("PENDING_PROTO_ID", protoId)

            startActivity(intent)
            Toast.makeText(this, "Обновление сессии...", Toast.LENGTH_SHORT).show()
        }

        viewModel.gradebookData.observe(this) { list ->
            if (list.isNotEmpty()) {
                showGlobalGradebookDialog(list)
                viewModel.clearGradebookData() // Очищаем, чтобы не открылось повторно при повороте
            }
        }

        viewModel.gradeData.observe(this) { data ->
            if (data != null) {
                val (totalScore, list, controlType) = data // Распаковываем Triple
                showGradesDialog(totalScore, list, controlType)
                viewModel.clearGradeResult()
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
            .setNeutralButton("Сброс") { dialog: DialogInterface, which: Int ->
                ApiSettings.resetRdSettings(context)
                if (ApiSettings.getApiSource(context) == ApiSource.RDCENTER) {
                    viewModel.refreshSchedule()
                }
                Toast.makeText(context, "Настройки сброшены", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun exportDatabaseFile() {
        try {
            val dbFile = java.io.File(filesDir, "allid_v2.json")
            if (!dbFile.exists()) {
                Toast.makeText(this, "База данных еще не создана", Toast.LENGTH_SHORT).show()
                return
            }
            val content = dbFile.readText()
            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val destFile = java.io.File(downloadDir, "modeus_db_dump.json")
            dbFile.copyTo(destFile, overwrite = true)
            Toast.makeText(this, "Сохранено в Загрузки: modeus_db_dump.json", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }
    private fun showGradesDialog(totalScore: String, items: List<GradeUiItem>, controlType: String?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_grades, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvTotal = dialogView.findViewById<TextView>(R.id.tvGradeTotal)
        val tvSubject = dialogView.findViewById<TextView>(R.id.tvGradeSubject)
        val tvControl = dialogView.findViewById<TextView>(R.id.tvGradeControl) // <--- НОВОЕ
        val tvDisclaimer = dialogView.findViewById<TextView>(R.id.tvGradeDisclaimer)
        val rv = dialogView.findViewById<RecyclerView>(R.id.rvGrades)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseGrades)

        tvSubject.text = "Текущая успеваемость"
        tvTotal.text = "$totalScore баллов"

        // Отображение типа контроля
        if (!controlType.isNullOrBlank()) {
            tvControl.text = controlType
            tvControl.visibility = View.VISIBLE
        } else {
            tvControl.visibility = View.GONE
        }

        val scoreVal = totalScore.toDoubleOrNull() ?: 0.0

        // ЛОГИКА ДИСКЛЕЙМЕРА И ЦВЕТОВ
        if (scoreVal == 0.0) {
            // Красный цвет для нуля
            tvTotal.setTextColor(Color.parseColor("#EF5350"))
            tvDisclaimer.setTextColor(Color.parseColor("#EF5350"))
            tvDisclaimer.text = "Внимание: 0 баллов не всегда означает отсутствие работ. Это значит лишь то, что баллы не были занесены непосредственно в базу Sfedu Modeus."
        } else {
            // Зеленый (или желтый если мало) для нормальных баллов
            if (scoreVal < 60) {
                tvTotal.setTextColor(Color.parseColor("#FFC107")) // Желтый/Оранжевый
            } else {
                tvTotal.setTextColor(Color.parseColor("#4CAF50")) // Зеленый
            }
            // Нейтральный серый текст
            tvDisclaimer.setTextColor(Color.parseColor("#B0B0B0"))
            tvDisclaimer.text = "Возможно, у вас больше баллов, просто они не занесены в систему Sfedu Modeus."
        }

        rv.layoutManager = LinearLayoutManager(this)
        val adapter = GradesAdapter()
        rv.adapter = adapter
        adapter.submitList(items)

        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
    }

    private fun showAttendeesDialog(eventId: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_attendees, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val rv = dialogView.findViewById<RecyclerView>(R.id.rvAttendees)
        val pb = dialogView.findViewById<ProgressBar>(R.id.pbAttendees)
        val tvError = dialogView.findViewById<TextView>(R.id.tvAttendeesError)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseAttendees)

        val adapter = AttendeesAdapter { attendee ->
            dialog.dismiss()
            viewModel.loadSchedule(attendee.personId)
            Toast.makeText(this, "Загрузка: ${attendee.fullName}", Toast.LENGTH_SHORT).show()
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnClose.setOnClickListener { dialog.dismiss() }

        viewModel.loadEventAttendees(eventId)

        viewModel.attendeesLoading.observe(this) { isLoading ->
            pb.visibility = if (isLoading) View.VISIBLE else View.GONE
            rv.visibility = if (isLoading) View.GONE else View.VISIBLE
            tvError.visibility = View.GONE
        }

        viewModel.attendeesList.observe(this) { list ->
            if (list.isNotEmpty()) {
                adapter.submitList(list)
                rv.visibility = View.VISIBLE
                tvError.visibility = View.GONE
            } else {
                if (viewModel.attendeesLoading.value == false) {
                    rv.visibility = View.GONE
                    tvError.visibility = View.VISIBLE
                    tvError.text = "Список пуст или ошибка доступа"
                }
            }
        }
        dialog.show()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            // 1. Логика скрытия клавиатуры при открытом Drawer (оставляем как было)
            if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                if (ev.action == MotionEvent.ACTION_DOWN) {
                    val v = currentFocus
                    if (v is EditText) {
                        val outRect = android.graphics.Rect()
                        v.getGlobalVisibleRect(outRect)
                        if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                            hideKeyboard()
                        }
                    }
                }
                return super.dispatchTouchEvent(ev)
            }

            // 2. ИСПРАВЛЕННАЯ ЛОГИКА ПЕРЕКЛЮЧЕНИЯ СТРАНИЦ
            val mode = viewModel.navigationMode.value ?: NavigationMode.BOTH
            val isTouchAllowed = (mode == NavigationMode.TOUCH || mode == NavigationMode.BOTH)

            if (isTouchAllowed && ::gestureDetector.isInitialized) {
                // Получаем границы ViewPager на экране
                val viewPagerRect = android.graphics.Rect()
                viewPager.getGlobalVisibleRect(viewPagerRect)

                // Проверяем, попал ли палец внутрь ViewPager
                // ViewPager - это область с парами, исключая бары дней и недель
                if (viewPagerRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    if (gestureDetector.onTouchEvent(ev)) {
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showGlobalGradebookDialog(items: List<com.fuck.modeus.data.GradebookEntry>) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_grades, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Переиспользуем layout от обычных оценок, просто меняем заголовки
        val tvSubject = dialogView.findViewById<TextView>(R.id.tvGradeSubject)
        val tvTotal = dialogView.findViewById<TextView>(R.id.tvGradeTotal)
        val tvDisclaimer = dialogView.findViewById<TextView>(R.id.tvGradeDisclaimer)
        val tvControl = dialogView.findViewById<TextView>(R.id.tvGradeControl) // Скрываем
        val rv = dialogView.findViewById<RecyclerView>(R.id.rvGrades)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseGrades)

        tvSubject.text = "Моя зачётка"
        tvTotal.visibility = View.GONE
        tvControl.visibility = View.GONE // Нам тут тип контроля не нужен
        tvDisclaimer.visibility = View.GONE // Дисклеймер тоже можно убрать или изменить

        rv.layoutManager = LinearLayoutManager(this)
        val adapter = GradebookAdapter()
        rv.adapter = adapter
        adapter.submitList(items)

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}