package com.magicboard.keyboard

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.vision.text.Text

internal data class PositionedRow(val row: OcrMessageCodec.Row, val bounds: Rect)
internal data class OverlayMessage(val bounds: Rect, val plaintext: String)

internal fun positionedRows(result: Text): List<PositionedRow> {
    val content = StringBuilder()
    val sections = mutableListOf<Pair<IntRange, Rect>>()
    for (line in result.textBlocks.flatMap { it.lines }) {
        if (content.length >= 24_000) break
        val start = content.length
        content.append(line.text).append('\n')
        var cursor = 0
        val words = line.elements.mapNotNull { word ->
            val bounds = word.boundingBox ?: return@mapNotNull null
            val index = line.text.indexOf(word.text, cursor)
            if (index < 0 || word.text.isEmpty()) return@mapNotNull null
            cursor = index + word.text.length
            (start + index until start + cursor) to bounds
        }
        if (words.isNotEmpty()) sections.addAll(words)
        else line.boundingBox?.let { sections.add((start until start + line.text.length) to it) }
    }
    return OcrMessageCodec.scan(content).mapNotNull { match ->
        val bounds = Rect()
        for ((range, rect) in sections) {
            if (range.first > match.range.last) break
            if (range.last >= match.range.first) bounds.union(rect)
        }
        if (bounds.isEmpty) null else PositionedRow(match.row, bounds)
    }
}

internal class OcrOverlay(private val context: Context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val cards = mutableListOf<Pair<Rect, TextView>>()
    val covered: List<Rect> get() = cards.map { it.first }

    @Suppress("RtlHardcoded")
    fun show(messages: List<OverlayMessage>, width: Int, height: Int): Boolean {
        clear()
        if (!Settings.canDrawOverlays(context)) return false
        for (message in messages.take(6)) {
            val rect = Rect(message.bounds)
            rect.inset(-Ui.dp(context, 2), -Ui.dp(context, 2))
            if (!rect.intersect(0, 0, width, height) || cards.any { Rect.intersects(it.first, rect) }) continue

            val card = Ui.text(context, message.plaintext, 16f).apply {
                gravity = Gravity.START or Gravity.TOP
                setPadding(Ui.dp(context, 6), Ui.dp(context, 1), Ui.dp(context, 6), 0)
                background = Ui.rounded(Ui.privateSurface, 6, context)
                maxLines = ((rect.height() - paddingTop) / lineHeight).coerceAtLeast(1)
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val parameters = WindowManager.LayoutParams(
                rect.width(), rect.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.LEFT or Gravity.TOP
                x = rect.left
                y = rect.top
                setTitle("magicboard OCR")
                // Android 12 allows touches through a single overlay below the obscuring-opacity limit.
                alpha = 0.78f
                if (android.os.Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            }
            try {
                windows.addView(card, parameters)
                cards.add(rect to card)
            } catch (_: WindowManager.BadTokenException) {
                clear()
                return false
            } catch (_: SecurityException) {
                clear()
                return false
            }
        }
        return true
    }

    fun clear() {
        for ((_, card) in cards) {
            card.text = ""
            try {
                windows.removeViewImmediate(card)
            } catch (_: IllegalArgumentException) {
                // Android already detached this window.
            }
        }
        cards.clear()
    }
}
