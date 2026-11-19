package com.magicboard.keyboard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.TextView

object Ui {
    val background = Color.rgb(225, 233, 230)
    val key = Color.rgb(251, 253, 252)
    val special = Color.rgb(204, 221, 214)
    val accent = Color.rgb(20, 102, 82)
    val dark = Color.rgb(20, 45, 40)
    val privateSurface = Color.rgb(225, 247, 235)
    fun dp(c: Context, value: Int): Int = (value * c.resources.displayMetrics.density + .5f).toInt()
    fun rounded(color: Int, radius: Int, c: Context) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(c, radius).toFloat()
    }
    fun keyBackground(c: Context, color: Int, radius: Int = 9) = RippleDrawable(
        ColorStateList.valueOf(0x33225648),
        rounded(color, radius, c),
        null
    )
    fun text(c: Context, title: String, size: Float = 16f, color: Int = dark): TextView =
        TextView(c).apply {
            text = title
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            includeFontPadding = true
        }
    fun bold(v: TextView) { v.typeface = Typeface.DEFAULT_BOLD }
    fun pad(v: View, p: Int) { v.setPadding(dp(v.context, p), dp(v.context, p), dp(v.context, p), dp(v.context, p)) }

    fun applyInsets(view: View) {
        if (Build.VERSION.SDK_INT < 35 || view.context.applicationInfo.targetSdkVersion < 35) return
        view.setOnApplyWindowInsetsListener { target, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            target.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        view.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        view.requestApplyInsets()
    }
}
