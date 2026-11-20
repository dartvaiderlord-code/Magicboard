package com.magicboard.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftStateTest {
    @Test fun doubleTapLocksUntilAnotherTap() {
        val shift = ShiftState()
        shift.tap(1000, 300)
        assertTrue(shift.uppercase)
        assertFalse(shift.locked)
        shift.tap(1250, 300)
        assertTrue(shift.locked)
        repeat(3) {
            assertFalse(shift.consumeLetter())
            assertTrue(shift.uppercase)
        }
        shift.tap(2000, 300)
        assertFalse(shift.uppercase)
    }

    @Test fun consumingOneShotShiftOrWaitingPreventsCapsLock() {
        val shift = ShiftState()
        shift.tap(1000, 300)
        assertTrue(shift.consumeLetter())
        shift.tap(1150, 300)
        assertFalse(shift.locked)
        shift.tap(1500, 300)
        assertFalse(shift.uppercase)
    }
}
