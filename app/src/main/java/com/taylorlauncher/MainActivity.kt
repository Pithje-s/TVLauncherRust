package com.taylorlauncher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taylorlauncher.R
import com.taylorlauncher.core.AppFonts
import com.taylorlauncher.core.AppRepository
import com.taylorlauncher.core.BackgroundService
import com.taylorlauncher.core.IconLoader
import com.taylorlauncher.core.LauncherKeepAliveService
import com.taylorlauncher.core.LauncherPrefs
import com.taylorlauncher.core.Tween
import com.taylorlauncher.databinding.ActivityMainBinding
import com.taylorlauncher.model.LauncherItem
import com.taylorlauncher.ui.AllAppsOverlay
import com.taylorlauncher.ui.AppRowAdapter
import com.taylorlauncher.ui.HomeRoleController
import com.taylorlauncher.ui.HomeStatusController
import com.taylorlauncher.ui.LauncherItemPresenter
import com.taylorlauncher.ui.WallpaperController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var linksAdapter: AppRowAdapter
    private lateinit var itemPresenter: LauncherItemPresenter
    private lateinit var prefs: LauncherPrefs
    private lateinit var statusController: HomeStatusController
    private lateinit var wallpaperController: WallpaperController
    private lateinit var roleController: HomeRoleController

    private var sleepMode = false
    private val sleepHandler = Handler(Looper.getMainLooper())
    private val sleepRunnable = Runnable { enterSleep() }

    private val activeOverlays = mutableListOf<View>()

    private var overlayReturnFocus: View? = null

    private var lastTileFocus: View? = null

    private var lastCenterAt = 0L

    private var returnFocusView: View? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> sleepHandler.removeCallbacks(sleepRunnable)
                Intent.ACTION_SCREEN_ON -> {
                    if (sleepMode) exitSleep()
                    startSleepTimer()
                }
            }
        }
    }

    private val homeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS &&
                intent.getStringExtra("reason") == "homekey"
            ) {
                if (sleepMode) {
                    exitSleep()
                    startSleepTimer()
                }
                if (activeOverlays.isNotEmpty()) {
                    closeOverlays()
                    restoreLauncherFocus()
                }
            }
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_CHANGED,
                Intent.ACTION_PACKAGE_REPLACED,
                Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE,
                Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE -> scheduleAppsReload()

                Intent.ACTION_LOCALE_CHANGED -> {
                    scheduleAppsReload()
                    applyTypography()
                    statusController.refresh()
                }

                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_DATE_CHANGED -> statusController.refresh()
            }
        }
    }

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "show_tv_inputs" -> updateTvInputsButton()
            "apps_configured", "selected_packages", "app_order" ->
                scheduleAppsReload()
            "sleep_timeout" -> startSleepTimer()
            "sleep_mode" -> {
                if (prefs.sleepMode == "stock") {
                    startSleepTimer()
                    if (sleepMode) exitSleep()
                } else {
                    startSleepTimer()
                }
                updateKeepScreenOn()
            }
            "use_24h" -> statusController.refresh()
            "tile_radius", "tile_scale", "tile_gap", "visible_tiles", "font_size", "font_family", "font_weight", "text_shadow" -> updateTilesGeometry()
            "background_blur", "background_brightness", "sleep_blur", "sleep_brightness", "sleep_bg_mode" ->
                wallpaperController.applyEffects()
        }
    }

    private var appsLoadJob: Job? = null
    private var allAppsLoading = false
    private var installedApps: List<LauncherItem> = emptyList()
    private var allAppsOverlay: AllAppsOverlay? = null
    private var allAppsSignature: String? = null
    private val reloadHandler = Handler(Looper.getMainLooper())
    private val appsReload = Runnable { loadInstalledApps() }

    private fun scheduleAppsReload() {
        reloadHandler.removeCallbacks(appsReload)
        reloadHandler.postDelayed(appsReload, 150)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val initialWallpaper = WallpaperController.cachedBackground() ?: WallpaperController.diskBackground(this)
        initialWallpaper?.let {
            binding.backgroundImage.setImageBitmap(it)
            binding.backgroundBis.setImageBitmap(it)
        }
        prefs = LauncherPrefs(this)

        prefs.pendingSleep = false

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        statusController = HomeStatusController(this, binding, prefs)
        wallpaperController = WallpaperController(this, binding, prefs, { sleepMode }, initialWallpaper)
        roleController = HomeRoleController(
            activity = this,
            parent = binding.root as ViewGroup,
            prefs = prefs,
            hasOverlays = { activeOverlays.isNotEmpty() },
            currentFocus = { currentFocus },
            setReturnFocus = { overlayReturnFocus = it },
            onAttached = { activeOverlays.add(it) },
            onDetached = { activeOverlays.remove(it) },
            onDismiss = { restoreLauncherFocus() }
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        if (AppFonts.options.none { it.key == prefs.fontFamily }) {
            prefs.fontFamily = AppFonts.DEFAULT
        }
        prefs.registerListener(prefsListener)
        updateKeepScreenOn()
        ensureSystemAnimations()

        setupUI()
        applyTypography()
        statusController.refresh()
        binding.weatherCurrent.visibility = View.GONE
        binding.weatherForecast.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.Default) { BackgroundService.warmUp(this@MainActivity) }

        wallpaperController.load(initialWallpaper != null)
        loadInstalledApps()
        statusController.loadWeather()
        disableSoundEffects(binding.root)
        setupTilesCentering()
        binding.root.post { roleController.maybePrompt() }
        binding.root.post { if (!isFinishing) reportFullyDrawn() }
        LauncherKeepAliveService.start(this)
    }

    private fun disableSoundEffects(view: View) {
        view.isSoundEffectsEnabled = false
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                disableSoundEffects(view.getChildAt(i))
            }
        }
    }

    private fun updateKeepScreenOn() {
        if (prefs.sleepMode != "stock") {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun ensureSystemAnimations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("MainActivity", "WRITE_SECURE_SETTINGS not granted; app-level animations only")
            return
        }
        try {
            Settings.Global.putInt(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1)
            Settings.Global.putInt(contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1)
            Settings.Global.putInt(contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1)
        } catch (e: Exception) {
            Log.w("MainActivity", "Unable to enable system animations", e)
        }
    }

    private fun setupTilesCentering() {
        binding.linkblocks.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus != null && newFocus.parent === binding.linkblocks) {
                lastTileFocus = newFocus
                binding.linkblocks.post { centerChild(newFocus) }
            }
        }

        binding.linkblocks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateArrows()
        })
        binding.linkblocks.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateArrows() }
        binding.linkblocks.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.isSoundEffectsEnabled = false
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            }
        )
    }

    private fun updateTilesGeometry() {
        val recycler = binding.linkblocks
        recycler.itemAnimator = null

        applyTilesWindow()
        val count = linksAdapter.size()
        if (count > 0) {
            linksAdapter.notifyItemRangeChanged(0, count)
        }

        recycler.doOnNextLayout {
            val before = recycler.layoutParams.width
            applyTilesWindow()
            if (recycler.layoutParams.width != before) {
                recycler.doOnNextLayout { updateArrows() }
            } else {
                updateArrows()
            }
        }

        applyTypography()
        allAppsOverlay?.refreshItems()
    }

    private fun centerChild(child: View) {
        val recycler = binding.linkblocks

        if (recycler.width <= 0) return
        if (!::linksAdapter.isInitialized) return
        val pos = recycler.getChildAdapterPosition(child)
        if (pos < 0) return

        val density = resources.displayMetrics.density
        val tile = (resources.getDimensionPixelSize(R.dimen.tile_size) * prefs.tileScale / 100f).toInt()
        val pitch = tile + (prefs.tileGap * density).toInt()
        val visible = prefs.visibleTiles.coerceIn(3, 8)
        val count = linksAdapter.size()
        val lm = recycler.layoutManager as? LinearLayoutManager
        val currentFirst = lm?.findFirstVisibleItemPosition() ?: 0
        var targetFirst = currentFirst
        if (pos > currentFirst + visible - 1) targetFirst = pos - visible + 1
        if (pos < currentFirst) targetFirst = pos
        targetFirst = targetFirst.coerceIn(0, (count - visible).coerceAtLeast(0))

        val dx = child.left - (rowPadPx() + (pos - targetFirst) * pitch)
        if (dx != 0) {
            val now = SystemClock.elapsedRealtime()
            val rapid = now - lastCenterAt < 250L
            lastCenterAt = now
            recycler.stopScroll()
            if (rapid) recycler.scrollBy(dx, 0) else recycler.smoothScrollBy(dx, 0)
        }
    }

    private fun focusTile(position: Int) {
        val recycler = binding.linkblocks
        val holder = recycler.findViewHolderForAdapterPosition(position)
        if (holder != null) {
            holder.itemView.requestFocus()
            return
        }

        recycler.scrollToPosition(position)
        recycler.doOnNextLayout {
            recycler.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()

        if (sleepMode && isScreenInteractive()) {
            exitSleep()
            startSleepTimer()
        }

        updateKeepScreenOn()
        ensureSystemAnimations()

        if (prefs.sleepMode == "stock" && sleepMode) exitSleep()

        applyTypography()
        wallpaperController.applyEffects()
        wallpaperController.retryFallback()
        startSleepTimer()
        if (prefs.pendingSleep) {
            prefs.pendingSleep = false
            binding.launcherInterface.alpha = 1f
            binding.settingsButtonContainer.alpha = 1f
            enterSleep()
        }
        updateTvInputsButton()

        if (returnFocusView != null) {
            binding.linkblocks.post { focusPendingReturnView() }
            return
        }

        if (activeOverlays.isNotEmpty()) return

        binding.linkblocks.post {
            if (linksAdapter.size() == 0) {
                binding.settingsButtonContainer.requestFocus()
                return@post
            }
            val target = lastTileFocus?.takeIf { it.isAttachedToWindow } ?: binding.linkblocks.getChildAt(0)
            if (target != null) {
                target.requestFocus()
                centerChild(target)
                growFocusedTile()
            } else {
                binding.linkblocks.requestFocus()
            }
            binding.linkblocks.postDelayed({ updateArrows() }, 100)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) focusPendingReturnView()
    }

    private fun focusPendingReturnView() {
        val view = returnFocusView ?: return
        view.post {
            if (returnFocusView === view && view.requestFocus()) returnFocusView = null
        }
    }

    private fun growFocusedTile() {
        val focus = currentFocus ?: return
        if (focus.parent !== binding.linkblocks) return
        val tile = focus.findViewById<View>(R.id.tile_frame) ?: return
        Tween.scale(tile, 1.05f, 150L)
    }

    override fun onStart() {
        super.onStart()
        registerReceivers()
        statusController.start()
    }

    override fun onStop() {
        statusController.stop()
        try {
            unregisterReceiver(packageReceiver)
        } catch (_: IllegalArgumentException) {
        }
        try {
            unregisterReceiver(homeReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onStop()
    }

    private fun registerReceivers() {
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
            addDataScheme("package")
        }
        val systemFilter = IntentFilter().apply {
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, packageReceiver, packageFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, packageReceiver, systemFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            this,
            homeReceiver,
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (sleepMode) {
            exitSleep()
            startSleepTimer()
        }
        if (activeOverlays.isNotEmpty()) {
            closeOverlays()
            restoreLauncherFocus()
        }
    }

    override fun onPause() {
        sleepHandler.removeCallbacks(sleepRunnable)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (sleepMode) {
                val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                    event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                if (!isVolumeKey) {
                    exitSleep()
                    startSleepTimer()
                    return true
                }
                startSleepTimer()
                return super.dispatchKeyEvent(event)
            }

            if (event.keyCode == KeyEvent.KEYCODE_HOME) {
                if (activeOverlays.isNotEmpty()) {
                    closeOverlays()
                    restoreLauncherFocus()
                }
                return true
            }

            if (::binding.isInitialized && ::linksAdapter.isInitialized && linksAdapter.size() == 0) {
                val focus = currentFocus
                val onButton = focus === binding.tvInputsButton ||
                    focus === binding.allAppsButton ||
                    focus === binding.settingsButtonContainer
                if (onButton && (event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                        event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                ) {
                    return true
                }
            }

            if (::binding.isInitialized) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    val firstButton =
                        if (binding.tvInputsButton.visibility == View.VISIBLE) binding.tvInputsButton
                        else binding.allAppsButton
                    if (currentFocus === firstButton) return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                    currentFocus === binding.settingsButtonContainer
                ) {
                    return true
                }
            }
            startSleepTimer()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            if (sleepMode) {
                exitSleep()
                startSleepTimer()
                return true
            }
            startSleepTimer()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        statusController.destroy()
        reloadHandler.removeCallbacks(appsReload)
        appsLoadJob?.cancel()
        wallpaperController.cancel()
        if (::prefs.isInitialized) {
            prefs.unregisterListener(prefsListener)
        }
        Tween.cancelAll()
        allAppsOverlay?.release()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            wallpaperController.trimMemory()
            IconLoader.trim()
        }
    }

    private fun applyTypography() {
        val family = prefs.fontFamily.ifBlank { AppFonts.DEFAULT }

        val typeface: Typeface = try {
            Typeface.create(AppFonts.typeface(this, family), prefs.fontWeight)
        } catch (e: Exception) {
            Typeface.DEFAULT
        }

        val scale = prefs.fontSize / 16f
        val alpha = (prefs.textShadow * 255 / 100).coerceIn(0, 255)
        val shadowColor = alpha shl 24

        applyText(binding.bigClock, typeface, R.dimen.clock_text_size, scale, shadowColor)
        applyText(binding.date, typeface, R.dimen.date_text_size, scale, shadowColor)
        applyText(binding.greetings, typeface, R.dimen.greeting_text_size, scale, shadowColor)
        applyText(binding.weatherCurrent, typeface, R.dimen.weather_text_size, scale, shadowColor)
    }

    private fun applyText(view: TextView, typeface: Typeface, dimen: Int, scale: Float, shadowColor: Int) {
        view.typeface = typeface
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(dimen) * scale)
        if (Color.alpha(shadowColor) > 0) {
            view.setShadowLayer(7f, 0.5f, 1f, shadowColor)
        } else {
            view.setShadowLayer(0f, 0f, 0f, 0)
        }
    }

    private fun startSleepTimer() {
        sleepHandler.removeCallbacks(sleepRunnable)

        if (prefs.sleepMode == "stock") return
        val minutes = prefs.sleepTimeoutMinutes
        if (minutes > 0) {
            sleepHandler.postDelayed(sleepRunnable, minutes * 60_000L)
        }
    }

    private fun enterSleep() {
        if (sleepMode) return
        sleepMode = true
        val fadeMs = fadeDurationMs()

        closeOverlays(fadeMs)
        animateInterface(0f, fadeMs)
        wallpaperController.updateBackgroundImage(smooth = true, durationMs = fadeMs)
    }

    private fun closeOverlays(animateMs: Long = 0L) {
        val list = activeOverlays.toList()
        activeOverlays.clear()
        list.forEach { overlay ->
            if (overlay.parent == null) return@forEach
            if (animateMs > 0) {
                Tween.alpha(overlay, 0f, animateMs, onEnd = {
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                })
            } else {
                (overlay.parent as? ViewGroup)?.removeView(overlay)
            }
        }

        binding.launcherInterface.alpha = 1f
    }

    private fun exitSleep() {
        if (!sleepMode) return
        sleepMode = false
        val fadeMs = fadeDurationMs()
        animateInterface(1f, fadeMs)
        wallpaperController.updateBackgroundImage(smooth = true, durationMs = fadeMs)

        restoreLauncherFocus()
    }

    private fun restoreLauncherFocus() {
        val target = overlayReturnFocus
        overlayReturnFocus = null
        binding.linkblocks.post {
            if (target != null && target.isAttachedToWindow && target.requestFocus()) return@post
            val child = binding.linkblocks.getChildAt(0)
            if (child != null) child.requestFocus() else binding.linkblocks.requestFocus()
            updateArrows()
        }
    }

    private fun isScreenInteractive(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isInteractive == true

    private fun fadeDurationMs(): Long = prefs.sleepFade * 1000L

    private fun animateInterface(target: Float, durationMs: Long) {
        val brightness = if (target > 0f) prefs.backgroundBrightness else prefs.sleepBrightness
        val dimTarget = ((100 - brightness) / 100f).coerceIn(0f, 0.9f)
        val overlay = binding.sleepBackground
        val overlayWanted = target <= 0f && prefs.sleepBackgroundMode == "gradient"
        if (overlayWanted) overlay.visibility = View.VISIBLE

        val uiFrom = binding.launcherInterface.alpha
        val dimFrom = binding.backgroundDim.alpha
        val overlayFrom = if (overlay.visibility == View.VISIBLE) overlay.alpha else 0f
        val overlayTo = if (overlayWanted) 1f else 0f
        val hideOverlay = !overlayWanted && overlay.visibility == View.VISIBLE

        Tween.run(
            this,
            durationMs,
            update = { t ->

                val e = t * t * (3f - 2f * t)
                val ui = uiFrom + (target - uiFrom) * e
                binding.launcherInterface.alpha = ui
                binding.settingsButtonContainer.alpha = ui
                binding.backgroundDim.alpha = dimFrom + (dimTarget - dimFrom) * e
                if (overlay.visibility == View.VISIBLE) {
                    overlay.alpha = overlayFrom + (overlayTo - overlayFrom) * e
                }
            },
            onEnd = {
                binding.launcherInterface.alpha = target
                binding.settingsButtonContainer.alpha = target
                binding.backgroundDim.alpha = dimTarget
                if (overlay.visibility == View.VISIBLE) overlay.alpha = overlayTo
                if (hideOverlay) overlay.visibility = View.GONE

                if (target > 0f) updateArrows()
            }
        )
    }

    private fun setupUI() {
        itemPresenter = LauncherItemPresenter(prefs)
        linksAdapter = AppRowAdapter(itemPresenter) { item -> handleItemClick(item) }

        binding.linkblocks.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.linkblocks.adapter = linksAdapter
        binding.linkblocks.post { updateArrows() }

        binding.linkblocks.setItemViewCacheSize(24)

        binding.linkblocks.itemAnimator = null

        binding.linkblocks.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                if (parent.getChildAdapterPosition(view) > 0) {
                    outRect.left = (prefs.tileGap * resources.displayMetrics.density).toInt()
                }
            }
        })

        binding.linkblocks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateArrows()
            }
        })

        binding.linkblocks.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val count = linksAdapter.size()
            if (count == 0) return@setOnKeyListener false
            val index = binding.linkblocks.findFocus()
                ?.let { binding.linkblocks.getChildAdapterPosition(it) } ?: -1
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT ->
                    if (index <= 0) { focusTile(count - 1); true } else false
                KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (index < 0 || index == count - 1) { focusTile(0); true } else false
                else -> false
            }
        }

        setupQuickButton(binding.tvInputsButton) { openTvInputs() }
        setupQuickButton(binding.allAppsButton) { showAllAppsOverlay() }
        setupQuickButton(binding.settingsButtonContainer) { openSettings() }
        updateTvInputsButton()
    }

    private fun setupQuickButton(view: View, onClick: () -> Unit) {
        view.setOnClickListener { onClick() }
        view.setOnFocusChangeListener { v, hasFocus ->
            v.background = if (hasFocus) {
                ContextCompat.getDrawable(this, R.drawable.bg_settings_focus)
            } else {
                ContextCompat.getDrawable(this, R.drawable.bg_settings_normal)
            }
            Tween.scale(v, if (hasFocus) 1.15f else 1f, 150L)
        }
    }

    private fun updateTvInputsButton() {
        if (::binding.isInitialized) {
            binding.tvInputsButton.visibility = if (prefs.showTvInputs) View.VISIBLE else View.GONE
        }
    }

    private fun centerLinkblocks(focusFirst: Boolean = true) {

        applyTilesWindow()
        binding.linkblocks.doOnNextLayout {
            updateArrows()
            if (!focusFirst) return@doOnNextLayout

            val lm = binding.linkblocks.layoutManager as? LinearLayoutManager
            val firstPos = lm?.findFirstVisibleItemPosition() ?: 0
            if (firstPos == 0) {
                binding.linkblocks.getChildAt(0)?.requestFocus()
            }
        }
    }

    private fun applyTilesWindow() {
        val recycler = binding.linkblocks
        val count = linksAdapter.size()
        val density = resources.displayMetrics.density
        val tile = (resources.getDimensionPixelSize(R.dimen.tile_size) * prefs.tileScale / 100f).toInt()
        val gap = (prefs.tileGap * density).toInt()
        val visible = prefs.visibleTiles.coerceIn(3, 8)

        val window = visible * tile + (visible - 1) * gap
        val content = if (count > 0) count * tile + (count - 1) * gap else window
        var width = minOf(content, window)

        val parentWidth = (recycler.parent as? View)?.width ?: 0
        if (parentWidth > 0) width = minOf(width, parentWidth)
        width = width.coerceAtLeast(1)

        val lp = recycler.layoutParams

        val pad = rowPadPx()
        val padV = (3 * resources.displayMetrics.density).toInt()
        if (recycler.paddingLeft != pad || recycler.paddingRight != pad ||
            recycler.paddingTop != padV || recycler.paddingBottom != padV
        ) {
            recycler.setPaddingRelative(pad, padV, pad, padV)
        }
        val fullWidth = width + 2 * pad
        if (lp.width != fullWidth) {
            lp.width = fullWidth
            recycler.layoutParams = lp

        }
    }

    private fun rowPadPx(): Int {
        val density = resources.displayMetrics.density
        return (prefs.tileGap * density / 2f).toInt() + (3 * density).toInt()
    }

    private fun updateArrows() {
        if (!::linksAdapter.isInitialized) return

        if (sleepMode) {
            binding.arrowLeft.alpha = 0f
            binding.arrowRight.alpha = 0f
            return
        }
        val recycler = binding.linkblocks

        if (binding.arrowLeft.height == 0) {
            binding.arrowLeft.post { updateArrows() }
            return
        }
        val count = linksAdapter.size()
        val lm = recycler.layoutManager as? LinearLayoutManager

        val firstPos = lm?.findFirstVisibleItemPosition() ?: 0
        val lastPos = lm?.findLastVisibleItemPosition() ?: (count - 1)
        val firstChild = lm?.findViewByPosition(firstPos)
        val lastChild = lm?.findViewByPosition(lastPos)

        val pad = rowPadPx()
        val contentRight = recycler.width - pad

        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i)
            val overlap = minOf(child.right, contentRight) - maxOf(child.left, pad)
            val a = if (child.hasFocus() || overlap * 2 >= child.width) 1f else 0f
            if (child.alpha != a) child.alpha = a
        }

        val canLeft = firstPos > 0 || (firstChild != null && firstChild.left < pad)
        val canRight = lastPos < count - 1 || (lastChild != null && lastChild.right > contentRight)

        val tileFrame = recycler.getChildAt(0)?.findViewById<View>(R.id.tile_frame)
        if (tileFrame != null && binding.arrowLeft.height > 0) {
            val tileLocation = IntArray(2)
            val arrowLocation = IntArray(2)
            tileFrame.getLocationOnScreen(tileLocation)
            binding.arrowLeft.getLocationOnScreen(arrowLocation)
            val iconCenterY = tileLocation[1] + tileFrame.height / 2f
            val arrowCenterY = arrowLocation[1] + binding.arrowLeft.height / 2f
            val delta = iconCenterY - arrowCenterY
            binding.arrowLeft.translationY += delta
            binding.arrowRight.translationY += delta
        }

        val arrowSize = binding.arrowLeft.width.toFloat().takeIf { it > 0f }
            ?: (20 * resources.displayMetrics.density)
        val gap = 4 * resources.displayMetrics.density
        val parentWidth = (recycler.parent as? View)?.width ?: recycler.width

        val rowLeft = (parentWidth - recycler.width) / 2f + pad
        binding.arrowLeft.translationX = rowLeft - arrowSize - gap
        binding.arrowRight.translationX = rowLeft + (recycler.width - 2 * pad) + gap

        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val showLeft = if (rtl) canRight else canLeft
        val showRight = if (rtl) canLeft else canRight
        binding.arrowLeft.alpha = if (showLeft) 0.85f else 0f
        binding.arrowRight.alpha = if (showRight) 0.85f else 0f
        binding.arrowLeft.visibility = if (showLeft) View.VISIBLE else View.INVISIBLE
        binding.arrowRight.visibility = if (showRight) View.VISIBLE else View.INVISIBLE
    }

    private fun openSettings() {
        returnFocusView = binding.settingsButtonContainer
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun handleItemClick(item: LauncherItem) {
        val intent = item.intent
            ?: item.packageName?.let { pkg ->
                packageManager.getLeanbackLaunchIntentForPackage(pkg)
                    ?: packageManager.getLaunchIntentForPackage(pkg)
            }
        if (intent != null) {
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "launch failed: ${item.title}", e)
                Toast.makeText(this, getString(R.string.toast_launch_failed, item.title), Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.toast_not_installed, item.title), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openTvInputs() {
        returnFocusView = binding.tvInputsButton
        startActivity(Intent(this, TvInputsActivity::class.java))
    }

    private fun showAllAppsOverlay() {

        val apps = installedApps.mapNotNull { item -> item.packageName?.let { it to item.title } }
        if (apps.isNotEmpty()) {
            openAllApps(apps)
            return
        }
        if (allAppsLoading) return
        allAppsLoading = true
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { AppRepository.launcherPackages(this@MainActivity) }
            allAppsLoading = false
            if (!isActive || loaded.isEmpty()) return@launch
            openAllApps(loaded)
        }
    }

    private fun openAllApps(apps: List<Pair<String, String>>) {
        ensureAllAppsPrepared(apps)
        if (activeOverlays.isEmpty()) overlayReturnFocus = currentFocus
        allAppsOverlay?.show(apps)
    }

    private fun ensureAllAppsPrepared(apps: List<Pair<String, String>>) {
        val signature = apps.joinToString("|") { "${it.first}:${it.second}" }
        if (allAppsOverlay != null && signature == allAppsSignature) return
        allAppsOverlay?.release()
        allAppsSignature = signature
        allAppsOverlay = createAllAppsOverlay().also { it.prepare(apps) }
    }

    private fun prepareAllAppsOverlay() {
        if (activeOverlays.isNotEmpty()) {
            lifecycleScope.launch {
                delay(500)
                prepareAllAppsOverlay()
            }
            return
        }
        val apps = installedApps.mapNotNull { item -> item.packageName?.let { it to item.title } }
        if (apps.isNotEmpty()) ensureAllAppsPrepared(apps)
    }

    private fun createAllAppsOverlay(): AllAppsOverlay = AllAppsOverlay(
        activity = this,
        parent = binding.root as ViewGroup,
        itemPresenter = itemPresenter,
        prefs = prefs,
        onLaunch = { app -> launchAllAppsEntry(app) },
        onAttached = { activeOverlays.add(it) },
        onDetached = { activeOverlays.remove(it) },
        onDismiss = { restoreLauncherFocus() }
    )

    private fun launchAllAppsEntry(app: Pair<String, String>) {
        val launch = packageManager.getLeanbackLaunchIntentForPackage(app.first)
            ?: packageManager.getLaunchIntentForPackage(app.first)
        if (launch == null) return
        try {
            startActivity(launch)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.toast_launch_failed, app.second),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun loadInstalledApps() {
        applyTilesWindow()

        if (linksAdapter.size() == 0) restoreCachedTiles()

        val hadTiles = linksAdapter.size() > 0
        binding.emptyApps.visibility = View.GONE

        appsLoadJob?.cancel()
        appsLoadJob = lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { queryLauncherApps() }
            if (!isActive) return@launch
            installedApps = items

            lifecycleScope.launch {
                delay(800)
                prepareAllAppsOverlay()
            }

            val selected = prefs.selectedPackages
            val visible = if (!prefs.appsConfigured) items else items.filter { selected.contains(it.packageName) }

            val tiles = visible.toMutableList()
            val order = prefs.appOrder
            if (order.isNotEmpty()) {
                val base = tiles.map { it }
                tiles.sortBy { item ->
                    val index = order.indexOf(orderKeyOf(item))
                    if (index >= 0) index else order.size + base.indexOf(item)
                }
            }

            if (tiles.isEmpty()) {
                prefs.cachedTiles = ""
                withContext(Dispatchers.Main) {
                    if (!isActive) return@withContext
                    linksAdapter.clear()
                    binding.emptyApps.visibility = View.VISIBLE
                    binding.linkblocks.visibility = View.GONE
                    binding.arrowLeft.alpha = 0f
                    binding.arrowRight.alpha = 0f
                    binding.settingsButtonContainer.post { binding.settingsButtonContainer.requestFocus() }
                }
                return@launch
            }
            prefs.cachedTiles = tilesToJson(tiles)

            if (hadTiles) {

                withContext(Dispatchers.Main) {
                    if (!isActive) return@withContext
                    linksAdapter.submit(tiles)
                    binding.emptyApps.visibility = View.GONE
                    binding.linkblocks.visibility = View.VISIBLE
                    centerLinkblocks(focusFirst = false)
                }
                return@launch
            }

            val shown = tiles.take(prefs.visibleTiles.coerceIn(3, 8))
            withContext(Dispatchers.IO) {
                IconLoader.preloadPackageIcons(this@MainActivity, shown.mapNotNull { it.packageName })
            }
            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext
                linksAdapter.submit(shown)
                binding.emptyApps.visibility = View.GONE
                binding.linkblocks.visibility = View.VISIBLE
                binding.linkblocks.alpha = 0f
                centerLinkblocks(focusFirst = !hadTiles)
                Tween.alpha(binding.linkblocks, 1f, 250L)
                binding.linkblocks.postDelayed({ updateArrows() }, 100)
            }

            if (tiles.size <= shown.size) return@launch

            if (!isActive) return@launch
            val rest = tiles.drop(shown.size)
            withContext(Dispatchers.IO) {
                IconLoader.preloadPackageIcons(this@MainActivity, rest.mapNotNull { it.packageName })
            }
            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext
                linksAdapter.append(rest)
                updateArrows()
            }
        }
    }

    private fun queryLauncherApps(): List<LauncherItem> =
        AppRepository.launcherItems(this)

    private fun restoreCachedTiles() {
        val cached = prefs.cachedTiles
        if (cached.isBlank()) return
        val items = try {
            val array = JSONArray(cached)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val pkg = obj.optString("p")
                val title = obj.optString("t")
                if (pkg.isBlank() || title.isBlank()) null else LauncherItem(title, null, pkg)
            }
        } catch (_: Throwable) {
            emptyList()
        }
        if (items.isEmpty()) return
        linksAdapter.submit(items)
        binding.emptyApps.visibility = View.GONE
        binding.linkblocks.visibility = View.VISIBLE
        centerLinkblocks(focusFirst = true)
        updateArrows()
        lifecycleScope.launch(Dispatchers.IO) {
            IconLoader.preloadPackageIcons(this@MainActivity, items.mapNotNull { it.packageName })
        }
    }

    private fun tilesToJson(tiles: List<LauncherItem>): String {
        val array = JSONArray()
        tiles.forEach {
            array.put(JSONObject().put("p", it.packageName ?: "").put("t", it.title))
        }
        return array.toString()
    }

    private fun orderKeyOf(item: LauncherItem): String = item.packageName ?: item.title

}
