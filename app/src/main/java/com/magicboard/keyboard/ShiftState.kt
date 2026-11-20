package com.magicboard.keyboard

internal class ShiftState {
    private enum class Mode { LOWER, ONCE, LOCKED }

    private var mode = Mode.LOWER
    private var firstTap = 0L
    val uppercase get() = mode != Mode.LOWER
    val locked get() = mode == Mode.LOCKED

    fun tap(now: Long, doubleTapTimeout: Int) {
        mode = when (mode) {
            Mode.LOWER -> Mode.ONCE.also { firstTap = now }
            Mode.ONCE -> if (now - firstTap <= doubleTapTimeout) Mode.LOCKED else Mode.LOWER
            Mode.LOCKED -> Mode.LOWER
        }
    }

    fun consumeLetter(): Boolean {
        if (mode != Mode.ONCE) return false
        reset()
        return true
    }

    fun reset() {
        mode = Mode.LOWER
    }
}
