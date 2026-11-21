package com.taylorlauncher.ui

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.taylorlauncher.R
import com.taylorlauncher.core.AppFonts
import com.taylorlauncher.core.LauncherPrefs
import com.taylorlauncher.core.Tween

class HomeRoleController(
    private val activity: AppCompatActivity,
    private val parent: ViewGroup,
    private val prefs: LauncherPrefs,
    private val hasOverlays: () -> Boolean,
    private val currentFocus: () -> View?,
    private val setReturnFocus: (View?) -> Unit,
    private val onAttached: (View) -> Unit,
    private val onDetached: (View) -> Unit,
    private val onDismiss: () -> Unit
) {

    fun maybePrompt() {
        if (prefs.homePromptShown) return
        if (isDefaultLauncher()) {
            prefs.homePromptShown = true
            return
        }
        prefs.homePromptShown = true
        if (!hasOverlays()) setReturnFocus(currentFocus())

        val root = FrameLayout(activity).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2131316.toInt())
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = FrameLayout.LayoutParams(dp(380), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        card.addView(TextView(activity).apply {
            text = activity.getString(R.string.home_prompt_title)
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = AppFonts.typeface(activity, prefs.fontFamily)
            setPadding(0, 0, 0, dp(6))
        })
        card.addView(TextView(activity).apply {
            text = activity.getString(R.string.home_prompt_text)
            setTextColor(ContextCompat.getColor(activity, R.color.on_surface_variant))
            textSize = activity.resources.getDimension(R.dimen.settings_row_text_size) /
                activity.resources.displayMetrics.scaledDensity
            setPadding(0, 0, 0, dp(12))
        })

        val rows = mutableListOf<View>()
        fun actionRow(label: String, action: () -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                isSoundEffectsEnabled = false
                setPadding(dp(12), 0, dp(12), 0)
                background = ContextCompat.getDrawable(activity, R.drawable.settings_row_bg)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(40)
                ).apply { bottomMargin = dp(4) }
            }
            row.addView(TextView(activity).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = activity.resources.getDimension(R.dimen.settings_row_text_size) /
                    activity.resources.displayMetrics.scaledDensity
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.setOnClickListener { action() }
            rows.add(row)
            card.addView(row)
        }

        val close = {
            Tween.alpha(root, 0f, 150L, onEnd = {
                (root.parent as? ViewGroup)?.removeView(root)
                onDetached(root)
                onDismiss()
            })
        }

        actionRow(activity.getString(R.string.home_prompt_open)) {
            close()
            openHomeSettings()
        }
        actionRow(activity.getString(R.string.home_prompt_later)) { close() }

        rows.forEachIndexed { index, row ->
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        rows[(index + 1) % rows.size].requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        rows[(index - 1 + rows.size) % rows.size].requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (event.action == KeyEvent.ACTION_DOWN) row.performClick()
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        close()
                        true
                    }
                    else -> false
                }
            }
        }

        root.addView(card)
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        parent.addView(root)
        onAttached(root)
        Tween.alpha(root, 1f, 200L)
        root.postDelayed({ rows.firstOrNull()?.requestFocus() }, 100)
    }

    private fun isDefaultLauncher(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val roleManager = activity.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                return true
            }
        }
        return try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val res = activity.packageManager.resolveActivity(
                home,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
            res?.activityInfo?.packageName == activity.packageName
        } catch (e: Exception) {
            false
        }
    }

    private fun openHomeSettings() {
        if (Build.VERSION.SDK_INT >= 29) {
            val roleManager = activity.getSystemService(RoleManager::class.java)
            if (roleManager != null) {
                try {
                    activity.startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    return
                } catch (_: Exception) {
                }
            }
        }
        val intents = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent("android.settings.HOME_SETTINGS"),
            Intent().setComponent(
                ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings")
            )
        )
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return
            } catch (e: Exception) {
            }
        }
        Toast.makeText(activity, activity.getString(R.string.home_prompt_manual), Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
