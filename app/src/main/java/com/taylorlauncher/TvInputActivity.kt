package com.taylorlauncher

import android.media.tv.TvContract
import android.media.tv.TvView
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TvInputActivity : AppCompatActivity() {

    private lateinit var tvView: TvView
    private lateinit var hint: TextView

    private var inputId: String? = null
    private var needsTune = false

    private val hideHint = Runnable {
        hint.animate().alpha(0f).setDuration(1000).start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_input)

        tvView = findViewById(R.id.tv_view)
        hint = findViewById(R.id.tv_input_hint)

        inputId = intent.getStringExtra(EXTRA_INPUT_ID)
        if (inputId.isNullOrBlank()) {
            finish()
            return
        }

        tvView.setCallback(object : TvView.TvInputCallback() {
            override fun onVideoAvailable(inputId: String) {
                hint.text = getString(R.string.tv_input_back_hint)
                showHint()
            }

            override fun onVideoUnavailable(inputId: String, reason: Int) {
                hint.text = getString(R.string.tv_input_no_signal)
                showHint()
            }
        })

        needsTune = true
        tvView.post { tuneIfNeeded() }
        showHint()
    }

    override fun onResume() {
        super.onResume()
        tvView.post { tuneIfNeeded() }
    }

    private fun showHint() {
        hint.animate().cancel()
        hint.alpha = 1f
        hint.removeCallbacks(hideHint)
        hint.postDelayed(hideHint, 2000)
    }

    private fun tuneIfNeeded() {
        val id = inputId ?: return
        if (!needsTune) return
        needsTune = false
        try {
            tvView.tune(id, TvContract.buildChannelUriForPassthroughInput(id))
        } catch (e: Exception) {
            Log.e(TAG, "tune failed", e)
            hint.text = getString(R.string.tv_input_failed)
        }
    }

    override fun onPause() {
        tvView.reset()
        needsTune = true
        hint.removeCallbacks(hideHint)
        super.onPause()
    }

    override fun onDestroy() {
        tvView.setCallback(null)
        tvView.reset()
        hint.removeCallbacks(hideHint)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TvInputActivity"
        const val EXTRA_INPUT_ID = "extra_input_id"
    }
}
