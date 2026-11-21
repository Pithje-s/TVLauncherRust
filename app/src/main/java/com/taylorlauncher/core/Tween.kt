package com.taylorlauncher.core

import android.view.Choreographer
import android.view.View
import java.util.WeakHashMap

object Tween {

    private class Anim(
        val startNanos: Long,
        val durationNanos: Long,
        val update: (Float) -> Unit,
        val onEnd: (() -> Unit)?
    )

    private val running = WeakHashMap<Any, Anim>()
    private var scheduled = false
    private val choreographer = Choreographer.getInstance()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            val now = System.nanoTime()
            val snapshot = running.entries.toList()
            for ((key, anim) in snapshot) {
                if (running[key] !== anim) continue
                val t = ((now - anim.startNanos).toFloat() / anim.durationNanos).coerceIn(0f, 1f)
                anim.update(t)
                if (t >= 1f) {
                    running.remove(key)
                    anim.onEnd?.invoke()
                }
            }
            if (running.isNotEmpty()) schedule()
        }
    }

    fun ease(t: Float): Float = t * t * (3f - 2f * t)

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    fun run(key: Any, durationMs: Long, update: (Float) -> Unit, onEnd: (() -> Unit)? = null) {
        running.remove(key)
        running[key] = Anim(
            startNanos = System.nanoTime(),
            durationNanos = durationMs.coerceAtLeast(1L) * 1_000_000L,
            update = update,
            onEnd = onEnd
        )
        schedule()
    }

    fun cancel(key: Any) {
        running.remove(key)
    }

    fun cancelAll() {
        running.clear()
    }

    fun scale(view: View, target: Float, durationMs: Long = 180L) {
        val from = view.scaleX
        run(view, durationMs, update = { t ->
            val s = from + (target - from) * ease(t)
            view.scaleX = s
            view.scaleY = s
        })
    }

    fun resetScale(view: View) {
        cancel(view)
        view.scaleX = 1f
        view.scaleY = 1f
    }

    fun alpha(view: View, target: Float, durationMs: Long = 300L, onEnd: (() -> Unit)? = null) {
        val from = view.alpha
        run(view, durationMs, update = { t ->
            view.alpha = from + (target - from) * ease(t)
        }, onEnd = onEnd)
    }
}
