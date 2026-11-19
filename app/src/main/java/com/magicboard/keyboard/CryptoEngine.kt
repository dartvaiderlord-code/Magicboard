package com.magicboard.keyboard

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Shared-passphrase interoperable message format: ENC1:base64url(salt16|iv12|GCM ciphertext+tag16). */
object CryptoEngine {
    private const val PREFIX = "ENC1:"
    private const val KDF_ROUNDS = 200_000
    const val MAX_MESSAGE_LENGTH = 4_000
    // A UTF-16 code unit can take up to three bytes in UTF-8.
    const val MAX_TOKEN_LENGTH = 5 + ((MAX_MESSAGE_LENGTH * 3 + 44) * 4 + 2) / 3
    private val random = SecureRandom()
    private val aad = "CipherBoard/ENC1".toByteArray(Charsets.UTF_8)
    private val tokenRegex = Regex("ENC1:[A-Za-z0-9_-]{59,}")
    private val embeddedToken = Regex("(?<![A-Za-z0-9_-])ENC1:[A-Za-z0-9_-]{59,${MAX_TOKEN_LENGTH - PREFIX.length}}(?![A-Za-z0-9_-])")

    fun isToken(text: CharSequence): Boolean =
        text.length in 64..MAX_TOKEN_LENGTH && tokenRegex.matches(text)

    fun visibleToken(text: CharSequence): String? {
        if (text.length > MAX_TOKEN_LENGTH + 512) return null
        val matches = embeddedToken.findAll(text).iterator()
        if (!matches.hasNext()) return null
        val match = matches.next().value
        return if (matches.hasNext()) null else match
    }

    fun encrypt(message: String, passphrase: String): String {
        require(passphrase.length >= 12) { "Нужен ключ минимум из 12 символов" }
        require(message.length <= MAX_MESSAGE_LENGTH) { "Сообщение слишком длинное" }
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val plaintext = message.toByteArray(Charsets.UTF_8)
        return try {
            val packed = salt + iv + cipher.doFinal(plaintext)
            PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(packed)
        } finally {
            plaintext.fill(0)
        }
    }

    fun decrypt(token: String, passphrase: String): String {
        require(passphrase.length >= 12) { "Ключ не установлен" }
        require(isToken(token)) { "Неверный формат" }
        val packed = Base64.getUrlDecoder().decode(token.removePrefix(PREFIX))
        require(packed.size >= 16 + 12 + 16) { "Повреждённое сообщение" }
        val salt = packed.copyOfRange(0, 16)
        val iv = packed.copyOfRange(16, 28)
        val encrypted = packed.copyOfRange(28, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(encrypted)
        return try {
            String(plaintext, Charsets.UTF_8).also { require(it.length <= MAX_MESSAGE_LENGTH) }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val password = passphrase.toCharArray()
        val spec = PBEKeySpec(password, salt, KDF_ROUNDS, 256)
        return try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                SecretKeySpec(key, "AES")
            } finally {
                key.fill(0)
            }
        } finally {
            password.fill('\u0000')
            spec.clearPassword()
        }
    }
}
