package com.taylorlauncher.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.taylorlauncher.R
import com.taylorlauncher.core.AppFonts
import com.taylorlauncher.core.IconLoader
import com.taylorlauncher.core.LauncherPrefs
import com.taylorlauncher.core.Tween

class LauncherItemPresenter(
    private val prefs: LauncherPrefs
) {

    private var cachedTypeface: Typeface? = null
    private var cachedTypefaceKey: String? = null
    private val backgroundCache = android.util.LruCache<String, Drawable>(32)

    fun createTileCell(context: Context, packageName: String?, title: String?): View {
        val size = tileSize(context)
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isFocusableInTouchMode = true
            isSoundEffectsEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val frame = FrameLayout(context).apply {
            id = R.id.tile_frame
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        frame.addView(ImageView(context).apply {
            id = R.id.app_icon
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_app_placeholder)
        })
        cell.addView(frame)

        cell.addView(TextView(context).apply {
            id = R.id.app_name
            setTextColor(ContextCompat.getColor(context, R.color.on_background))
            gravity = Gravity.CENTER
            setHorizontallyScrolling(false)
            ellipsize = android.text.TextUtils.TruncateAt.END
            isSingleLine = false
            maxLines = 2
            minLines = 2
        })

        cell.setOnFocusChangeListener { _, hasFocus ->
            val body = cell.getTag(R.id.tile_frame) as? Int ?: Color.WHITE
            val radius = tileRadius(context, tileSize(context))
            frame.background = tileBackground(context, radius, body, focused = hasFocus)

            if (hasFocus) {
                Tween.cancel(frame)
                Tween.scale(frame, 1.05f, 150L)
            } else {
                Tween.scale(frame, 1f, 150L)
            }
        }
        bindTileCell(cell, packageName, title)
        return cell
    }

    fun bindTileCell(cell: View, packageName: String?, title: String?) {
        val context = cell.context
        val density = context.resources.displayMetrics.density
        val size = tileSize(context)
        val radius = tileRadius(context, size)
        val bodyColor = IconLoader.edgeColorCached(packageName)
        val frame = cell.findViewById<FrameLayout>(R.id.tile_frame)
        val icon = cell.findViewById<ImageView>(R.id.app_icon)
        val label = cell.findViewById<TextView>(R.id.app_name)

        cell.layoutParams?.let {
            it.width = size
            it.height = LinearLayout.LayoutParams.WRAP_CONTENT
        }
        frame.layoutParams?.let {
            it.width = size
            it.height = size
        }
        val inner = (size / 6).coerceAtLeast((2 * density).toInt())
        icon.setPadding(inner, inner, inner, inner)
        clipIconToRing(icon, radius)
        icon.setImageDrawable(
            IconLoader.appIconCached(packageName)
                ?: ContextCompat.getDrawable(context, R.drawable.ic_app_placeholder)
        )

        label.text = title ?: ""
        label.typeface = typeface(context)
        val base = context.resources.getDimension(R.dimen.tile_label_text_size)
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, base * (prefs.fontSize / 16f))
        label.setPadding((2 * density).toInt(), (9 * density).toInt(), (2 * density).toInt(), 0)
        cell.contentDescription = title ?: ""
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val gapPx = (prefs.tileGap * density).toInt()
        label.layoutParams = LinearLayout.LayoutParams(
            size + gapPx,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }

        frame.background = tileBackground(context, radius, bodyColor)
        frame.scaleX = 1f
        frame.scaleY = 1f
        Tween.resetScale(frame)
        cell.setTag(R.id.tile_frame, bodyColor)
        cell.tag = packageName
        if (!packageName.isNullOrBlank() &&
            (IconLoader.appIconCached(packageName) == null || !IconLoader.isEdgeColorCached(packageName))
        ) {
            IconLoader.loadAsync(context, packageName) { loadedIcon, loadedColor ->
                if (cell.tag == packageName) {
                    icon.setImageDrawable(
                        loadedIcon ?: ContextCompat.getDrawable(context, R.drawable.ic_app_placeholder)
                    )
                    frame.background = tileBackground(context, radius, loadedColor)
                    cell.setTag(R.id.tile_frame, loadedColor)
                }
            }
        }
    }

    fun tileSize(context: Context): Int {
        val baseSize = context.resources.getDimensionPixelSize(R.dimen.tile_size)
        return (baseSize * prefs.tileScale / 100f).toInt().coerceAtLeast(baseSize / 2)
    }

    fun tileRadius(context: Context, size: Int): Float =
        (prefs.tileCornerRadius * context.resources.displayMetrics.density).coerceAtMost(size / 2f)

    fun tileBackground(context: Context, radius: Float, bodyColor: Int, focused: Boolean = false): Drawable {
        val key = "$radius:$bodyColor:$focused"
        backgroundCache.get(key)?.let { return it }
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE

            cornerRadius = radius + context.resources.displayMetrics.density
            setColor(bodyColor)
        }
        val drawable = if (!focused) body else LayerDrawable(arrayOf(body, ringDrawable(context, radius, bodyColor)))
        backgroundCache.put(key, drawable)
        return drawable
    }

    private fun ringDrawable(context: Context, radius: Float, bodyColor: Int): Drawable {
        val density = context.resources.displayMetrics.density
        val stroke = (2 * density).toInt().coerceAtLeast(1)

        val luminance = 0.299f * Color.red(bodyColor) +
            0.587f * Color.green(bodyColor) +
            0.114f * Color.blue(bodyColor)
        val ring = if (luminance > 140f) ContextCompat.getColor(context, R.color.primary) else Color.WHITE
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE

            cornerRadius = (radius + density - stroke / 2f).coerceAtLeast(0f)
            setColor(Color.TRANSPARENT)
            setStroke(stroke, ring)
        }
    }

    private fun clipIconToRing(icon: ImageView, radius: Float) {
        val density = icon.resources.displayMetrics.density
        val stroke = (2 * density).toInt().coerceAtLeast(1)

        val innerRadius = (radius + density - stroke).coerceAtLeast(0f)
        icon.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width <= 2 * stroke || view.height <= 2 * stroke) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                } else {
                    outline.setRoundRect(stroke, stroke, view.width - stroke, view.height - stroke, innerRadius)
                }
            }
        }
        icon.clipToOutline = true
    }

    private fun typeface(context: Context): Typeface {
        val key = "${prefs.fontFamily}:${prefs.fontWeight}"
        if (cachedTypeface == null || cachedTypefaceKey != key) {
            cachedTypeface = Typeface.create(AppFonts.typeface(context, prefs.fontFamily), prefs.fontWeight)
            cachedTypefaceKey = key
        }
        return cachedTypeface!!
    }

}
