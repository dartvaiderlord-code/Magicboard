package com.magicboard.keyboard

import android.graphics.Color
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Standalone IME: no third-party keyboard SDK or network capability. */
class CipherKeyboardService : InputMethodService() {
    private enum class Mode { NORMAL, BUFFER, LIVE }
    private var mode = Mode.NORMAL
    private var lang = "ru"
    private var upper = false
    private var symbols = false
    private var emojis = false
    private var emojiCategory = 0
    private var passwordField = false
    private var numberField = false
    private var predictions = false
    private var bufferBusy = false
    private var selectionStart = -1
    private var selectionEnd = -1
    private var selectionRevision = 0
    private var bufferRevision = 0
    private val buffer = StringBuilder()
    private var lastLiveToken = ""
    private var liveRevision = 0
    private var liveWorking = false
    private var livePending = false
    private var liveStatus = ""
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var liveTask: Runnable? = null
    private var base: LinearLayout? = null
    private var suggestions: LinearLayout? = null
    private var preview: TextView? = null
    private var actionRow: LinearLayout? = null
    private var tip: TextView? = null

    override fun onCreate() {
        super.onCreate()
        lang = AppSettings.language(this)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        resetPrivateInput()
        symbols = false
        emojis = false
        upper = false
        val type = attribute?.inputType ?: 0
        val inputClass = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        passwordField = when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        numberField = inputClass in listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)
        predictions = inputClass == InputType.TYPE_CLASS_TEXT && !passwordField &&
            type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0 &&
            (attribute?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0 &&
            variation !in listOf(InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_FILTER)
        selectionStart = attribute?.initialSelStart ?: -1
        selectionEnd = attribute?.initialSelEnd ?: -1
        selectionRevision++
        if (base != null) render()
    }

    override fun onCreateInputView(): View {
        base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(Ui.background)
            setPadding(Ui.dp(context, 3), Ui.dp(context, 3), Ui.dp(context, 3), Ui.dp(context, 7))
        }
        render()
        return base!!
    }

    override fun onFinishInput() {
        resetPrivateInput()
        render()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        resetPrivateInput()
        render()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (selectionStart != newSelStart || selectionEnd != newSelEnd) selectionRevision++
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        if (mode == Mode.NORMAL && isInputViewShown) updateUi()
    }

    private fun clearBuffer() {
        for (index in buffer.indices) buffer.setCharAt(index, '\u0000')
        buffer.setLength(0)
        bufferRevision++
    }

    private fun resetPrivateInput() {
        clearBuffer()
        lastLiveToken = ""
        liveRevision++
        liveTask?.let(handler::removeCallbacks)
        liveTask = null
        livePending = false
        liveStatus = ""
        mode = Mode.NORMAL
        updateSecureFlag()
    }

    private fun updateSecureFlag() {
        val window = window?.window ?: return
        if (mode == Mode.NORMAL) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onDestroy() {
        resetPrivateInput()
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        val root = base ?: return
        preview?.text = ""
        suggestions?.removeAllViews()
        root.removeAllViews()
        val tabs = row(root, 38)
        val modes = if (passwordField || numberField) listOf(Mode.NORMAL to "Обычный") else
            listOf(Mode.NORMAL to "Обычный", Mode.BUFFER to "🔒 Буфер", Mode.LIVE to "⚡ Live")
        modes.forEach { (m, label) ->
            val tv = Ui.text(this, label, 13f, if (mode == m) Color.WHITE else Ui.dark).apply {
                if (mode == m) Ui.bold(this)
                background = Ui.keyBackground(context, if (mode == m) Ui.accent else Ui.background, 16)
                isSelected = mode == m
                setOnClickListener { changeMode(m) }
            }
            tabs.addView(tv, LinearLayout.LayoutParams(0, -1, 1f).apply {
                leftMargin = Ui.dp(this@CipherKeyboardService, 3)
                rightMargin = Ui.dp(this@CipherKeyboardService, 3)
            })
        }

        tip = Ui.text(this, "", 12f).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0)
            visibility = if (mode == Mode.NORMAL) View.GONE else View.VISIBLE
        }
        root.addView(tip, LinearLayout.LayoutParams(-1, Ui.dp(this, 24)))
        preview = Ui.text(this, "", 13f).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 10), 0)
            background = Ui.rounded(Ui.privateSurface, 8, context)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.START
            visibility = if (mode == Mode.NORMAL) View.GONE else View.VISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply {
            leftMargin = Ui.dp(this@CipherKeyboardService, 3)
            rightMargin = Ui.dp(this@CipherKeyboardService, 3)
            bottomMargin = Ui.dp(this@CipherKeyboardService, 3)
        })
        suggestions = row(root, 40).apply {
            importantForAccessibility = if (mode == Mode.NORMAL) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            visibility = if (passwordField || numberField) View.GONE else View.VISIBLE
        }
        actionRow = row(root, 39).apply {
            visibility = if (mode == Mode.NORMAL) View.GONE else View.VISIBLE
        }
        if (emojis) renderEmojis(root)
        else if (symbols || numberField) renderSymbols(root)
        else renderLetters(root)
        updateUi()
    }

    private fun key(row: LinearLayout, value: String, width: Float = 1f, special: Boolean = false, action: () -> Unit) {
        val tv = Ui.text(this, value, if (value.length > 4) 14f else 20f).apply {
            background = Ui.keyBackground(context, if (special) Ui.special else Ui.key)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            contentDescription = when (value) {
                "⇧" -> "Регистр"
                "⌫" -> "Удалить"
                "↵" -> "Ввод"
                "🌐" -> "Сменить язык"
                "😊" -> "Эмодзи"
                "?123" -> "Цифры и символы"
                "Русский", "English", "Пробел" -> if (row === suggestions) "Сменить язык" else "Пробел, $value"
                else -> null
            }
            setOnClickListener { action() }
            isClickable = true
        }
        row.addView(tv, LinearLayout.LayoutParams(0, -1, width).apply {
            leftMargin = Ui.dp(this@CipherKeyboardService, 2)
            rightMargin = Ui.dp(this@CipherKeyboardService, 2)
            topMargin = Ui.dp(this@CipherKeyboardService, 3)
            bottomMargin = Ui.dp(this@CipherKeyboardService, 3)
        })
    }

    private fun row(root: LinearLayout, height: Int = 47): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        root.addView(this, LinearLayout.LayoutParams(-1, Ui.dp(this@CipherKeyboardService, if (landscape) height * 3 / 4 else height)))
    }

    private fun renderLetters(root: LinearLayout) {
        val layouts = if (lang == "ru") listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбюё")
            else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        layouts.forEachIndexed { i, letters ->
            val r = row(root)
            if (i == 2) key(r, "⇧", 1.3f, true) { upper = !upper; render() }
            letters.forEach { ch ->
                val letter = if (upper) ch.uppercaseChar().toString() else ch.toString()
                key(r, letter) { insert(letter) }
            }
            if (i == 2) key(r, "⌫", 1.3f, true) { backspace() }
        }
        val r = row(root, 51)
        key(r, "?123", 1.4f, true) { symbols = true; render() }
        key(r, "😊", 1.05f, true) { emojis = true; render() }
        key(r, "🌐", 1.05f, true) { changeLanguage() }
        key(r, ",", .8f) { insert(",") }
        key(r, if (lang == "ru") "Русский" else "English", 4.4f) { insert(" ") }
        key(r, ".", .8f) { insert(".") }
        key(r, "↵", 1.35f, true) { enter() }
    }

    private fun renderSymbols(root: LinearLayout) {
        val rows = if (numberField) listOf("123", "456", "789", "-0.+") else
            listOf("1234567890", "@#\$%&-+()/", "*\"':;!?=_.")
        rows.forEach { s ->
            val r = row(root)
            s.forEach { c -> key(r, c.toString()) { insert(c.toString()) } }
        }
        val r = row(root, 51)
        if (!numberField) {
            key(r, if (lang == "ru") "АБВ" else "ABC", 1.3f, true) { symbols = false; emojis = false; render() }
            key(r, "😊", 1f, true) { emojis = true; render() }
            key(r, "Пробел", 4f) { insert(" ") }
        }
        key(r, "⌫", 1f, true) { backspace() }
        key(r, "↵", 1f, true) { enter() }
    }

    private fun renderEmojis(root: LinearLayout) {
        val categories = listOf(
            "😀 😂 🥰 😍 😎 😭 😡 🤔 😊 😘 😅 😢 🥺 🙂 🙃 🫠".split(" "),
            "👍 👎 ❤️ 🔥 💯 🙏 🎉 💀 🤝 👀 👋 ✌️ 🤞 💪 💚 ⭐".split(" "),
            "🐶 🐱 🐸 🐼 🦊 🐈 🌱 🌻 🌍 🌙 ☀️ 🌈 ☕ 🍕 ✨ 🚀".split(" ")
        )
        val categoryRow = row(root)
        listOf("Лица", "Жесты", "Природа").forEachIndexed { index, name ->
            key(categoryRow, name, special = emojiCategory == index) {
                emojiCategory = index
                render()
            }
        }
        categories[emojiCategory].chunked(8).forEach { entries ->
            val r = row(root)
            entries.forEach { emoji -> key(r, emoji) { insert(emoji) } }
        }
        val r = row(root, 51)
        key(r, if (lang == "ru") "АБВ" else "ABC", 1.1f, true) { emojis = false; symbols = false; render() }
        key(r, "Пробел", 3f) { insert(" ") }
        key(r, "⌫", 1f, true) { backspace() }
        key(r, "↵", 1f, true) { enter() }
    }

    private fun changeLanguage() {
        lang = if (lang == "ru") "en" else "ru"
        AppSettings.setLanguage(this, lang)
        upper = false
        render()
    }

    private fun changeMode(newMode: Mode) {
        if (mode == newMode || (passwordField || numberField) && newMode != Mode.NORMAL) return
        if (newMode == Mode.LIVE && !isEmptyEditor()) {
            toast("Для Live откройте пустое поле ввода")
            return
        }
        resetPrivateInput()
        mode = newMode
        symbols = false
        emojis = false
        updateSecureFlag()
        render()
    }

    private fun isEmptyEditor(): Boolean {
        val ic = currentInputConnection ?: return false
        return selectionStart == 0 && selectionEnd == 0 &&
            ic.getTextBeforeCursor(1, 0)?.isEmpty() == true &&
            ic.getTextAfterCursor(1, 0)?.isEmpty() == true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (mode != Mode.NORMAL && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_DEL -> backspace()
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> enter()
                else -> {
                    val value = event.unicodeChar
                    if (!Character.isValidCodePoint(value) || Character.isISOControl(value)) {
                        return super.onKeyDown(keyCode, event)
                    }
                    insert(String(Character.toChars(value)))
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun insert(value: String) {
        val wasUpper = upper
        val word = if (value == " " && AppSettings.autoCorrect(this)) currentWord() else ""
        val correction = if (word.isNotEmpty()) WordSuggestions.autoCorrect(word, lang) else null
        if (mode == Mode.NORMAL) {
            val ic = currentInputConnection ?: return
            if (correction == null) ic.commitText(value, 1)
            else replaceWord(ic, word, correction + value, value)
        } else {
            if (correction == null) updateBuffer(0, value)
            else updateBuffer(word.length, correction + value)
        }
        if (value.any { it.isLetter() }) upper = false
        if (wasUpper && !upper) render() else updateUi()
    }

    private fun backspace() {
        if (mode == Mode.NORMAL) {
            val ic = currentInputConnection ?: return
            if (selectionStart != selectionEnd) {
                ic.commitText("", 1)
            } else {
                val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
                if (before.isNotEmpty()) ic.deleteSurroundingText(lastCharacterLength(before), 0)
            }
        } else {
            if (buffer.isNotEmpty()) updateBuffer(lastCharacterLength(buffer.takeLast(64).toString()), "")
        }
        updateUi()
    }

    private fun lastCharacterLength(value: String): Int {
        val boundaries = BreakIterator.getCharacterInstance().apply { setText(value) }
        boundaries.last()
        return value.length - boundaries.previous()
    }

    private fun enter() {
        if (mode != Mode.NORMAL) { insert("\n"); return }
        val action = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (action == EditorInfo.IME_ACTION_UNSPECIFIED || !sendDefaultEditorAction(true)) {
            currentInputConnection?.commitText("\n", 1)
        }
        updateUi()
    }

    private fun updateUi() {
        tip?.text = when (mode) {
            Mode.NORMAL -> ""
            Mode.BUFFER -> "Текст хранится в клавиатуре до вставки"
            Mode.LIVE -> liveStatus.ifEmpty { "Live: наберите сообщение в пустом поле" }
        }
        preview?.text = when (mode) {
            Mode.NORMAL -> ""
            Mode.BUFFER, Mode.LIVE -> if (buffer.isEmpty()) "Введите сообщение…" else buffer.takeLast(160).toString()
        }
        val bar = suggestions ?: return
        bar.removeAllViews()
        val word = currentWord()
        val guesses = WordSuggestions.suggest(word, lang)
        when {
            guesses.isNotEmpty() -> guesses.forEach { suggestion ->
                key(bar, suggestion, 1f, true) { useSuggestion(suggestion) }
            }
            word.isNotEmpty() -> key(bar, word, 1f, true) { insert(" ") }
            mode == Mode.NORMAL && !passwordField && !numberField -> {
                key(bar, "Настройки", 1f, true) {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                key(bar, "Расшифровать", 1f, true) {
                    startActivity(Intent(this, DecryptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            else -> {
                // Preserve keyboard row positions while suggestions appear and disappear.
            }
        }
        val controls = actionRow ?: return
        controls.removeAllViews()
        when (mode) {
            Mode.NORMAL -> Unit
            Mode.BUFFER -> {
                key(controls, "Очистить", 1f, true) { clearBuffer(); updateUi() }
                key(controls, if (bufferBusy) "Шифрование…" else "Зашифровать и вставить", 2.4f, true) { encryptBuffer() }
            }
            Mode.LIVE -> {
                key(controls, "Очистить", 1f, true) { clearBuffer(); scheduleLive(); updateUi() }
                key(controls, "🔐 Обновить Live", 2f, true) {
                    scheduleLive(immediate = true)
                    updateUi()
                }
            }
        }
    }

    private fun currentWord(): String {
        if (symbols || emojis || numberField || passwordField) return ""
        val source = if (mode == Mode.NORMAL) {
            if (!predictions || selectionStart != selectionEnd) return ""
            currentInputConnection?.getTextBeforeCursor(40, 0)?.toString().orEmpty()
        } else buffer.takeLast(40).toString()
        return source.takeLastWhile { it in 'a'..'z' || it in 'A'..'Z' ||
            it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
    }

    private fun useSuggestion(candidate: String) {
        val word = currentWord()
        if (candidate !in WordSuggestions.suggest(word, lang)) return
        val value = if (word.first().isUpperCase()) candidate.replaceFirstChar { it.uppercaseChar() } else candidate
        if (mode == Mode.NORMAL) {
            currentInputConnection?.let { replaceWord(it, word, "$value ") }
        } else {
            updateBuffer(word.length, "$value ")
        }
        updateUi()
    }

    private fun updateBuffer(remove: Int, value: String) {
        val length = buffer.length - remove
        if (length + value.length > CryptoEngine.MAX_MESSAGE_LENGTH) {
            toast("Максимум ${CryptoEngine.MAX_MESSAGE_LENGTH} символов")
            return
        }
        for (index in length until buffer.length) buffer.setCharAt(index, '\u0000')
        buffer.setLength(length)
        buffer.append(value)
        bufferRevision++
        if (mode == Mode.LIVE) scheduleLive()
    }

    private fun replaceWord(ic: InputConnection, word: String, value: String, fallback: String? = null) {
        ic.beginBatchEdit()
        try {
            if (ic.deleteSurroundingText(word.length, 0)) ic.commitText(value, 1)
            else if (fallback != null) ic.commitText(fallback, 1)
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun encryptBuffer() {
        if (mode != Mode.BUFFER || bufferBusy || buffer.isEmpty()) return
        val ic = currentInputConnection ?: return
        val draft = bufferRevision
        val selection = selectionRevision
        val key = AppSettings.secretRevision(this)
        bufferBusy = true
        val text = buffer.toString()
        updateUi()
        worker.execute {
            val secret = AppSettings.loadSecret(this)
            val result = secret?.let { runCatching { CryptoEngine.encrypt(text, it) } }
            handler.post {
                bufferBusy = false
                if (mode != Mode.BUFFER || draft != bufferRevision || currentInputConnection !== ic) {
                    updateUi(); return@post
                }
                when {
                    selection != selectionRevision -> toast("Курсор переместился. Нажмите «Вставить» снова")
                    key != AppSettings.secretRevision(this) -> toast("Ключ изменился. Повторите шифрование")
                    result == null -> toast("Сначала задайте фразу в magicboard")
                    else -> result.onSuccess { token ->
                        if (ic.commitText(token, 1)) {
                            clearBuffer()
                            toast("Шифротекст вставлен. Отправьте его в чате")
                        } else toast("Не удалось вставить шифротекст")
                    }.onFailure { toast("Ошибка шифрования") }
                }
                updateUi()
            }
        }
    }

    private fun scheduleLive(immediate: Boolean = false) {
        if (mode != Mode.LIVE) return
        liveRevision++
        liveTask?.let(handler::removeCallbacks)
        liveStatus = "Live: шифрование…"
        val task = Runnable {
            liveTask = null
            startLiveEncryption()
        }
        liveTask = task
        handler.postDelayed(task, if (immediate) 0 else 350)
    }

    private fun startLiveEncryption() {
        if (mode != Mode.LIVE) return
        if (liveWorking) {
            livePending = true
            return
        }
        val ic = currentInputConnection ?: return
        val revision = liveRevision
        val key = AppSettings.secretRevision(this)
        val content = buffer.toString()
        livePending = false
        liveWorking = true
        worker.execute {
            val secret = if (content.isEmpty()) null else AppSettings.loadSecret(this)
            val result = when {
                content.isEmpty() -> Result.success("")
                secret != null -> runCatching { CryptoEngine.encrypt(content, secret) }
                else -> null
            }
            handler.post {
                liveWorking = false
                if (mode != Mode.LIVE) return@post
                if (revision == liveRevision && currentInputConnection === ic) {
                    val token = result?.getOrNull()
                    liveStatus = when {
                        key != AppSettings.secretRevision(this) -> "Live: ключ изменился, обновите"
                        result == null -> "Live: задайте фразу в magicboard"
                        token == null -> "Live: ошибка шифрования"
                        !replaceLiveToken(token) -> "Live: поле изменилось, используйте буфер"
                        token.isEmpty() -> "Live: наберите сообщение в пустом поле"
                        else -> "Live: шифротекст готов"
                    }
                    updateUi()
                }
                if (livePending && liveTask == null) startLiveEncryption()
            }
        }
    }

    /** Replaces only the exact token occupying the complete field. */
    private fun replaceLiveToken(next: String): Boolean {
        val ic = currentInputConnection ?: return false
        val length = lastLiveToken.length
        if (selectionStart != length || selectionEnd != length) return false
        if (ic.getTextAfterCursor(1, 0)?.isEmpty() != true) return false
        if (ic.getTextBeforeCursor(length + 1, 0)?.toString() != lastLiveToken) return false
        if (next == lastLiveToken) return true
        ic.beginBatchEdit()
        return try {
            val committed = if (Build.VERSION.SDK_INT >= 34) {
                ic.replaceText(0, length, next, 1, null)
            } else if (length == 0 || ic.setSelection(0, length)) {
                ic.commitText(next, 1)
            } else false
            if (!committed) return false
            lastLiveToken = next
            true
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
