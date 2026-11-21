package com.taylorlauncher.core

import android.content.Context
import android.content.SharedPreferences

class LauncherPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    init {

        if (!prefs.getBoolean(KEY_SLEEP_BRIGHT_MIGRATED, false)) {
            if (prefs.getInt(KEY_SLEEP_BRIGHTNESS, 20) == 100) {
                prefs.edit().putInt(KEY_SLEEP_BRIGHTNESS, 20).apply()
            }
            prefs.edit().putBoolean(KEY_SLEEP_BRIGHT_MIGRATED, true).apply()
        }

        if (!prefs.getBoolean(KEY_DEFAULTS_V2, false)) {
            prefs.edit()
                .putInt(KEY_TILE_SCALE, 100)
                .putInt(KEY_FONT_SIZE, 16)
                .putInt(KEY_SLEEP_FADE, 3)
                .remove("stop_unused_apps")
                .remove("stop_unused_minutes")
                .putBoolean(KEY_DEFAULTS_V2, true)
                .apply()
        }
    }

    var selectedPackages: Set<String>
        get() = prefs.getStringSet(KEY_PACKAGES, DEFAULT_PACKAGES) ?: DEFAULT_PACKAGES
        set(value) = prefs.edit().putStringSet(KEY_PACKAGES, value).apply()

    var appsConfigured: Boolean
        get() = prefs.getBoolean(KEY_APPS_CONFIGURED, false)
        set(value) = prefs.edit().putBoolean(KEY_APPS_CONFIGURED, value).apply()

    var showTvInputs: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TV_INPUTS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TV_INPUTS, value).apply()

    var use24Hour: Boolean
        get() = prefs.getBoolean(KEY_24H, true)
        set(value) = prefs.edit().putBoolean(KEY_24H, value).apply()

    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, DEFAULT_FONT_FAMILY) ?: DEFAULT_FONT_FAMILY
        set(value) = prefs.edit().putString(KEY_FONT_FAMILY, value).apply()

    var fontWeight: Int
        get() = prefs.getInt(KEY_FONT_WEIGHT, 300)
        set(value) = prefs.edit().putInt(KEY_FONT_WEIGHT, value).apply()

    var fontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 16).coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX)
        set(value) = prefs.edit().putInt(KEY_FONT_SIZE, value.coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX)).apply()

    var textShadow: Int
        get() = prefs.getInt(KEY_TEXT_SHADOW, 60)
        set(value) = prefs.edit().putInt(KEY_TEXT_SHADOW, value).apply()

    var tileCornerRadius: Int
        get() = prefs.getInt(KEY_TILE_RADIUS, 20)
        set(value) = prefs.edit().putInt(KEY_TILE_RADIUS, value).apply()

    var tileScale: Int
        get() = prefs.getInt(KEY_TILE_SCALE, 100)
        set(value) = prefs.edit().putInt(KEY_TILE_SCALE, value).apply()

    var tileGap: Int
        get() = prefs.getInt(KEY_TILE_GAP, 30)
        set(value) = prefs.edit().putInt(KEY_TILE_GAP, value).apply()

    var sleepTimeoutMinutes: Int
        get() = prefs.getInt(KEY_SLEEP_TIMEOUT, 15)
        set(value) = prefs.edit().putInt(KEY_SLEEP_TIMEOUT, value).apply()

    var sleepBlur: Int
        get() = prefs.getInt(KEY_SLEEP_BLUR, 0).coerceIn(0, 25)
        set(value) = prefs.edit().putInt(KEY_SLEEP_BLUR, value).apply()

    var sleepBrightness: Int
        get() = prefs.getInt(KEY_SLEEP_BRIGHTNESS, 20)
        set(value) = prefs.edit().putInt(KEY_SLEEP_BRIGHTNESS, value).apply()

    var sleepBackgroundMode: String
        get() = prefs.getString(KEY_SLEEP_BG_MODE, "wallpaper") ?: "wallpaper"
        set(value) = prefs.edit().putString(KEY_SLEEP_BG_MODE, value).apply()

    var sleepMode: String
        get() = prefs.getString(KEY_SLEEP_MODE, "app") ?: "app"
        set(value) = prefs.edit().putString(KEY_SLEEP_MODE, value).apply()

    var homePromptShown: Boolean
        get() = prefs.getBoolean(KEY_HOME_PROMPT, false)
        set(value) = prefs.edit().putBoolean(KEY_HOME_PROMPT, value).apply()

    var visibleTiles: Int
        get() = prefs.getInt(KEY_VISIBLE_TILES, 5)
        set(value) = prefs.edit().putInt(KEY_VISIBLE_TILES, value.coerceIn(3, 8)).apply()

    var appOrder: List<String>
        get() = (prefs.getString(KEY_APP_ORDER, DEFAULT_ORDER) ?: DEFAULT_ORDER)
            .split("|")
            .filter { it.isNotBlank() && !it.startsWith("spec:") }
        set(value) = prefs.edit().putString(KEY_APP_ORDER, value.joinToString("|")).apply()

    var sleepFade: Int
        get() = prefs.getInt(KEY_SLEEP_FADE, 3).coerceIn(1, 5)
        set(value) = prefs.edit().putInt(KEY_SLEEP_FADE, value.coerceIn(1, 5)).apply()

    var seenWallpapers: Set<String>
        get() = (prefs.getString(KEY_SEEN_WALLPAPERS, "") ?: "")
            .split("|")
            .filter { it.isNotBlank() }
            .toSet()
        set(value) = prefs.edit().putString(KEY_SEEN_WALLPAPERS, value.joinToString("|")).apply()

    var lastWallpaper: String
        get() = prefs.getString(KEY_LAST_WALLPAPER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_WALLPAPER, value).apply()

    var cachedTiles: String
        get() = prefs.getString(KEY_CACHED_TILES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CACHED_TILES, value).apply()

    var wallpaperElapsed: Long
        get() = prefs.getLong(KEY_WALLPAPER_ELAPSED, 0L)
        set(value) = prefs.edit().putLong(KEY_WALLPAPER_ELAPSED, value).apply()

    var backgroundCollection: String
        get() = prefs.getString(KEY_BG_COLLECTION, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_BG_COLLECTION, value).apply()

    var backgroundBlur: Int
        get() = prefs.getInt(KEY_BG_BLUR, 15).coerceIn(0, 25)
        set(value) = prefs.edit().putInt(KEY_BG_BLUR, value).apply()

    var backgroundBrightness: Int
        get() = prefs.getInt(KEY_BG_BRIGHTNESS, 50)
        set(value) = prefs.edit().putInt(KEY_BG_BRIGHTNESS, value).apply()

    var pendingSleep: Boolean
        get() = prefs.getBoolean(KEY_PENDING_SLEEP, false)
        set(value) = prefs.edit().putBoolean(KEY_PENDING_SLEEP, value).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        private const val NAME = "taylor_launcher_settings"

        const val FONT_SIZE_MIN = 13
        const val FONT_SIZE_MAX = 22

        private const val DEFAULT_FONT_FAMILY = "proxima_nova"
        private const val DEFAULT_ORDER =
            "com.google.android.youtube.tv|com.seena.app|com.yandex.browser.tv"

        private val DEFAULT_PACKAGES = emptySet<String>()

        val HIDDEN_PACKAGES = setOf(
            "com.spocky.projengmenu",
            "org.smarttube.stable",
            "com.mediatek.tv.service",
            "com.android.tv.settings",
            "com.taylorlauncher"
        )

        private const val KEY_PACKAGES = "selected_packages"
        private const val KEY_APPS_CONFIGURED = "apps_configured"
        private const val KEY_SHOW_TV_INPUTS = "show_tv_inputs"
        private const val KEY_24H = "use_24h"
        private const val KEY_BG_COLLECTION = "background_collection"
        private const val KEY_BG_BLUR = "background_blur"
        private const val KEY_BG_BRIGHTNESS = "background_brightness"
        private const val KEY_PENDING_SLEEP = "pending_sleep"
        private const val KEY_VISIBLE_TILES = "visible_tiles"
        private const val KEY_APP_ORDER = "app_order"
        private const val KEY_SLEEP_FADE = "sleep_fade"
        private const val KEY_SEEN_WALLPAPERS = "seen_wallpapers"
        private const val KEY_LAST_WALLPAPER = "last_wallpaper"
        private const val KEY_CACHED_TILES = "cached_tiles"
        private const val KEY_WALLPAPER_ELAPSED = "wallpaper_elapsed"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_FONT_WEIGHT = "font_weight"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_TEXT_SHADOW = "text_shadow"
        private const val KEY_SLEEP_TIMEOUT = "sleep_timeout"
        private const val KEY_SLEEP_BLUR = "sleep_blur"
        private const val KEY_SLEEP_BRIGHTNESS = "sleep_brightness"
        private const val KEY_SLEEP_BRIGHT_MIGRATED = "sleep_brightness_default_migrated"
        private const val KEY_DEFAULTS_V2 = "defaults_v2_applied"
        private const val KEY_SLEEP_BG_MODE = "sleep_bg_mode"
        private const val KEY_SLEEP_MODE = "sleep_mode"
        private const val KEY_HOME_PROMPT = "home_prompt_shown"
        private const val KEY_TILE_RADIUS = "tile_radius"
        private const val KEY_TILE_SCALE = "tile_scale"
        private const val KEY_TILE_GAP = "tile_gap"
    }
}
