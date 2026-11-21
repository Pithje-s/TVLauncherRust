package com.taylorlauncher.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.Calendar
import com.taylorlauncher.BuildConfig

data class BackgroundPhoto(
    val id: String,
    val file: File,
    val author: String,
    val link: String,
    val color: String
)

object BackgroundService {

    private const val API = "https://api.unsplash.com/photos/random"
    private const val TAG = "BackgroundService"

    private val collections = mapOf(
        "noon" to "GD4aOSg4yQE",
        "day" to "o8uX55RbBPs",
        "evening" to "3M2rKTckZaQ",
        "night" to "bHDh4Ae7O8o"
    )

    private val client = HttpClient.client

    fun collectionForNow(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour in 11..15 -> "noon"
            hour in 6..10 || hour in 16..17 -> "day"
            hour in 18..21 -> "evening"
            else -> "night"
        }
    }

    suspend fun load(
        context: Context,
        collection: String,
        width: Int,
        exclude: Set<String> = emptySet()
    ): BackgroundPhoto? =
        withContext(Dispatchers.IO) {
            if (BuildConfig.UNSPLASH_CLIENT_ID.isBlank()) return@withContext null
            val name = if (collection == "auto") collectionForNow() else collection
            val collectionId = collections[name] ?: collections.getValue(collectionForNow())
            val json = fetchJson("$API?collections=$collectionId&count=30") ?: return@withContext null
            val photos = JSONArray(json)
            val candidates = (0 until photos.length())
                .map { photos.getJSONObject(it) }
                .filter { it.optString("id").isNotEmpty() && it.optString("id") !in exclude }
            if (candidates.isEmpty()) return@withContext null

            val photo = candidates.random()
            val id = photo.getString("id")
            val rawUrl = photo.getJSONObject("urls").getString("raw")
            val url = "$rawUrl&w=$width&dpr=1"

            val file = File(File(context.filesDir, "backgrounds"), "$id.jpg")
            if (!file.exists()) {
                val bytes = download(url) ?: return@withContext null
                val dir = file.parentFile ?: return@withContext null
                dir.mkdirs()
                val temp = try {
                    File.createTempFile(id, ".tmp", dir)
                } catch (t: Throwable) {
                    Log.w(TAG, "Unsplash download failed", t)
                    return@withContext null
                }
                try {
                    temp.writeBytes(bytes)

                    Os.rename(temp.path, file.path)
                } catch (t: Throwable) {
                    temp.delete()
                    Log.w(TAG, "Unsplash download failed", t)
                    return@withContext null
                }
            }

            BackgroundPhoto(
                id = id,
                file = file,
                author = photo.optJSONObject("user")?.optString("name").orEmpty(),
                link = photo.optJSONObject("links")?.optString("html").orEmpty(),
                color = photo.optString("color", "#000000")
            )
        }

    fun decode(file: File, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private val renderScriptLock = Any()
    private var renderScript: RenderScript? = null

    private fun renderScript(context: Context): RenderScript = synchronized(renderScriptLock) {
        renderScript ?: RenderScript.create(context.applicationContext).also { renderScript = it }
    }

    private fun resetRenderScript() = synchronized(renderScriptLock) {
        renderScript?.destroy()
        renderScript = null
    }

    fun warmUp(context: Context) {
        if (renderScript != null) return
        try {
            val rs = renderScript(context)
            val warm = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            renderScriptBlur(rs, warm, 9).recycle()
            warm.recycle()
        } catch (e: Throwable) {
            Log.w(TAG, "RenderScript unavailable", e)
            resetRenderScript()
        }
    }

    fun blurBitmap(context: Context, source: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return source
        return try {
            renderScriptBlur(renderScript(context), source, radius)
        } catch (e: Throwable) {
            Log.w(TAG, "RenderScript blur failed; keeping the sharp bitmap", e)
            resetRenderScript()
            source
        }
    }

    private fun renderScriptBlur(rs: RenderScript, source: Bitmap, radius: Int): Bitmap {
        val input = Allocation.createFromBitmap(rs, source)
        val output = Allocation.createTyped(rs, input.type)
        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        script.setRadius(radius.toFloat().coerceIn(0.1f, 25f))
        script.setInput(input)
        script.forEach(output)
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        output.copyTo(result)
        input.destroy()
        output.destroy()
        script.destroy()
        return result
    }

    private fun fetchJson(url: String): String? = try {
        client.newCall(
            Request.Builder()
                .url(url)
                .addHeader("Authorization", "Client-ID ${BuildConfig.UNSPLASH_CLIENT_ID}")
                .addHeader("Accept-Version", "v1")
                .build()
        ).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else {
                Log.w(TAG, "Unsplash request failed: HTTP ${response.code}")
                null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unsplash request failed", e)
        null
    }

    private fun download(url: String): ByteArray? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else {
                Log.w(TAG, "Unsplash download failed: HTTP ${response.code}")
                null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unsplash download failed", e)
        null
    }
}
