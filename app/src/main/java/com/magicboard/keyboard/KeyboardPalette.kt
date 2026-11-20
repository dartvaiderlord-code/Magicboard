package com.magicboard.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build

internal data class KeyboardPalette(
    val surface: Int,
    val key: Int,
    val outline: Int,
    val action: Int,
    val selected: Int,
    val text: Int,
    val secondaryText: Int,
    val selectedText: Int
) {
    fun keyBackground(context: Context, color: Int, radius: Int, outlined: Boolean = false): RippleDrawable {
        val fill = GradientDrawable().apply {
            setColor(color)
            cornerRadius = Ui.dp(context, radius).toFloat()
            if (outlined) setStroke(Ui.dp(context, 1), outline)
        }
        val ripple = Color.argb(36, Color.red(text), Color.green(text), Color.blue(text))
        return RippleDrawable(ColorStateList.valueOf(ripple), fill, null)
    }

    companion object {
        fun from(context: Context): KeyboardPalette {
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            if (Build.VERSION.SDK_INT >= 31) {
                fun tone(id: Int) = context.getColor(id)
                return if (dark) KeyboardPalette(
                    tone(android.R.color.system_neutral2_900),
                    tone(android.R.color.system_neutral1_800),
                    tone(android.R.color.system_neutral2_600),
                    tone(android.R.color.system_accent2_800),
                    tone(android.R.color.system_accent1_300),
                    tone(android.R.color.system_neutral1_50),
                    tone(android.R.color.system_neutral2_300),
                    tone(android.R.color.system_accent1_900)
                ) else KeyboardPalette(
                    tone(android.R.color.system_accent2_50),
                    tone(android.R.color.system_neutral1_10),
                    tone(android.R.color.system_neutral2_200),
                    tone(android.R.color.system_accent2_100),
                    tone(android.R.color.system_accent1_200),
                    tone(android.R.color.system_neutral1_900),
                    tone(android.R.color.system_neutral2_700),
                    tone(android.R.color.system_accent1_900)
                )
            }
            return if (dark) KeyboardPalette(
                0xFF262122.toInt(), 0xFF383134.toInt(), 0xFF514649.toInt(), 0xFF554047.toInt(),
                0xFFF3B6C4.toInt(), 0xFFFFEFF2.toInt(), 0xFFCEC0C4.toInt(), 0xFF3D242C.toInt()
            ) else KeyboardPalette(
                0xFFF9EBE8.toInt(), 0xFFFFF7F5.toInt(), 0xFFE7D1CE.toInt(), 0xFFF6DAD5.toInt(),
                0xFFF7C9C2.toInt(), 0xFF302324.toInt(), 0xFF695051.toInt(), 0xFF3D2829.toInt()
            )
        }
    }
}
