package com.magicboard.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

/** Secret is encrypted locally with a non-exportable Android Keystore key. Not included in backups. */
object AppSettings {
    private const val PREF = "cipherboard_settings"
    private const val SECRET = "encrypted_phrase"
    private const val OCR_EXTRA_COPIES = "ocr_extra_copies"
    private const val KEY_ALIAS = "cipherboard_local_secret_v1"
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun language(c: Context): String = prefs(c).getString("language", "ru") ?: "ru"
    fun setLanguage(c: Context, lang: String) = prefs(c).edit().putString("language", lang).apply()
    fun hasSecret(c: Context): Boolean = prefs(c).contains(SECRET)
    fun secretRevision(c: Context): Int = prefs(c).getInt("secret_revision", 0)
    fun clearSecret(c: Context) = prefs(c).edit()
        .remove(SECRET)
        .putInt("secret_revision", secretRevision(c) + 1)
        .apply()
    fun autoCorrect(c: Context): Boolean = prefs(c).getBoolean("auto_correct", true)
    fun setAutoCorrect(c: Context, enabled: Boolean) =
        prefs(c).edit().putBoolean("auto_correct", enabled).apply()
    fun hapticFeedback(c: Context): Boolean = prefs(c).getBoolean("haptic_feedback", true)
    fun setHapticFeedback(c: Context, enabled: Boolean) =
        prefs(c).edit().putBoolean("haptic_feedback", enabled).apply()
    fun ocrExtraCopies(c: Context): Int =
        prefs(c).getInt(OCR_EXTRA_COPIES, 0).coerceIn(0, OcrMessageCodec.MAX_EXTRA_COPIES)
    fun setOcrExtraCopies(c: Context, count: Int) =
        prefs(c).edit().putInt(OCR_EXTRA_COPIES, count.coerceIn(0, OcrMessageCodec.MAX_EXTRA_COPIES)).apply()

    fun saveSecret(c: Context, phrase: String) {
        require(phrase.length >= 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, localKey())
        val bytes = phrase.toByteArray(Charsets.UTF_8)
        val output = try {
            cipher.iv + cipher.doFinal(bytes)
        } finally {
            bytes.fill(0)
        }
        prefs(c).edit()
            .putString(SECRET, Base64.encodeToString(output, Base64.NO_WRAP))
            .putInt("secret_revision", prefs(c).getInt("secret_revision", 0) + 1)
            .apply()
    }

    fun loadSecret(c: Context): String? {
        return try {
            val encoded = prefs(c).getString(SECRET, null) ?: return null
            val blob = Base64.decode(encoded, Base64.DEFAULT)
            if (blob.size < 29) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, localKey(create = false), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            val bytes = cipher.doFinal(blob.copyOfRange(12, blob.size))
            try {
                String(bytes, Charsets.UTF_8)
            } finally {
                bytes.fill(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    private fun localKey(create: Boolean = true): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "No local key" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
