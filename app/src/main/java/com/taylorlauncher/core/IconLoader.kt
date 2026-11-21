package com.taylorlauncher.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object IconLoader {

    private val edgeColorCache = LruCache<String, Int>(256)
    private val drawableCache = LruCache<String, Drawable>(256)
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun appIconCached(packageName: String?): Drawable? =
        if (packageName.isNullOrBlank()) null else drawableCache.get(packageName)

    fun edgeColorCached(packageName: String?): Int =
        if (packageName.isNullOrBlank()) android.graphics.Color.WHITE
        else edgeColorCache.get(packageName) ?: android.graphics.Color.WHITE

    fun isEdgeColorCached(packageName: String?): Boolean =
        !packageName.isNullOrBlank() && edgeColorCache.get(packageName) != null

    fun loadAsync(context: Context, packageName: String, onReady: (Drawable?, Int) -> Unit) {
        loadScope.launch {
            val icon = appIcon(context, packageName)
            val color = edgeColor(context, packageName)
            withContext(Dispatchers.Main) { onReady(icon, color) }
        }
    }

    fun appIcon(context: Context, packageName: String?): Drawable? {
        if (packageName.isNullOrBlank()) return null

        drawableCache.get(packageName)?.let { return it }
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            drawableCache.put(packageName, drawable)
            drawable
        } catch (e: Exception) {
            null
        }
    }

    fun edgeColor(context: Context, packageName: String?): Int {
        if (packageName.isNullOrBlank()) return android.graphics.Color.WHITE
        edgeColorCache.get(packageName)?.let { return it }
        val color = try {
            val drawable = appIcon(context, packageName) ?: return android.graphics.Color.WHITE
            val bitmap = drawableToBitmap(drawable)
            val w = bitmap.width
            val h = bitmap.height
            if (w < 4 || h < 4) return android.graphics.Color.WHITE
            val border = (minOf(w, h) / 10).coerceIn(2, 12)
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            fun add(x: Int, y: Int) {
                val c = bitmap.getPixel(x, y)
                if (android.graphics.Color.alpha(c) < 40) return
                r += android.graphics.Color.red(c)
                g += android.graphics.Color.green(c)
                b += android.graphics.Color.blue(c)
                n++
            }
            var d = 0
            while (d < border) {
                var x = 0
                while (x < w) { add(x, d); add(x, h - 1 - d); x += 2 }
                var y = 0
                while (y < h) { add(d, y); add(w - 1 - d, y); y += 2 }
                d += 2
            }
            if (n == 0) android.graphics.Color.WHITE
            else android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        } catch (e: Exception) {
            android.graphics.Color.WHITE
        }
        edgeColorCache.put(packageName, color)
        return color
    }

    fun trim() {
        drawableCache.evictAll()
        edgeColorCache.evictAll()
    }

    fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap

        val fallback = 96
        val max = 128
        val width = when {
            drawable.intrinsicWidth in 16..max -> drawable.intrinsicWidth
            drawable.intrinsicWidth > max -> max
            else -> fallback
        }
        val height = when {
            drawable.intrinsicHeight in 16..max -> drawable.intrinsicHeight
            drawable.intrinsicHeight > max -> max
            else -> fallback
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    suspend fun preloadPackageIcons(context: Context, packageNames: List<String>) = withContext(Dispatchers.IO) {
        for (pkg in packageNames) {
            if (pkg.isNullOrBlank()) continue
            val drawable = try { context.packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }
            if (drawable != null) {
                edgeColor(context, pkg)
            }
        }
    }
}
