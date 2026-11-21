package com.taylorlauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taylorlauncher.core.AppFonts
import com.taylorlauncher.core.AppRepository
import com.taylorlauncher.core.LauncherPrefs
import com.taylorlauncher.core.TvInputEntry
import com.taylorlauncher.core.TvInputs
import com.taylorlauncher.core.Tween
import com.taylorlauncher.databinding.ActivitySettingsBinding
import com.taylorlauncher.ui.ValueSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: LauncherPrefs

    private var lastLeftPress = 0L
    private var popupVisible = false
    private var popupMulti = false
    private var popupSelection = BooleanArray(0)
    private var popupApply: (() -> Unit)? = null
    private var lastFocusedView: View? = null

    private val popupItems = mutableListOf<View>()
    private val settingsItems = mutableListOf<View>()
    private var popupFocusIndex = 0
    private var popupColumns = 1
    private var popupMoveItem: ((Int) -> Boolean)? = null

    private val sleepHandler = Handler(Looper.getMainLooper())
    private val sleepRunnable = Runnable {
        prefs.pendingSleep = true
        finish()
    }

    private val fontLabels: List<String>
        get() = AppFonts.options.map { it.label }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.isSoundEffectsEnabled = false
        prefs = LauncherPrefs(this)

        if (AppFonts.options.none { it.key == prefs.fontFamily }) {
            prefs.fontFamily = AppFonts.DEFAULT
        }

        binding.settingsVersion.text = getString(R.string.about_version, versionName())

        binding.settingsContent.layoutManager = LinearLayoutManager(this)
        binding.settingsContent.adapter = SettingsAdapter(settingsItems)
        binding.settingsContent.itemAnimator = null
        buildContent()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (popupVisible) hidePopup() else goHome()
            }
        })
        binding.root.post { focusFirst() }
    }

    override fun onResume() {
        super.onResume()
        resetSleepTimer()
        if (panelHiddenForChild) {
            panelHiddenForChild = false
            restorePanel()
        }
    }

    override fun onPause() {
        sleepHandler.removeCallbacks(sleepRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        sleepHandler.removeCallbacks(sleepRunnable)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            resetSleepTimer()

            if (event.keyCode == KeyEvent.KEYCODE_HOME) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
                return true
            }
            if (popupVisible) {

                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> return popupMove(-popupColumns)
                    KeyEvent.KEYCODE_DPAD_DOWN -> return popupMove(popupColumns)
                    KeyEvent.KEYCODE_DPAD_LEFT -> return popupMoveHorizontal(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> return popupMoveHorizontal(1)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        popupActivate()
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (handleLeftKey(event)) return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (panelMove(true)) return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (panelMove(false)) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun popupMove(delta: Int): Boolean {
        if (popupItems.isEmpty()) return true
        focusPopupItem((popupFocusIndex + delta).coerceIn(0, popupItems.lastIndex))
        return true
    }

    private fun popupMoveHorizontal(delta: Int): Boolean {

        popupMoveItem?.let { if (it(delta)) return true }
        if (popupItems.isEmpty()) return true
        focusPopupItem((popupFocusIndex + delta).coerceIn(0, popupItems.lastIndex))
        return true
    }

    private fun focusPopupItem(index: Int) {
        if (popupItems.isEmpty()) return
        popupFocusIndex = index.coerceIn(0, popupItems.lastIndex)
        val view = popupItems[popupFocusIndex]
        view.requestFocus()
        scrollTo(binding.popupScroll, binding.popupList, view)
    }

    private fun popupActivate() {
        popupItems.getOrNull(popupFocusIndex)?.performClick()
    }

    private fun panelMove(up: Boolean): Boolean {
        val recycler = binding.settingsContent
        val focused = currentFocus ?: return false
        val currentItem = recycler.findContainingItemView(focused) ?: return false
        val current = recycler.getChildAdapterPosition(currentItem)
        if (current == RecyclerView.NO_POSITION) return false

        val focusable = settingsItems.indices.filter { firstFocusable(settingsItems[it]) != null }
        if (focusable.isEmpty()) return false
        val index = focusable.indexOf(current)
        val next = when {
            index < 0 -> if (up) focusable.last() else focusable.first()
            up -> focusable[(index - 1 + focusable.size) % focusable.size]
            else -> focusable[(index + 1) % focusable.size]
        }

        if (recycler.findViewHolderForAdapterPosition(next) == null) {
            recycler.scrollToPosition(next)
            recycler.post { firstFocusable(settingsItems[next])?.requestFocus() }
        } else {
            firstFocusable(settingsItems[next])?.requestFocus()
        }
        return true
    }

    private fun scrollTo(scroll: android.widget.ScrollView, container: View, view: View) {
        var top = 0
        var node: View? = view
        while (node != null && node !== container) {
            top += node.top
            node = node.parent as? View
        }
        val bottom = top + view.height
        val visibleTop = scroll.scrollY
        val visibleBottom = visibleTop + scroll.height
        when {
            top < visibleTop -> scroll.smoothScrollTo(0, top)
            bottom > visibleBottom -> scroll.smoothScrollTo(0, bottom - scroll.height)
        }
    }

    private fun handleLeftKey(event: KeyEvent): Boolean {
        val focused = currentFocus
        val sliderAllowed = focused is ValueSlider && !focused.isAtMin()
        if (sliderAllowed) return false

        val now = SystemClock.uptimeMillis()
        if (event.repeatCount > 0) return true
        if (now - lastLeftPress < 1000) {
            goHome()
            return true
        }
        lastLeftPress = now
        showHint(getString(R.string.settings_left_hint))
        return true
    }

    private fun firstFocusable(view: View): View? {
        if (view.visibility != View.VISIBLE) return null
        if (view.isFocusable) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                firstFocusable(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun focusFirst() {
        binding.settingsContent.post {
            settingsItems.firstNotNullOfOrNull { firstFocusable(it) }?.requestFocus()
        }
    }

    private inner class SettingsAdapter(
        private val items: List<View>
    ) : RecyclerView.Adapter<SettingsAdapter.Holder>() {

        inner class Holder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            (item.parent as? ViewGroup)?.removeView(item)
            holder.container.removeAllViews()
            holder.container.addView(item)
        }

        override fun onViewRecycled(holder: Holder) {
            holder.container.removeAllViews()
        }

        override fun getItemCount() = items.size
    }

    private fun showHint(text: String) {
        binding.settingsHint.text = text
        binding.settingsHint.alpha = 1f
        binding.settingsHint.postDelayed({
            if (!isFinishing) Tween.alpha(binding.settingsHint, 0f, 400L)
        }, 3000)
    }

    private var panelHiddenForChild = false

    private fun launchChild(intent: Intent): Boolean {
        panelHiddenForChild = true
        hidePanelForChild()
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            panelHiddenForChild = false
            restorePanel()
            false
        }
    }

    private fun hidePanelForChild() {
        val panel = binding.settingsPanel
        val width = (if (panel.width > 0) panel.width else dp(300)).toFloat()
        panel.translationX = width
        panel.alpha = 0f
    }

    private fun restorePanel() {
        binding.settingsPanel.translationX = 0f
        binding.settingsPanel.alpha = 1f
    }

    private fun goHome() {

        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }

    private fun resetSleepTimer() {
        sleepHandler.removeCallbacks(sleepRunnable)
        val minutes = prefs.sleepTimeoutMinutes
        if (minutes > 0) {
            sleepHandler.postDelayed(sleepRunnable, minutes * 60_000L)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dimen(id: Int): Int = resources.getDimensionPixelSize(id)

    private fun sp(id: Int): Float = resources.getDimension(id) / resources.displayMetrics.scaledDensity

    private var fontRow: TextView? = null
    private var appsRow: TextView? = null

    private fun section(title: String) {
        settingsItems.add(TextView(this).apply {
            text = title.uppercase()
            setTextColor(0xFF8E8E93.toInt())
            textSize = sp(R.dimen.settings_section_text_size)
            letterSpacing = 0.08f
            setPadding(dp(4), dp(14), dp(4), dp(6))
        })
    }

    private fun rowBase(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(10), 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dimen(R.dimen.settings_row_height)
        ).apply { bottomMargin = dp(4) }
        background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.settings_row_bg)
        isSoundEffectsEnabled = false
    }

    private fun addLabel(row: LinearLayout, text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = sp(R.dimen.settings_row_text_size)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(this)
        }

    private fun addValue(row: LinearLayout, text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(0xFF8E8E93.toInt())
            textSize = sp(R.dimen.settings_row_text_size)
            row.addView(this)
        }

    private fun switchRow(label: String, getter: () -> Boolean, setter: (Boolean) -> Unit) {
        val row = rowBase().apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        addLabel(row, label)
        val value = addValue(row, if (getter()) getString(R.string.settings_on) else getString(R.string.settings_off))
        row.setOnClickListener {
            val next = !getter()
            setter(next)
            value.text = if (next) getString(R.string.settings_on) else getString(R.string.settings_off)
        }
        settingsItems.add(row)
    }

    private fun sliderRow(
        label: String,
        min: Int,
        max: Int,
        step: Int,
        initial: Int,
        formatter: (Int) -> String,
        onChange: (Int) -> Unit
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        val row = rowBase().apply {
            isFocusable = false
            isFocusableInTouchMode = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dimen(R.dimen.settings_slider_row_height))
        }
        addLabel(row, label)
        val value = addValue(row, formatter(initial))
        container.addView(row)

        val slider = ValueSlider(this)
        slider.label = label
        slider.configure(min, max, step, initial)
        slider.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dimen(R.dimen.settings_slider_height)
        ).apply { bottomMargin = dp(2) }
        slider.onValueChanged = { v ->
            value.text = formatter(v)
            onChange(v)
        }
        container.addView(slider)
        settingsItems.add(container)
    }

    private fun buttonRow(label: String, value: String?, onClick: () -> Unit): TextView {
        val row = rowBase().apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        addLabel(row, label)
        val valueView = addValue(row, value ?: "›")
        row.setOnClickListener { onClick() }
        settingsItems.add(row)
        return valueView
    }
    private fun buildContent() {
        settingsItems.clear()

        section(getString(R.string.settings_section_general))
        switchRow(getString(R.string.settings_use_24h), { prefs.use24Hour }) { prefs.use24Hour = it }

        section(getString(R.string.settings_section_background))
        sliderRow(getString(R.string.settings_background_blur_short), 0, 25, 5, prefs.backgroundBlur, { "$it" }) {
            prefs.backgroundBlur = it
        }
        sliderRow(getString(R.string.settings_background_brightness_short), 0, 100, 10, prefs.backgroundBrightness, { "$it%" }) {
            prefs.backgroundBrightness = it
        }

        section(getString(R.string.settings_section_text))
        fontRow = buttonRow(getString(R.string.settings_font_label), AppFonts.labelOf(prefs.fontFamily)) { showFontList() }
        sliderRow(getString(R.string.settings_size_label), LauncherPrefs.FONT_SIZE_MIN, LauncherPrefs.FONT_SIZE_MAX, 1, prefs.fontSize, {
            String.format(java.util.Locale.US, "%.2f", it / 16f)
        }) { prefs.fontSize = it }

        section(getString(R.string.settings_section_apps))
        appsRow = buttonRow(
            getString(R.string.settings_apps_button),
            getString(R.string.settings_apps_button_count, prefs.selectedPackages.size)
        ) { showAppsList() }
        loadLauncherApps { apps ->
            appsRow?.text = getString(
                R.string.settings_apps_button_count,
                if (prefs.appsConfigured) prefs.selectedPackages.size else apps.size
            )
        }
        buttonRow(getString(R.string.settings_order_button), null) { showOrderList() }
        switchRow(getString(R.string.settings_show_tv_inputs), { prefs.showTvInputs }) { prefs.showTvInputs = it }

        section(getString(R.string.settings_section_tiles))
        sliderRow(getString(R.string.settings_tile_radius_label), 0, 20, 2, prefs.tileCornerRadius, { "$it dp" }) {
            prefs.tileCornerRadius = it
        }
        sliderRow(getString(R.string.settings_tile_scale_label), 70, 150, 10, prefs.tileScale, { "$it%" }) {
            prefs.tileScale = it
        }
        sliderRow(getString(R.string.settings_tile_gap_label), 0, 48, 5, prefs.tileGap, { "$it dp" }) {
            prefs.tileGap = it
        }
        sliderRow(getString(R.string.settings_visible_tiles), 3, 8, 1, prefs.visibleTiles, { "$it" }) {
            prefs.visibleTiles = it
        }

        section(getString(R.string.settings_section_animations))
        sliderRow(getString(R.string.settings_sleep_fade), 1, 5, 1, prefs.sleepFade, { "${it} с" }) {
            prefs.sleepFade = it
        }

        section(getString(R.string.settings_section_sleep))

        switchRow(getString(R.string.settings_sleep_mode_android), { prefs.sleepMode == "stock" }) { stock ->
            prefs.sleepMode = if (stock) "stock" else "app"
        }
        sliderRow(
            getString(R.string.settings_sleep_timeout_label),
            0,
            30,
            1,
            prefs.sleepTimeoutMinutes,
            { if (it == 0) getString(R.string.settings_sleep_off_option) else "$it мин" }
        ) {
            prefs.sleepTimeoutMinutes = it
            resetSleepTimer()
        }
        sliderRow(getString(R.string.settings_sleep_blur_label), 0, 25, 5, prefs.sleepBlur, { "$it" }) {
            prefs.sleepBlur = it
        }
        sliderRow(getString(R.string.settings_sleep_bright_label), 0, 100, 10, prefs.sleepBrightness, { "$it%" }) {
            prefs.sleepBrightness = it
        }
        switchRow(getString(R.string.settings_sleep_wallpaper_label), {
            prefs.sleepBackgroundMode == "wallpaper"
        }) { wallpaper ->
            prefs.sleepBackgroundMode = if (wallpaper) "wallpaper" else "gradient"
        }

        section(getString(R.string.settings_section_system))
        buttonRow(getString(R.string.settings_tv_settings), null) { openTvSettings() }
        buttonRow(getString(R.string.settings_display_settings), null) { openDisplaySettings() }
        buttonRow(getString(R.string.settings_about), versionName()) { showAbout() }

        binding.settingsContent.adapter?.notifyDataSetChanged()
    }

    data class PopupRow(val label: String, val checked: Boolean, val connected: Boolean = false)

    private fun showList(title: String, rows: List<PopupRow>, multi: Boolean, onApply: () -> Unit) {
        lastFocusedView = currentFocus
        popupVisible = true
        popupMulti = multi
        popupApply = onApply
        popupSelection = BooleanArray(rows.size) { rows[it].checked }
        popupColumns = 1
        popupMoveItem = null

        binding.popupTitle.text = title

        val list = binding.popupList
        list.removeAllViews()
        popupItems.clear()

        val marks = mutableListOf<TextView>()
        rows.forEachIndexed { index, item ->
            val row = rowBase().apply {
                isFocusable = true
                isFocusableInTouchMode = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dimen(R.dimen.settings_popup_row_height)
                ).apply { bottomMargin = dp(2) }
            }
            if (item.connected) {
                val dot = TextView(this).apply {
                    text = getString(R.string.settings_dot_connected)
                    setTextColor(0xFF35C759.toInt())
                    textSize = sp(R.dimen.settings_dot_text_size)
                    setPadding(0, 0, dp(6), 0)
                }
                row.addView(dot)
            }
            addLabel(row, item.label)
            val mark = addValue(row, if (popupSelection[index]) "✓" else "")
            mark.setTextColor(0xFF0072EE.toInt())
            marks.add(mark)

            row.setOnClickListener {
                if (multi) {
                    popupSelection[index] = !popupSelection[index]
                    mark.text = if (popupSelection[index]) "✓" else ""
                    popupApply?.invoke()
                } else {

                    for (i in popupSelection.indices) popupSelection[i] = (i == index)
                    marks.forEachIndexed { i, m -> m.text = if (i == index) "✓" else "" }
                    applyPopup()
                }
            }
            list.addView(row)
            popupItems.add(row)
        }

        val checkedIndex = rows.indexOfFirst { it.checked }
        popupFocusIndex = if (checkedIndex in rows.indices) checkedIndex else 0

        binding.settingsPopup.visibility = View.VISIBLE
        binding.settingsPopup.alpha = 0f
        Tween.alpha(binding.settingsPopup, 1f, 160L)
        binding.settingsContent.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        binding.settingsPopup.post { focusPopupItem(popupFocusIndex) }
    }

    private fun applyPopup() {
        val apply = popupApply
        popupApply = null
        apply?.invoke()
        hidePopup()
    }

    private fun hidePopup() {
        if (popupMulti && popupApply != null) {
            val apply = popupApply
            popupApply = null
            apply?.invoke()
        }
        popupVisible = false
        popupMulti = false
        popupMoveItem = null
        popupItems.clear()
        binding.settingsPopup.visibility = View.GONE
        binding.settingsContent.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        popupApply = null
        binding.root.post {
            val previous = lastFocusedView
            if (previous != null && previous.isAttachedToWindow) {
                previous.requestFocus()
            } else {
                focusFirst()
            }
        }
    }

    private fun showFontList() {
        val rows = fontLabels.map { PopupRow(it, it == AppFonts.labelOf(prefs.fontFamily)) }
        showList(getString(R.string.settings_font_dialog_title), rows, multi = false) {
            val index = popupSelection.indexOfFirst { it }
            if (index >= 0) {
                val option = AppFonts.options[index]
                prefs.fontFamily = option.key
                fontRow?.text = option.label
            }
        }
    }

    private fun showAppsList() {
        loadLauncherApps { apps -> showAppsList(apps) }
    }

    private fun showAppsList(apps: List<Pair<String, String>>) {
        if (apps.isEmpty()) return

        val checked = if (prefs.appsConfigured) prefs.selectedPackages else apps.map { it.first }.toSet()
        val rows = apps.map { PopupRow(it.second, checked.contains(it.first)) }
        showList(getString(R.string.settings_apps_dialog_title), rows, multi = true) {
            prefs.selectedPackages = apps.filterIndexed { index, _ -> popupSelection[index] }
                .map { it.first }
                .toSet()
            prefs.appsConfigured = true
            appsRow?.text = getString(R.string.settings_apps_button_count, prefs.selectedPackages.size)
        }
    }

    private fun showInputsList() {
        lifecycleScope.launch {
            val inputs = withContext(Dispatchers.IO) { TvInputs.list(this@SettingsActivity) }
            if (isActive) showInputsList(inputs)
        }
    }

    private fun showInputsList(inputs: List<TvInputEntry>) {
        if (inputs.isEmpty()) {
            Toast.makeText(this, getString(R.string.tv_inputs_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val rows = inputs.map { PopupRow(it.label, checked = false, connected = it.connected) }
        showList(getString(R.string.tv_inputs), rows, multi = false) {
            val index = popupSelection.indexOfFirst { it }
            if (index >= 0) {
                openInput(inputs[index].id)
            }
        }
    }

    private fun openInput(inputId: String) {
        if (launchChild(TvInputs.watchIntent(inputId))) return
        if (launchChild(
                Intent(this, TvInputActivity::class.java)
                    .putExtra(TvInputActivity.EXTRA_INPUT_ID, inputId)
            )
        ) return
        Toast.makeText(this, getString(R.string.tv_input_failed), Toast.LENGTH_LONG).show()
    }

    private fun loadLauncherApps(onReady: (List<Pair<String, String>>) -> Unit) {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { AppRepository.launcherPackages(this@SettingsActivity) }
            if (isActive) onReady(apps)
        }
    }

    private fun openTvSettings() {
        val tvSettings = Intent().setComponent(
            ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings")
        )
        if (launchChild(tvSettings)) return
        if (launchChild(Intent(Settings.ACTION_SETTINGS))) return
        Toast.makeText(this, getString(R.string.settings_unavailable), Toast.LENGTH_SHORT).show()
    }

    private fun openDisplaySettings() {
        if (launchChild(Intent(Settings.ACTION_DISPLAY_SETTINGS))) return
        openTvSettings()
    }

    private fun showAbout() {
        showHint(getString(R.string.about_message, versionName()))
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    private fun showOrderList() {
        loadLauncherApps { apps -> showOrderList(apps) }
    }

    private fun showOrderList(apps: List<Pair<String, String>>) {
        val selected = if (prefs.appsConfigured) prefs.selectedPackages else apps.map { it.first }.toSet()

        val labels = LinkedHashMap<String, String>()
        apps.filter { selected.contains(it.first) }.forEach { labels[it.first] = it.second }
        if (labels.isEmpty()) return

        val order = prefs.appOrder.filter { labels.containsKey(it) }.toMutableList()
        labels.keys.filter { !order.contains(it) }.forEach { order.add(it) }

        lastFocusedView = currentFocus
        popupVisible = true
        popupMulti = false
        popupColumns = 1
        binding.popupTitle.text = getString(R.string.settings_order_dialog)
        popupApply = null
        binding.settingsPopup.visibility = View.VISIBLE
        binding.settingsPopup.alpha = 0f
        Tween.alpha(binding.settingsPopup, 1f, 160L)
        binding.settingsContent.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        var focusIndex = 0

        val render = {
            val list = binding.popupList
            list.removeAllViews()
            popupItems.clear()
            order.forEachIndexed { index, key ->
                val row = rowBase().apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dimen(R.dimen.settings_popup_row_height)
                    ).apply { bottomMargin = dp(2) }
                }
                addLabel(row, "${index + 1}. ${labels[key] ?: key}")
                list.addView(row)
                popupItems.add(row)
            }
            popupFocusIndex = focusIndex.coerceIn(0, popupItems.lastIndex)
            list.post { focusPopupItem(popupFocusIndex) }
        }

        popupMoveItem = { delta ->
            val to = popupFocusIndex + delta
            if (popupFocusIndex < order.size && to in order.indices) {
                val tmp = order[to]
                order[to] = order[popupFocusIndex]
                order[popupFocusIndex] = tmp
                prefs.appOrder = order.toList()
                focusIndex = to
                render()
                true
            } else {
                false
            }
        }

        render()
    }

}
