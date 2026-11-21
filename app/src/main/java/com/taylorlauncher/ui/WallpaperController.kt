package com.taylorlauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.system.Os
import android.util.Log
import android.util.LruCache
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.taylorlauncher.R
import com.taylorlauncher.core.BackgroundService
import com.taylorlauncher.core.LauncherPrefs
import com.taylorlauncher.core.Tween
import com.taylorlauncher.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

class WallpaperController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val prefs: LauncherPrefs,
    private val isSleeping: () -> Boolean,
    initialDisplay: Bitmap? = null
) {

    private data class Wallpaper(val key: String, val resId: Int, val file: File?)

    private var backgroundBitmap: Bitmap? = null
    private var firstShown = true
    private var backgroundLoadJob: Job? = null
    private var blurJob: Job? = null
    private var lastFallbackRetry = 0L
    private val blurredCache = LruCache<Int, Bitmap>(4)
    private var shownBackground: Bitmap? = null

    init {

        shownBackground = cachedDisplay ?: initialDisplay
        if (shownBackground != null) firstShown = false
        if (initialDisplay != null) restoreOriginal(initialDisplay)
    }

    private fun restoreOriginal(initialDisplay: Bitmap) {
        val key = prefs.lastWallpaper
        if (key.isBlank()) return
        activity.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeWallpaperKey(key, wallpaperWidth()) }
                ?: return@launch
            if (backgroundBitmap != null || shownBackground !== initialDisplay) return@launch
            backgroundBitmap = bitmap
            applyEffects()
        }
    }

    private fun decodeWallpaperKey(key: String, width: Int): Bitmap? {
        if (key.startsWith("file:")) {
            val file = File(activity.filesDir, "backgrounds/${key.removePrefix("file:")}")
            if (file.exists() && file.length() > 50_000) {
                return BackgroundService.decode(file, width)
            }
            return null
        }
        return decodeWallpaper(R.drawable.unsplash_bg, width)
    }

    fun load(hasVisibleWallpaper: Boolean) {
        if (cachedDisplay != null) return
        if (hasVisibleWallpaper && hasCurrentWallpaperFile()) {
            val now = SystemClock.elapsedRealtime()
            val last = prefs.wallpaperElapsed
            if (last != 0L && last <= now) return
        }
        loadWallpaper()
    }

    fun retryFallback() {
        if (!prefs.lastWallpaper.startsWith("res:")) return
        if (backgroundLoadJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFallbackRetry < FALLBACK_RETRY_MS) return
        lastFallbackRetry = now
        loadWallpaper()
    }

    private fun hasCurrentWallpaperFile(): Boolean {
        val key = prefs.lastWallpaper
        if (!key.startsWith("file:")) return false
        return File(activity.filesDir, "backgrounds/${key.removePrefix("file:")}").exists()
    }

    private fun loadWallpaper() {
        val width = wallpaperWidth()
        backgroundLoadJob?.cancel()
        backgroundLoadJob = activity.lifecycleScope.launch {
            blurredCache.evictAll()
            val wallpaper = withContext(Dispatchers.IO) { pickWallpaper() }
            val bitmap = withContext(Dispatchers.IO) { decodeBitmap(wallpaper, width) }
            if (!isActive) return@launch

            val display = blurIfNeeded(bitmap)
            if (!isActive) return@launch
            showWallpaper(wallpaper, display, bitmap)
            ensureWallpaperPool(width)
            if (wallpaper.key.startsWith("res:")) scheduleFallbackRetry()
        }
    }

    private fun scheduleFallbackRetry() {
        activity.lifecycleScope.launch {
            delay(60_000)
            retryFallback()
        }
    }

    fun applyEffects(updateImage: Boolean = true) {
        val brightness = if (isSleeping()) prefs.sleepBrightness else prefs.backgroundBrightness
        binding.backgroundDim.alpha = ((100 - brightness) / 100f).coerceIn(0f, 0.9f)
        val overlay = binding.sleepBackground
        if (isSleeping() && prefs.sleepBackgroundMode == "gradient") {
            overlay.visibility = View.VISIBLE
            overlay.alpha = 1f
        } else {
            overlay.visibility = View.GONE
            overlay.alpha = 0f
        }
        if (updateImage) updateBackgroundImage(smooth = false, durationMs = 0L)
    }

    fun cancel() {
        backgroundLoadJob?.cancel()
        blurJob?.cancel()
    }

    fun trimMemory() {
        blurredCache.evictAll()
    }

    private fun wallpaperPool(): List<Wallpaper> {
        val dir = File(activity.filesDir, "backgrounds")
        val files = dir.listFiles()
            ?.filter { it.isFile && (it.extension == "jpg" || it.extension == "png") && it.length() > 50_000 }
            ?.sortedBy { it.name }
            .orEmpty()

        if (files.isEmpty()) return listOf(Wallpaper("res:unsplash_bg", R.drawable.unsplash_bg, null))
        return files.map { Wallpaper("file:${it.name}", 0, it) }
    }

    private suspend fun pickWallpaper(): Wallpaper {
        val pool = wallpaperPool()
        val seen = prefs.seenWallpapers + prefs.lastWallpaper
        val wallpaper = pool.filter { it.key !in seen }.randomOrNull()
            ?: fetchFreshWallpaper(seen)
            ?: pool.filter { it.key != prefs.lastWallpaper }.randomOrNull()
            ?: pool.first()
        prefs.seenWallpapers = seen + wallpaper.key
        prefs.lastWallpaper = wallpaper.key
        prefs.wallpaperElapsed = SystemClock.elapsedRealtime()
        return wallpaper
    }

    private suspend fun fetchFreshWallpaper(seen: Set<String>): Wallpaper? {
        val photo = BackgroundService.load(activity, prefs.backgroundCollection, wallpaperWidth(), seen)
            ?: return null
        pruneWallpaperFiles(photo.file)
        return Wallpaper("file:${photo.file.name}", 0, photo.file)
    }

    private fun showWallpaper(wallpaper: Wallpaper, display: Bitmap?, original: Bitmap? = display) {
        val previous = shownBackground
        backgroundBitmap = original
        if (display != null) {

            binding.backgroundBis.setImageBitmap(previous ?: display)
            binding.backgroundBis.alpha = 1f
            binding.backgroundImage.setImageBitmap(display)
            if (previous != null && previous !== display) {
                binding.backgroundImage.alpha = 0f
                Tween.run(binding.backgroundImage, 400L, update = { t ->
                    binding.backgroundImage.alpha = Tween.ease(t)
                })
            } else {
                binding.backgroundImage.alpha = 1f
            }
        } else if (wallpaper.resId != 0) {
            binding.backgroundImage.setImageResource(wallpaper.resId)
            binding.backgroundBis.setImageResource(wallpaper.resId)
        }
        shownBackground = display
        cachedDisplay = display
        cacheLastDisplay(display)
        if (firstShown && previous == null) {
            binding.backgroundOverlay.alpha = 0f
            Tween.alpha(binding.backgroundOverlay, 1f, 400L)
        }
        firstShown = false
        applyEffects(updateImage = false)
    }

    private suspend fun blurIfNeeded(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        val blur = if (isSleeping()) prefs.sleepBlur else prefs.backgroundBlur
        if (blur <= 0) return bitmap
        blurredCache.get(blur)?.let { return it }
        val blurred = withContext(Dispatchers.Default) { BackgroundService.blurBitmap(activity, bitmap, blur) }
        blurredCache.put(blur, blurred)
        return blurred
    }

    private fun ensureWallpaperPool(width: Int) {
        val dir = File(activity.filesDir, "backgrounds")
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 50_000 } ?: emptyList()
        val seen = prefs.seenWallpapers + prefs.lastWallpaper
        if (files.count { "file:${it.name}" !in seen } >= 3) return

        preloadScope.launch {
            val photo = BackgroundService.load(activity, prefs.backgroundCollection, width, seen)
            if (photo == null) {
                Log.w(TAG, "Unsplash wallpaper unavailable; keeping the current pool")
                return@launch
            }
            pruneWallpaperFiles(photo.file)
        }
    }

    private fun pruneWallpaperFiles(keep: File) {
        val dir = File(activity.filesDir, "backgrounds")
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_WALLPAPER_FILES).forEach {
            if (it != keep && "file:${it.name}" != prefs.lastWallpaper) it.delete()
        }
    }

    private fun cacheLastDisplay(display: Bitmap?) {
        if (display == null) return
        preloadScope.launch(Dispatchers.IO) {
            val dir = activity.filesDir
            val temp = try {
                File.createTempFile("last_display", ".tmp", dir)
            } catch (t: Throwable) {
                Log.w(TAG, "cacheLastDisplay failed", t)
                return@launch
            }
            try {
                val written = temp.outputStream().use {
                    display.compress(Bitmap.CompressFormat.PNG, 100, it)
                }

                if (written) {
                    Os.rename(temp.path, File(dir, LAST_DISPLAY).path)
                } else {
                    temp.delete()
                    Log.w(TAG, "cacheLastDisplay failed: compress returned false")
                }
            } catch (t: Throwable) {
                temp.delete()
                Log.w(TAG, "cacheLastDisplay failed", t)
            }
        }
    }

    private fun wallpaperWidth(): Int =
        activity.resources.displayMetrics.widthPixels.coerceIn(1280, 1920)

    private fun decodeBitmap(wallpaper: Wallpaper, width: Int): Bitmap? =
        if (wallpaper.file != null) BackgroundService.decode(wallpaper.file, width)
        else decodeWallpaper(wallpaper.resId, width)

    private fun decodeWallpaper(resId: Int, maxWidth: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(activity.resources, resId, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeResource(activity.resources, resId, options)
        } catch (e: Exception) {
            Log.e("WallpaperController", "decodeWallpaper failed", e)
            null
        }
    }

    fun updateBackgroundImage(smooth: Boolean, durationMs: Long) {
        val bitmap = backgroundBitmap
        val blur = if (isSleeping()) prefs.sleepBlur else prefs.backgroundBlur
        blurJob?.cancel()

        fun applyShown(shown: Bitmap) {
            val previous = shownBackground
            if (smooth && previous != null && previous !== shown) {
                binding.backgroundBis.setImageBitmap(previous)
                binding.backgroundBis.alpha = 1f
                binding.backgroundImage.setImageBitmap(shown)
                binding.backgroundImage.alpha = 0f
                Tween.run(binding.backgroundImage, durationMs, update = { t ->
                    binding.backgroundImage.alpha = Tween.ease(t)
                })
            } else {
                binding.backgroundImage.setImageBitmap(shown)
                binding.backgroundImage.alpha = 1f
                binding.backgroundBis.setImageBitmap(shown)
                binding.backgroundBis.alpha = 1f
            }
            shownBackground = shown
            cachedDisplay = shown
        }

        if (bitmap == null) {
            return
        }
        if (blur <= 0) {
            applyShown(bitmap)
            return
        }
        val cached = blurredCache.get(blur)
        if (cached != null) {
            applyShown(cached)
            return
        }

        val targetSleep = isSleeping()
        blurJob = activity.lifecycleScope.launch {
            delay(60)
            val blurred = withContext(Dispatchers.Default) { BackgroundService.blurBitmap(activity, bitmap, blur) }
            if (isSleeping() != targetSleep || backgroundBitmap !== bitmap) return@launch
            blurredCache.put(blur, blurred)
            applyShown(blurred)
        }
    }

    companion object {
        private const val TAG = "WallpaperController"
        private const val LAST_DISPLAY = "last_display.png"
        private const val MAX_WALLPAPER_FILES = 12
        private const val FALLBACK_RETRY_MS = 5 * 60_000L
        private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var cachedDisplay: Bitmap? = null

        fun cachedBackground(): Bitmap? = cachedDisplay

        fun diskBackground(context: Context): Bitmap? {
            val file = File(context.filesDir, LAST_DISPLAY)

            if (!file.exists() || !hasPngEnd(file)) {
                file.delete()
                return null
            }
            return BitmapFactory.decodeFile(file.path)
        }

        private fun hasPngEnd(file: File): Boolean = try {
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.length()
                if (size < 20) return false

                raf.seek(size - 12)
                val length = raf.readInt()
                val type = ByteArray(4).also { raf.readFully(it) }
                length == 0 && String(type) == "IEND"
            }
        } catch (_: Throwable) {
            false
        }
    }
}
