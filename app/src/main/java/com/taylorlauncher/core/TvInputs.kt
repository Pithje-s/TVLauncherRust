package com.taylorlauncher.core

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import com.taylorlauncher.R

data class TvInputEntry(
    val id: String,
    val label: String,
    val type: Int,
    val port: Int,
    val connected: Boolean
)

object TvInputs {

    private val supportedTypes = setOf(
        TvInputInfo.TYPE_HDMI,
        TvInputInfo.TYPE_TUNER,
        TvInputInfo.TYPE_COMPOSITE,
        TvInputInfo.TYPE_SVIDEO,
        TvInputInfo.TYPE_SCART
    )

    fun list(context: Context): List<TvInputEntry> {
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            ?: return emptyList()
        val entries = try {
            manager.tvInputList
                .filter { supportedTypes.contains(it.type) && (it.isPassthroughInput() || it.id.contains("/HW")) }
                .map { info ->
                    val port = portOf(info)
                    TvInputEntry(
                        id = info.id,
                        label = labelOf(context, info, port),
                        type = info.type,
                        port = port,
                        connected = isConnected(manager, info)
                    )
                }
                .sortedWith(compareBy({ it.type }, { it.port }, { it.label }))
        } catch (e: Exception) {
            emptyList()
        }

        val counters = mutableMapOf<String, Int>()
        return entries.map { entry ->
            val seen = (counters[entry.label] ?: 0) + 1
            counters[entry.label] = seen
            if (seen == 1) entry else entry.copy(label = "${entry.label} ($seen)")
        }
    }

    private fun portOf(info: TvInputInfo): Int {
        val marker = info.id.substringAfterLast("/HW", "")
        return marker.toIntOrNull() ?: 0
    }

    private fun isConnected(manager: TvInputManager, info: TvInputInfo): Boolean = try {
        manager.getInputState(info.id) == TvInputManager.INPUT_STATE_CONNECTED
    } catch (e: Exception) {
        false
    }

    private fun labelOf(context: Context, info: TvInputInfo, port: Int): String {
        val label = try {
            info.loadLabel(context).toString()
        } catch (e: Exception) {
            ""
        }
        return label.ifBlank { defaultLabel(context, info, port) }
    }

    private fun defaultLabel(context: Context, info: TvInputInfo, port: Int): String = when (info.type) {
        TvInputInfo.TYPE_HDMI -> if (port > 0) "HDMI $port" else "HDMI"
        TvInputInfo.TYPE_TUNER -> context.getString(R.string.tv_input_tuner)
        TvInputInfo.TYPE_COMPOSITE -> context.getString(R.string.tv_input_av)
        else -> context.getString(R.string.tv_input_other)
    }

    fun watchIntent(inputId: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                TvContract.buildChannelUriForPassthroughInput(inputId),
                "vnd.android.cursor.item/channel"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun liveTvIntent(context: Context): Intent? {
        val packages = listOf(
            "com.mediatek.wwtv.tvcenter",
            "com.mitv.livetv.ci_mediatek",
            "com.google.android.tv"
        )
        for (name in packages) {
            val intent = context.packageManager.getLaunchIntentForPackage(name)
            if (intent != null) return intent
        }
        return null
    }
}
