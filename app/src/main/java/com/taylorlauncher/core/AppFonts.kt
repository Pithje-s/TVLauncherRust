package com.taylorlauncher.core

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.taylorlauncher.R

object AppFonts {

    data class Option(val key: String, val label: String)

    const val DEFAULT = "roboto"

    val options = listOf(
        Option(DEFAULT, "Roboto"),
        Option("arial", "Arial"),
        Option("manrope", "Manrope"),
        Option("montserrat", "Montserrat"),
        Option("nunito_sans", "Nunito Sans"),
        Option("proxima_nova", "Proxima Nova")
    )

    fun typeface(context: Context, key: String): Typeface {
        val res = when (key) {
            DEFAULT -> return Typeface.create("sans-serif", Typeface.NORMAL)
            "arial" -> R.font.arimo
            "manrope" -> R.font.manrope
            "montserrat" -> R.font.montserrat
            "nunito_sans" -> R.font.nunito_sans
            "proxima_nova" -> R.font.mulish
            else -> return try {

                Typeface.create(key, Typeface.NORMAL)
            } catch (e: Exception) {
                Typeface.DEFAULT
            }
        }
        return try {
            ResourcesCompat.getFont(context, res) ?: Typeface.DEFAULT
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
    }

    fun labelOf(key: String): String = options.firstOrNull { it.key == key }?.label ?: key
}
