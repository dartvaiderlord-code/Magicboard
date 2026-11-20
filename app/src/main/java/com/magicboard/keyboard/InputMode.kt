package com.magicboard.keyboard

internal enum class InputMode {
    NORMAL, BUFFER, OCR, LIVE;

    companion object {
        fun forEditor(preferred: InputMode?, protectedInputAllowed: Boolean, capturing: Boolean): InputMode =
            when {
                !protectedInputAllowed -> NORMAL
                preferred == LIVE -> LIVE
                preferred == OCR || capturing -> OCR
                else -> NORMAL
            }
    }
}
