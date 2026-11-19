package com.magicboard.keyboard

import android.graphics.Rect
import android.media.Image

/** Coarse frame comparison outside our own overlays, to discard positions after scrolling. */
internal class OcrFrameSignature(image: Image, private val width: Int, private val height: Int) {
    private val pixels = IntArray(COLUMNS * ROWS)

    init {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val start = buffer.position()
        for (y in 0 until ROWS) {
            val line = (y * image.height / ROWS) * plane.rowStride
            for (x in 0 until COLUMNS) {
                val offset = line + (x * image.width / COLUMNS) * plane.pixelStride
                pixels[y * COLUMNS + x] = buffer.getInt(start + offset)
            }
        }
    }

    fun matches(other: OcrFrameSignature, masked: List<Rect> = emptyList(), region: Rect? = null): Boolean {
        if (width != other.width || height != other.height) return false
        var mismatched = 0
        var compared = 0
        val masks = masked.map { Rect(it).apply { inset(-4, -4) } }
        for (y in 4 until ROWS - 2) {
            val screenY = y * height / ROWS
            for (x in 0 until COLUMNS) {
                val screenX = x * width / COLUMNS
                if (region != null && !region.contains(screenX, screenY)) continue
                if (masks.any { it.contains(screenX, screenY) }) continue
                compared++
                val index = y * COLUMNS + x
                if (pixels[index] != other.pixels[index] && ++mismatched > 5) return false
            }
        }
        return compared > 0
    }

    private companion object {
        const val COLUMNS = 48
        const val ROWS = 80
    }
}
