package com.magicboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSuggestionsTest {
    @Test fun predictsByLanguage() {
        assertTrue("привет" in WordSuggestions.suggest("прив", "ru"))
        assertTrue("hello" in WordSuggestions.suggest("hel", "en"))
        assertTrue(WordSuggestions.suggest("hel", "ru").isEmpty())
    }

    @Test fun correctsKnownTyposAndPreservesUnknownWords() {
        assertEquals("привет", WordSuggestions.autoCorrect("превет", "ru"))
        assertEquals("the", WordSuggestions.autoCorrect("teh", "en"))
        assertNull(WordSuggestions.autoCorrect("Teh", "en"))
        assertNull(WordSuggestions.autoCorrect("foobar", "en"))
    }
}
