package com.magicboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardLettersTest {
    @Test fun keepsTheWholeRussianAlphabetAvailable() {
        val letters = mutableSetOf<Char>()
        KeyboardLetters.rows("ru").forEachIndexed { row, keys ->
            keys.forEachIndexed { column, letter ->
                letters += letter
                letters.addAll(KeyboardLetters.alternatives(letter, row, column).filter(Char::isLetter))
            }
        }
        assertEquals(('а'..'я').toSet() + 'ё', letters)
    }

    @Test fun mapsDigitsToTheFirstTenKeysInBothLayouts() {
        for (language in listOf("ru", "en")) {
            val top = KeyboardLetters.rows(language).first()
            assertEquals("1234567890", top.indices.mapNotNull { KeyboardLetters.digit(0, it) }.joinToString(""))
        }
        assertNull(KeyboardLetters.digit(1, 0))
    }

    @Test fun respectsShiftOnAlternateRussianLetters() {
        assertEquals(listOf('ё', '5'), KeyboardLetters.alternatives('е', 0, 4))
        assertEquals(listOf('Ё', '5'), KeyboardLetters.alternatives('Е', 0, 4))
        assertEquals(listOf('Ъ'), KeyboardLetters.alternatives('Ь', 2, 6))
    }
}
