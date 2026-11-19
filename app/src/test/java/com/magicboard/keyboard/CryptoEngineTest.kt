package com.magicboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import java.util.Base64

class CryptoEngineTest {
    private val phrase = "magicboard test phrase 42"

    @Test fun decryptsIndependentEnc1Vector() {
        val token = "ENC1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaG69cFxuCW1mv2Zcbaf89rPEDJIZgHefN_SHswho-LzVZGA"
        assertEquals("Привет 🌍", CryptoEngine.decrypt(token, phrase))
    }

    @Test fun acceptsMaximumLengthUnicodeMessage() {
        val message = "界".repeat(CryptoEngine.MAX_MESSAGE_LENGTH)
        val token = CryptoEngine.encrypt(message, "длинная тестовая фраза")
        assertTrue(CryptoEngine.isToken(token))
        assertEquals(message, CryptoEngine.decrypt(token, "длинная тестовая фраза"))
        assertThrows(IllegalArgumentException::class.java) {
            CryptoEngine.encrypt(message + "x", phrase)
        }
    }

    @Test fun usesFreshSaltAndNonce() {
        fun packed() = Base64.getUrlDecoder().decode(CryptoEngine.encrypt("test", phrase).substringAfter(':'))
        val first = packed()
        val second = packed()
        assertNotEquals(first.slice(0 until 16), second.slice(0 until 16))
        assertNotEquals(first.slice(16 until 28), second.slice(16 until 28))
    }

    @Test fun rejectsWrongPhraseAndTampering() {
        val token = CryptoEngine.encrypt("Привет, hello 👋", phrase)
        assertThrows(GeneralSecurityException::class.java) { CryptoEngine.decrypt(token, phrase + "x") }
        val bytes = Base64.getUrlDecoder().decode(token.substringAfter(':'))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val changed = "ENC1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        assertThrows(GeneralSecurityException::class.java) { CryptoEngine.decrypt(changed, phrase) }
        assertThrows(IllegalArgumentException::class.java) { CryptoEngine.decrypt("ENC1:abc", phrase) }
    }
}
