package com.taylorlauncher

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.taylorlauncher.core.TvInputEntry
import com.taylorlauncher.core.TvInputs
import com.taylorlauncher.core.Tween
import com.taylorlauncher.databinding.ActivityTvInputsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvInputsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvInputsBinding
    private val rows = mutableListOf<View>()
    private var lastRightPress = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvInputsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.isSoundEffectsEnabled = false
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
    }

    private fun goHome() {

        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {

        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.repeatCount > 0) return true
            val now = SystemClock.uptimeMillis()
            if (now - lastRightPress < 1000) {
                goHome()
                return true
            }
            lastRightPress = now
            showHint(getString(R.string.tv_inputs_right_hint))
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showHint(text: String) {
        binding.tvInputsHint.text = text
        binding.tvInputsHint.alpha = 1f
        binding.tvInputsHint.postDelayed({
            if (!isFinishing) Tween.alpha(binding.tvInputsHint, 0f, 400L)
        }, 3000)
    }

    override fun onResume() {
        super.onResume()
        loadInputs()
    }

    private fun loadInputs() {
        lifecycleScope.launch {
            val inputs = withContext(Dispatchers.IO) { TvInputs.list(this@TvInputsActivity) }
            if (isActive) renderInputs(inputs)
        }
    }

    private fun renderInputs(inputs: List<TvInputEntry>) {
        rows.clear()
        binding.tvInputsContent.removeAllViews()

        if (inputs.isEmpty()) {
            Toast.makeText(this, getString(R.string.tv_inputs_empty), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        inputs.forEachIndexed { index, entry ->
            val row = rowBase()
            row.addView(label(entry))
            row.addView(state(entry))
            row.setOnClickListener { open(entry) }
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
                    else -> false
                }
            }
            rows.add(row)
            binding.tvInputsContent.addView(row)
        }

        binding.tvInputsContent.post { rows.firstOrNull()?.requestFocus() }
    }

    private fun rowBase(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(10), 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dimen(R.dimen.settings_row_height)
        ).apply { bottomMargin = dp(4) }
        background = ContextCompat.getDrawable(this@TvInputsActivity, R.drawable.settings_row_bg)
        isFocusable = true
        isFocusableInTouchMode = true
        isSoundEffectsEnabled = false
    }

    private fun label(entry: TvInputEntry) = TextView(this).apply {
        text = entry.label
        setTextColor(if (entry.connected) Color.WHITE else 0xFF9A9AA0.toInt())
        textSize = sp(R.dimen.tv_input_label_text_size)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun state(entry: TvInputEntry) = TextView(this).apply {
        text = getString(
            if (entry.connected) R.string.tv_input_connected else R.string.tv_input_disconnected
        )
        setTextColor(if (entry.connected) 0xFF35C759.toInt() else 0xFF6E6E73.toInt())
        textSize = sp(R.dimen.tv_input_state_text_size)
    }

    private fun open(entry: TvInputEntry) {
        try {
            startActivity(TvInputs.watchIntent(entry.id))
            finish()
            return
        } catch (e: Exception) {

        }
        try {
            startActivity(
                Intent(this, TvInputActivity::class.java)
                    .putExtra(TvInputActivity.EXTRA_INPUT_ID, entry.id)
            )
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.tv_input_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dimen(id: Int): Int = resources.getDimensionPixelSize(id)

    private fun sp(id: Int): Float = resources.getDimension(id) / resources.displayMetrics.scaledDensity
}
