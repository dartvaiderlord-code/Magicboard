package com.magicboard.keyboard

internal object KeyboardLetters {
    private const val DIGITS = "1234567890"
    private val russian = listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
    private val english = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    fun rows(language: String): List<String> = if (language == "ru") russian else english

    fun digit(row: Int, column: Int): Char? = if (row == 0) DIGITS.getOrNull(column) else null

    fun alternatives(letter: Char, row: Int, column: Int): List<Char> = buildList {
        when (letter) {
            'е' -> add('ё')
            'Е' -> add('Ё')
            'ь' -> add('ъ')
            'Ь' -> add('Ъ')
        }
        digit(row, column)?.let(::add)
    }
}
