package com.taylorlauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.roundToInt

class ValueSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var minValue = 0
    var maxValue = 100
    var stepValue = 10

    var value = 0
        private set

    var onValueChanged: ((Int) -> Unit)? = null

    var label: String? = null
        set(value) {
            field = value
            updateA11y()
        }

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0072EE.toInt() }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = 0xFF0072EE.toInt()
    }
    private val rect = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isSoundEffectsEnabled = false
        minimumHeight = (32 * density).toInt()
        updateA11y()
        accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.SeekBar"
                info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }

            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean =
                when (action) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                        setValue(value + stepValue, true)
                        true
                    }
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                        setValue(value - stepValue, true)
                        true
                    }
                    else -> super.performAccessibilityAction(host, action, args)
                }
        }
    }

    private fun updateA11y() {
        contentDescription = listOfNotNull(label, value.toString()).joinToString(": ")
    }

    fun configure(min: Int, max: Int, step: Int, initial: Int) {
        minValue = min
        maxValue = max
        stepValue = step
        value = snap(initial)
        updateA11y()
        invalidate()
    }

    fun setValue(newValue: Int, notify: Boolean = false) {
        val snapped = snap(newValue)
        if (snapped == value) return
        value = snapped
        updateA11y()
        invalidate()
        if (notify) onValueChanged?.invoke(value)
    }

    fun isAtMin(): Boolean = value <= minValue

    private fun snap(v: Int): Int {
        if (stepValue <= 1) return v.coerceIn(minValue, maxValue)
        val steps = ((v - minValue).toFloat() / stepValue).roundToInt()
        return (minValue + steps * stepValue).coerceIn(minValue, maxValue)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                setValue(value + if (rtl) -stepValue else stepValue, true)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                setValue(value + if (rtl) stepValue else -stepValue, true)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val pad = 8 * density
        val trackH = 4 * density
        val top = (h - trackH) / 2

        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val startX = if (rtl) width - pad else pad
        val endX = if (rtl) pad else width - pad

        rect.set(minOf(startX, endX), top, maxOf(startX, endX), top + trackH)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, trackPaint)

        val ratio = if (maxValue > minValue) (value - minValue).toFloat() / (maxValue - minValue) else 0f
        val fillX = startX + (endX - startX) * ratio
        rect.set(minOf(startX, fillX), top, maxOf(startX, fillX), top + trackH)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, fillPaint)

        val thumbR = 6 * density
        canvas.drawCircle(fillX, h / 2, thumbR, thumbPaint)

        if (isFocused) {
            rect.set(density, density, width - density, h - density)
            canvas.drawRoundRect(rect, 6 * density, 6 * density, focusPaint)
        }
    }
}
