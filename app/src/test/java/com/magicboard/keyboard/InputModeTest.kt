package com.magicboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class InputModeTest {
    @Test fun liveSurvivesEditorRestartWithOrWithoutAnOcrSession() {
        assertEquals(InputMode.LIVE, InputMode.forEditor(InputMode.LIVE, true, true))
        assertEquals(InputMode.LIVE, InputMode.forEditor(InputMode.LIVE, true, false))
    }

    @Test fun restrictedFieldsUseNormalInput() {
        assertEquals(InputMode.NORMAL, InputMode.forEditor(InputMode.LIVE, false, true))
        assertEquals(InputMode.NORMAL, InputMode.forEditor(InputMode.OCR, false, true))
    }

    @Test fun restoresOcrOnExplicitSelectionOrActiveCapture() {
        assertEquals(InputMode.OCR, InputMode.forEditor(InputMode.OCR, true, false))
        assertEquals(InputMode.OCR, InputMode.forEditor(null, true, true))
        assertEquals(InputMode.NORMAL, InputMode.forEditor(null, true, false))
    }
}
