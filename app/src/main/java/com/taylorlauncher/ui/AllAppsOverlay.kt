package com.taylorlauncher.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taylorlauncher.R
import com.taylorlauncher.core.AppFonts
import com.taylorlauncher.core.LauncherPrefs
import com.taylorlauncher.core.Tween

class AllAppsOverlay(
    private val activity: AppCompatActivity,
    private val parent: ViewGroup,
    private val itemPresenter: LauncherItemPresenter,
    private val prefs: LauncherPrefs,
    private val onLaunch: (Pair<String, String>) -> Unit,
    private val onAttached: (View) -> Unit,
    private val onDetached: (View) -> Unit,
    private val onDismiss: () -> Unit
) {

    private var root: FrameLayout? = null
    private var recycler: RecyclerView? = null
    private var header: TextView? = null
    private var closing = false
    private var homeInterface: View? = null

    fun prepare(apps: List<Pair<String, String>>) {
        if (root != null || apps.isEmpty()) return

        val density = activity.resources.displayMetrics.density
        val tileSize = itemPresenter.tileSize(activity)
        val gap = (prefs.tileGap * density).toInt()

        val overlay = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            alpha = 0f
            visibility = View.INVISIBLE
        }
        root = overlay
        overlay.setOnKeyListener { _, keyCode, event -> handleBack(keyCode, event) }

        val screenWidth = activity.resources.displayMetrics.widthPixels
        val sidePadding = (screenWidth * 0.2f).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(sidePadding, dp(20), sidePadding, dp(20))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(TextView(activity).apply {
            text = activity.getString(R.string.settings_all_apps_title)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            header = this
            applyHeaderFont(this)
        })

        val availableWidth = screenWidth - 2 * sidePadding
        val columns = (availableWidth / (tileSize + gap)).coerceIn(2, 8)
        val recycler = RecyclerView(activity).apply {
            layoutManager = GridLayoutManager(activity, columns)
            itemAnimator = null
            adapter = AllAppsOverlayAdapter(
                apps = apps,
                itemPresenter = itemPresenter,
                onOpen = { app ->
                    close()
                    onLaunch(app)
                },
                onKey = { position, keyCode, event ->
                    if (handleBack(keyCode, event)) {
                        true
                    } else if (event.action == KeyEvent.ACTION_DOWN && position >= 0) {
                        val last = apps.lastIndex
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> position % columns == 0
                            KeyEvent.KEYCODE_DPAD_RIGHT -> position == last || position % columns == columns - 1
                            KeyEvent.KEYCODE_DPAD_UP -> position < columns
                            KeyEvent.KEYCODE_DPAD_DOWN -> position + columns > last
                            else -> false
                        }
                    } else {
                        false
                    }
                }
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(4), 0, 0)
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: android.graphics.Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {

                    val side = ((parent.width / columns - tileSize) / 2).coerceAtLeast(0)
                    outRect.left = side
                    outRect.right = side
                    outRect.top = gap / 2
                    outRect.bottom = gap / 2
                }
            })
        }
        recycler.setOnKeyListener { _, keyCode, event -> handleBack(keyCode, event) }
        this.recycler = recycler
        container.addView(recycler)

        overlay.addView(container)
        overlay.isFocusable = true
        overlay.isFocusableInTouchMode = true
        overlay.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        parent.addView(overlay)
    }

    fun show(apps: List<Pair<String, String>>) {
        if (root == null) prepare(apps)
        val overlay = root ?: return
        if (overlay.parent == null) parent.addView(overlay)

        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        onAttached(overlay)

        homeInterface = parent.findViewById(R.id.launcher_interface)
        homeInterface?.let { Tween.alpha(it, 0f, 160L) }
        Tween.alpha(overlay, 1f, 160L)
        recycler?.post {
            val grid = recycler ?: return@post
            if (grid.childCount > 0) grid.getChildAt(0).requestFocus() else grid.requestFocus()
        }
    }

    fun refreshItems() {
        header?.let { applyHeaderFont(it) }
        recycler?.adapter?.notifyDataSetChanged()
    }

    private fun handleBack(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            close()
            return true
        }
        return false
    }

    private fun close() {
        val overlay = root ?: return
        if (overlay.parent == null || closing) return
        closing = true

        onDismiss()

        homeInterface?.let { Tween.alpha(it, 1f, 160L) }
        Tween.alpha(overlay, 0f, 160L, onEnd = {
            overlay.visibility = View.INVISIBLE
            closing = false
            onDetached(overlay)
            homeInterface?.alpha = 1f
        })
    }

    fun release() {
        val overlay = root ?: return
        onDetached(overlay)
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        root = null
        recycler = null
        header = null
        closing = false
    }

    private fun applyHeaderFont(view: TextView) {
        view.typeface = Typeface.create(AppFonts.typeface(activity, prefs.fontFamily), prefs.fontWeight)
        view.textSize = 14f * prefs.fontSize / 16f
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
