package com.magicboard.keyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.concurrent.Executors

/** Local reader for a manually pasted, shared, or selected encrypted message. */
class DecryptActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var revision = 0
    private var clearResult: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.background) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            Ui.pad(this, 20)
        }
        scroll.addView(column)
        val title = Ui.text(this, "Расшифровка", 28f).apply {
            Ui.bold(this)
            gravity = Gravity.START
        }
        column.addView(title)
        val token = EditText(this).apply {
            hint = "Вставьте сообщение ENC1: или MB2:"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled = false
            maxLines = 5
            filters = arrayOf(android.text.InputFilter.LengthFilter(CryptoEngine.MAX_TOKEN_LENGTH + 32))
            val shared = when (intent.action) {
                Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                else -> null
            }
            if (shared != null && shared.length <= CryptoEngine.MAX_TOKEN_LENGTH + 32) setText(shared.toString())
        }
        column.addView(token)
        val decrypt = Button(this).apply {
            text = "Расшифровать"
            isAllCaps = false
        }
        column.addView(decrypt)
        val output = Ui.text(this, "", 17f).apply {
            gravity = Gravity.START
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            isSaveEnabled = false
        }
        column.addView(output)
        clearResult = {
            output.text = ""
            decrypt.isEnabled = true
        }
        decrypt.setOnClickListener {
            val encoded = token.text.toString().trim()
            val request = ++revision
            val key = AppSettings.secretRevision(this)
            decrypt.isEnabled = false
            output.text = "Расшифровка…"
            worker.execute {
                val enc1 = CryptoEngine.isToken(encoded)
                val packet = if (enc1) null else OcrMessageCodec.Collector().acceptPackets(encoded).singleOrNull()
                val valid = enc1 || packet != null
                val passphrase = if (valid) AppSettings.loadSecret(this) else null
                val plaintext = if (passphrase != null) {
                    runCatching { packet?.decrypt(passphrase) ?: CryptoEngine.decrypt(encoded, passphrase) }.getOrNull()
                } else null
                runOnUiThread {
                    if (request != revision || isFinishing || isDestroyed) return@runOnUiThread
                    decrypt.isEnabled = true
                    output.text = when {
                        key != AppSettings.secretRevision(this) -> "Фраза изменилась. Повторите попытку."
                        !valid -> getString(R.string.invalid_token)
                        passphrase == null -> "Сначала сохраните общую фразу в настройках magicboard."
                        plaintext == null -> "Не удалось расшифровать. Проверьте фразу и целостность сообщения."
                        else -> plaintext
                    }
                }
            }
        }
        setContentView(scroll)
        Ui.applyInsets(scroll)
    }

    override fun onStop() {
        revision++
        clearResult?.invoke()
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
