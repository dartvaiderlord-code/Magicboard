package com.magicboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrMessageCodecTest {
    private val phrase = "magicboard test phrase 42"

    @Test fun locatesWrappedRowsWithoutIncludingAdjacentChatText() {
        val row = OcrMessageCodec.encrypt("Test overlay", phrase).lineSequence().first()
        val wrapped = row.take(18) + "\n" + row.drop(18)
        val screen = "Alice\n$wrapped   13:25\nBob"
        val match = OcrMessageCodec.scan(screen).single()
        assertEquals(wrapped, screen.substring(match.range))
        assertEquals(0, match.row.index)
    }

    @Test fun correctsFourSubstitutionsPerRowWithWrapping() {
        val clear = "Привет 🌍\nHello, OCR!"
        val text = OcrMessageCodec.encrypt(clear, phrase).lines().joinToString("\n") { row ->
            val chars = row.toCharArray()
            for (offset in listOf(4, 11, 22, 34)) {
                chars[offset] = if (chars[offset] == 'X') 'Y' else 'X'
            }
            String(chars).chunked(18).joinToString("\n")
        }
        val token = OcrMessageCodec.Collector().accept(text).single()
        assertEquals(clear, CryptoEngine.decrypt(token, phrase))
    }

    @Test fun assemblesOutOfOrderAcrossFramesAndEmitsOnce() {
        val clear = "Сообщение " + "🛰".repeat(30)
        val rows = OcrMessageCodec.encrypt(clear, phrase).lines().reversed()
        val collector = OcrMessageCodec.Collector()
        assertTrue(collector.accept(rows.dropLast(1).joinToString("\n")).isEmpty())
        val token = collector.accept(rows.last().replace("MB2:", "M8Z;")).single()
        assertEquals(clear, CryptoEngine.decrypt(token, phrase))
        assertTrue(collector.accept(rows.joinToString("\n")).isEmpty())
    }

    @Test fun recognizesAmbiguousGlyphsWithoutChangingOriginalAlphabet() {
        val clear = "Обмен"
        val text = OcrMessageCodec.encrypt(clear, phrase)
        assertFalse(text.lineSequence().any { it.substringAfter(':').any { c -> c in "ILOU" } })
        val read = text.replace('0', 'O').replace('1', 'l')
        assertEquals(clear, CryptoEngine.decrypt(OcrMessageCodec.Collector().accept(read).single(), phrase))
    }

    @Test fun boundsMessageLengthByUtf8Bytes() {
        val max = "Я".repeat(250)
        val text = OcrMessageCodec.encrypt(max, phrase)
        val token = OcrMessageCodec.Collector().accept(text).single()
        assertEquals(max, CryptoEngine.decrypt(token, phrase))
        assertThrows(IllegalArgumentException::class.java) { OcrMessageCodec.encrypt(max + "!", phrase) }
    }

    @Test fun extraCopiesRecoverMissingRowsWithoutEmittingMultipleMessages() {
        val clear = "Я".repeat(250)
        val copies = OcrMessageCodec.MAX_EXTRA_COPIES + 1
        val lines = OcrMessageCodec.encrypt(clear, phrase, extraCopies = copies - 1).lines()
        val rows = lines.size / copies
        assertEquals(List(copies) { lines.take(rows) }.flatten(), lines)

        val withMissingRows = lines.filterIndexed { index, _ -> index % rows % copies == index / rows }
        val collector = OcrMessageCodec.Collector()
        val packet = collector.acceptPackets(withMissingRows.joinToString("\n")).single()
        assertEquals(clear, packet.decrypt(phrase))
        assertEquals(clear, CryptoEngine.decrypt(packet.token, phrase))
        assertTrue(collector.accept(lines.joinToString("\n")).isEmpty())
        assertEquals(clear, OcrMessageCodec.Collector().acceptPackets(lines.joinToString("\n")).single().decrypt(phrase))
        assertThrows(IllegalArgumentException::class.java) {
            OcrMessageCodec.encrypt(clear, phrase, extraCopies = copies)
        }
    }
}
