package com.magicboard.keyboard

import com.google.zxing.common.reedsolomon.GenericGF
import com.google.zxing.common.reedsolomon.ReedSolomonDecoder
import com.google.zxing.common.reedsolomon.ReedSolomonEncoder
import com.google.zxing.common.reedsolomon.ReedSolomonException
import java.security.MessageDigest

/** A printed representation of the authenticated ENC1 bytes, with independently protected rows. */
internal object OcrMessageCodec {
    const val MAX_PLAINTEXT_BYTES = 500
    const val MAX_EXTRA_COPIES = 3
    private const val MAX_PACKET_BYTES = MAX_PLAINTEXT_BYTES + 44
    private const val MAX_ROWS = 64
    private const val ROW_SYMBOLS = 31
    private const val PARITY_SYMBOLS = 8
    private const val DATA_SYMBOLS = ROW_SYMBOLS - PARITY_SYMBOLS
    private const val PAYLOAD_SYMBOLS = DATA_SYMBOLS - 8
    private const val MARKER = "MB2:"
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    // GF(32) with primitive polynomial x⁵ + x² + 1.
    private val field = GenericGF(0x25, 32, 1)
    private val marker = Regex("(?<![0-9A-Z])M[B8][2Z]\\s*[:;]", RegexOption.IGNORE_CASE)

    data class Row(val id: String, val index: Int, val total: Int, val data: String) {
        val messageKey get() = id to total
    }
    data class Match(val row: Row, val range: IntRange)
    class Packet(private val bytes: ByteArray, val rows: List<Row>) {
        val token by lazy { CryptoEngine.encodeToken(bytes) }
        fun decrypt(phrase: String): String = CryptoEngine.decryptPacket(bytes, phrase)
    }

    fun encrypt(message: String, phrase: String, extraCopies: Int = 0): String {
        require(message.toByteArray(Charsets.UTF_8).size <= MAX_PLAINTEXT_BYTES)
        require(extraCopies in 0..MAX_EXTRA_COPIES)
        val packet = CryptoEngine.encryptPacket(message, phrase)
        val stream = encode32(byteArrayOf((packet.size shr 8).toByte(), packet.size.toByte()) + packet)
        val id = messageId(packet)
        val parts = stream.chunked(PAYLOAD_SYMBOLS)
        val encoder = ReedSolomonEncoder(field)
        val rows = parts.mapIndexed { index, part ->
            val data = id + pair(index) + pair(parts.size) + part.padEnd(PAYLOAD_SYMBOLS, '0')
            val codeword = IntArray(ROW_SYMBOLS)
            data.forEachIndexed { i, char -> codeword[i] = ALPHABET.indexOf(char) }
            encoder.encode(codeword, PARITY_SYMBOLS)
            buildString(MARKER.length + ROW_SYMBOLS) {
                append(MARKER)
                codeword.forEach { append(ALPHABET[it]) }
            }
        }.joinToString("\n")
        // Spread the copies across the message so loss of a contiguous block can be recovered.
        return List(extraCopies + 1) { rows }.joinToString("\n")
    }

    fun scan(text: CharSequence): List<Match> {
        val bounded = text.take(24_000)
        val starts = marker.findAll(bounded).take(256).toList()
        if (starts.isEmpty()) return emptyList()
        val decoder = ReedSolomonDecoder(field)
        val decoded = mutableMapOf<String, Row?>()
        return starts.mapIndexedNotNull { index, markerMatch ->
            val end = starts.getOrNull(index + 1)?.range?.first ?: bounded.length
            val symbols = IntArray(ROW_SYMBOLS)
            var count = 0
            var lastSymbol = 0
            for (offset in (markerMatch.range.last + 1) until end) {
                val char = bounded[offset]
                if (char.isWhitespace()) continue
                val symbol = symbolFor(char)
                if (symbol < 0) break
                symbols[count++] = symbol
                lastSymbol = offset
                if (count == ROW_SYMBOLS) break
            }
            if (count != ROW_SYMBOLS) return@mapIndexedNotNull null
            val raw = buildString(ROW_SYMBOLS) { symbols.forEach { append(ALPHABET[it]) } }
            if (!decoded.containsKey(raw)) decoded[raw] = decodeRow(symbols, decoder)
            val row = decoded[raw] ?: return@mapIndexedNotNull null
            Match(row, markerMatch.range.first..lastSymbol)
        }
    }

    private fun decodeRow(symbols: IntArray, decoder: ReedSolomonDecoder): Row? {
        try {
            decoder.decode(symbols, PARITY_SYMBOLS)
        } catch (_: ReedSolomonException) {
            return null
        }
        val data = buildString(DATA_SYMBOLS) {
            for (index in 0 until DATA_SYMBOLS) append(ALPHABET[symbols[index]])
        }
        val index = readPair(data, 4)
        val total = readPair(data, 6)
        if (total !in 1..MAX_ROWS || index >= total) return null
        return Row(data.take(4), index, total, data.substring(8))
    }

    private fun assemble(rows: List<Row>): ByteArray? {
        val first = rows.firstOrNull() ?: return null
        if (rows.size != first.total) return null
        if (rows.indices.any { index ->
                val row = rows[index]
                row.index != index || row.total != first.total || row.id != first.id
            }) return null
        val bytes = decode32(rows.joinToString("") { it.data })
        if (bytes.size < 2) return null
        val length = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
        if (length !in 44..MAX_PACKET_BYTES || length + 2 > bytes.size) return null
        val requiredRows = ((length + 2) * 8 + 5 * PAYLOAD_SYMBOLS - 1) / (5 * PAYLOAD_SYMBOLS)
        if (requiredRows != rows.size) return null
        val packet = bytes.copyOfRange(2, length + 2)
        if (messageId(packet) != first.id) return null
        return packet
    }

    /** Retains only validated ciphertext fragments and can assemble rows across captures. */
    class Collector {
        private val pending = LinkedHashMap<Pair<String, Int>, MutableMap<Int, Row>>()
        private val completed = LinkedHashMap<Pair<String, Int>, List<Row>>()
        private val emitted = LinkedHashSet<String>()

        fun accept(text: CharSequence): List<String> = acceptPackets(text).map { it.token }

        fun acceptPackets(text: CharSequence): List<Packet> = acceptRows(scan(text).map { it.row })

        fun acceptRows(rows: List<Row>): List<Packet> {
            val complete = ArrayList<Packet>()
            for (row in rows) {
                val key = row.messageKey
                val known = completed[key]
                if (key !in pending && known?.getOrNull(row.index) == row) continue
                val parts = pending.getOrPut(key) { known.orEmpty().associateByTo(linkedMapOf()) { it.index } }
                if (parts.put(row.index, row) == row) continue
                if (parts.size != row.total) continue
                val ordered = parts.values.sortedBy { it.index }
                val bytes = assemble(ordered) ?: continue
                val packet = Packet(bytes, ordered)
                pending.remove(key)
                completed[key] = ordered
                if (emitted.add(packet.token)) complete.add(packet)
            }
            while (pending.size > 12) pending.remove(pending.keys.first())
            while (completed.size > 64) completed.remove(completed.keys.first())
            while (emitted.size > 64) emitted.remove(emitted.first())
            return complete
        }

        fun clear() {
            pending.clear()
            completed.clear()
            emitted.clear()
        }
    }

    private fun symbolFor(char: Char): Int = when (val upper = char.uppercaseChar()) {
        'O' -> 0
        'I', 'L' -> 1
        else -> ALPHABET.indexOf(upper)
    }

    private fun pair(value: Int): String =
        "${ALPHABET[value / 32]}${ALPHABET[value % 32]}"

    private fun readPair(text: String, offset: Int): Int =
        ALPHABET.indexOf(text[offset]) * 32 + ALPHABET.indexOf(text[offset + 1])

    private fun messageId(packet: ByteArray): String =
        encode32(MessageDigest.getInstance("SHA-256").digest(packet)).take(4)

    private fun encode32(bytes: ByteArray): String = buildString {
        var state = 0
        var bits = 0
        for (byte in bytes) {
            state = (state shl 8) or (byte.toInt() and 255)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                append(ALPHABET[(state shr bits) and 31])
            }
        }
        if (bits > 0) append(ALPHABET[(state shl (5 - bits)) and 31])
    }

    private fun decode32(text: String): ByteArray {
        val bytes = ByteArray(text.length * 5 / 8)
        var state = 0
        var bits = 0
        var written = 0
        for (char in text) {
            state = (state shl 5) or ALPHABET.indexOf(char)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                bytes[written++] = (state shr bits).toByte()
            }
        }
        return bytes
    }
}
