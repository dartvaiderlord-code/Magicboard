package com.magicboard.keyboard

import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.InsetDrawable
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Standalone IME: no third-party keyboard SDK or network capability. */
class CipherKeyboardService : InputMethodService() {
    private enum class Mode { NORMAL, BUFFER, OCR, LIVE }
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
    private var preferOcr = false
    private var capturing = false
    private lateinit var palette: KeyboardPalette
    private var modeMenuOpen = false
    private var alternatives: PopupWindow? = null
    private var heldDelete: View? = null
    private val repeatDelete = object : Runnable {
        override fun run() {
            if (heldDelete?.isPressed == true && backspace()) handler.postDelayed(this, 65)
            else stopDeleteRepeat()
        }
    }
    private val captureStatus: (OcrPreview.State) -> Unit = { state ->
        if (capturing != state.active) {
            capturing = state.active
            if (mode == Mode.OCR) updateUi()
        }
    }

    override fun onCreate() {
        super.onCreate()
        palette = KeyboardPalette.from(this)
        lang = AppSettings.language(this)
        OcrPreview.observe(captureStatus)
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
        restoreOcrMode()
        if (base != null) render()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val previousPalette = palette
        palette = KeyboardPalette.from(this)
        if (restoreOcrMode() || previousPalette != palette) render()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        palette = KeyboardPalette.from(this)
        render()
    }

    private fun restoreOcrMode(): Boolean {
        if (mode != Mode.NORMAL || passwordField || numberField || (!preferOcr && !capturing)) return false
        mode = Mode.OCR
        updateSecureFlag()
        return true
    }

    override fun onCreateInputView(): View {
        base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(palette.surface)
            setPadding(Ui.dp(context, 3), Ui.dp(context, 3), Ui.dp(context, 3), Ui.dp(context, 7))
            if (Build.VERSION.SDK_INT >= 30) {
                setOnApplyWindowInsetsListener { view, insets ->
                    val navigation = insets.getInsets(WindowInsets.Type.navigationBars())
                    view.setPadding(
                        Ui.dp(context, 3) + navigation.left,
                        Ui.dp(context, 3),
                        Ui.dp(context, 3) + navigation.right,
                        Ui.dp(context, 7) + navigation.bottom
                    )
                    insets
                }
            }
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
        dismissAlternatives()
        stopDeleteRepeat()
        modeMenuOpen = false
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
        OcrPreview.remove(captureStatus)
        resetPrivateInput()
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        val root = base ?: return
        dismissAlternatives()
        stopDeleteRepeat()
        root.setBackgroundColor(palette.surface)
        preview?.text = ""
        suggestions?.removeAllViews()
        root.removeAllViews()
        val toolbar = row(root, 46)
        toolbarButton(toolbar, R.drawable.ic_keyboard_modes, "Режимы ввода", modeMenuOpen) {
            modeMenuOpen = !modeMenuOpen
            render()
        }
        suggestions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            importantForAccessibility = if (mode == Mode.NORMAL) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            toolbar.addView(this, LinearLayout.LayoutParams(0, -1, 1f))
        }
        toolbarButton(toolbar, R.drawable.ic_keyboard_paste, "Вставить из буфера обмена", false, ::pasteClipboard)
        if (modeMenuOpen) renderModeMenu(root)

        tip = Ui.text(this, "", 12f, palette.secondaryText).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0)
            visibility = if (mode == Mode.NORMAL) View.GONE else View.VISIBLE
        }
        root.addView(tip, LinearLayout.LayoutParams(-1, Ui.dp(this, 24)))
        preview = Ui.text(this, "", 13f, palette.text).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 10), 0)
            background = Ui.rounded(palette.key, 10, context)
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
        actionRow = row(root, 42).apply {
            visibility = if (mode == Mode.NORMAL) View.GONE else View.VISIBLE
        }
        if (emojis) renderEmojis(root)
        else if (symbols || numberField) renderSymbols(root)
        else renderLetters(root)
        updateUi()
    }

    private fun toolbarButton(row: LinearLayout, icon: Int, label: String, selected: Boolean, action: () -> Unit) {
        val button = ImageButton(this).apply {
            contentDescription = label
            tooltipText = label
            isSelected = selected
            setImageResource(icon)
            setColorFilter(if (selected) palette.selectedText else palette.text)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = InsetDrawable(
                palette.keyBackground(context, if (selected) palette.selected else palette.action, 14),
                Ui.dp(context, 3)
            )
            isHapticFeedbackEnabled = AppSettings.hapticFeedback(context)
            setOnClickListener { keyFeedback(it); action() }
        }
        row.addView(button, LinearLayout.LayoutParams(Ui.dp(this, 48), -1))
    }

    private fun renderModeMenu(root: LinearLayout) {
        val tabs = row(root, 42)
        val modes = if (passwordField || numberField) listOf(Mode.NORMAL to "Обычный") else
            listOf(Mode.NORMAL to "Обычный", Mode.BUFFER to "Буфер", Mode.OCR to "OCR", Mode.LIVE to "Live")
        modes.forEach { (m, label) ->
            val tv = Ui.text(this, label, 14f, if (mode == m) palette.selectedText else palette.text).apply {
                if (mode == m) Ui.bold(this)
                background = palette.keyBackground(context, if (mode == m) palette.selected else palette.action, 12)
                isSelected = mode == m
                setOnClickListener {
                    keyFeedback(it)
                    if (mode == m) {
                        modeMenuOpen = false
                        render()
                    } else changeMode(m)
                }
            }
            tabs.addView(tv, LinearLayout.LayoutParams(0, -1, 1f).apply {
                leftMargin = Ui.dp(this@CipherKeyboardService, 3)
                rightMargin = Ui.dp(this@CipherKeyboardService, 3)
            })
        }
        val tools = row(root, 40)
        key(tools, "Настройки", special = true) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        key(tools, "Расшифровать", special = true) {
            startActivity(Intent(this, DecryptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun key(
        row: LinearLayout, value: String, width: Float = 1f, special: Boolean = false,
        letter: Boolean = false, hint: Char? = null, action: () -> Unit
    ): View {
        val tv = Ui.text(this, value, if (letter) 22f else if (value.length > 4) 14f else 19f, palette.text).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val cell = FrameLayout(this).apply {
            val space = value in listOf("Русский", "English", "Пробел") && row !== suggestions
            val color = if (space) palette.key else if (special) palette.action else palette.key
            background = InsetDrawable(
                palette.keyBackground(context, color, if (space) 19 else if (letter) 8 else 13, letter),
                Ui.dp(context, 2), Ui.dp(context, 3), Ui.dp(context, 2), Ui.dp(context, 3)
            )
            addView(tv, FrameLayout.LayoutParams(-1, -1))
            if (hint != null) {
                val number = Ui.text(context, hint.toString(), 10f, palette.secondaryText).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                addView(number, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                    topMargin = Ui.dp(context, 2)
                    marginEnd = Ui.dp(context, 6)
                })
            }
            contentDescription = when (value) {
                "⇧" -> "Регистр"
                "⌫" -> "Удалить"
                "↵" -> "Ввод"
                "🌐" -> "Сменить язык"
                "😊" -> "Эмодзи"
                "?123" -> "Цифры и символы"
                "Русский", "English", "Пробел" -> if (row === suggestions) "Сменить язык" else "Пробел, $value"
                else -> value
            }
            isHapticFeedbackEnabled = AppSettings.hapticFeedback(context)
            setOnClickListener { keyFeedback(it); action() }
        }
        row.addView(cell, LinearLayout.LayoutParams(0, -1, width))
        return cell
    }

    private fun keyFeedback(view: View) {
        if (AppSettings.hapticFeedback(this)) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun row(root: LinearLayout, height: Int = 51): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        root.addView(this, LinearLayout.LayoutParams(-1, Ui.dp(this@CipherKeyboardService, if (landscape) height * 3 / 4 else height)))
    }

    private fun renderLetters(root: LinearLayout) {
        val layouts = KeyboardLetters.rows(lang)
        val columns = layouts.maxOf { it.length }
        layouts.forEachIndexed { i, letters ->
            val r = row(root)
            r.weightSum = columns.toFloat()
            val side = (columns - letters.length) / 2f
            if (i == 2) key(r, "⇧", side, true) { upper = !upper; render() }
            else if (side > 0) r.addView(View(this), LinearLayout.LayoutParams(0, -1, side))
            letters.forEachIndexed { column, ch ->
                val letter = if (upper) ch.uppercaseChar() else ch
                val variants = KeyboardLetters.alternatives(letter, i, column)
                val key = key(r, letter.toString(), letter = true, hint = KeyboardLetters.digit(i, column)) {
                    insert(letter.toString())
                }
                if (variants.isNotEmpty()) key.setOnLongClickListener { showAlternatives(it, variants) }
            }
            if (i == 2) deleteKey(r, side)
            else if (side > 0) r.addView(View(this), LinearLayout.LayoutParams(0, -1, side))
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
        deleteKey(r)
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
        deleteKey(r)
        key(r, "↵", 1f, true) { enter() }
    }

    private fun showAlternatives(anchor: View, variants: List<Char>): Boolean {
        dismissAlternatives()
        val height = Ui.dp(this, 52)
        val width = Ui.dp(this, 52) * variants.size
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(context, 2), 0, Ui.dp(context, 2), 0)
            variants.forEach { variant ->
                key(this, variant.toString(), special = true) {
                    dismissAlternatives()
                    insert(variant.toString())
                }
            }
        }
        alternatives = PopupWindow(panel, width, height, false).apply {
            setBackgroundDrawable(Ui.rounded(palette.surface, 14, this@CipherKeyboardService))
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            elevation = Ui.dp(this@CipherKeyboardService, 4).toFloat()
            setOnDismissListener { if (alternatives === this) alternatives = null }
            showAsDropDown(anchor, (anchor.width - width) / 2, -anchor.height - height)
        }
        return true
    }

    private fun dismissAlternatives() {
        alternatives?.dismiss()
        alternatives = null
    }

    private fun deleteKey(row: LinearLayout, width: Float = 1f) {
        key(row, "⌫", width, special = true) { backspace() }.apply {
            setOnLongClickListener {
                heldDelete = it
                repeatDelete.run()
                true
            }
            setOnTouchListener { pressed, event ->
                if (heldDelete === pressed &&
                    (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL ||
                        (event.actionMasked == MotionEvent.ACTION_MOVE &&
                            (event.x < 0 || event.y < 0 || event.x >= pressed.width || event.y >= pressed.height)))) {
                    stopDeleteRepeat()
                }
                false
            }
        }
    }

    private fun stopDeleteRepeat() {
        heldDelete = null
        handler.removeCallbacks(repeatDelete)
    }

    private fun pasteClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = try {
            clipboard.primaryClip
        } catch (_: SecurityException) {
            null
        }
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (text.isNullOrEmpty()) toast("В буфере обмена нет текста") else insert(text, correctSpace = false)
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
        preferOcr = newMode == Mode.OCR
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

    private fun insert(value: String, correctSpace: Boolean = true) {
        val wasUpper = upper
        val word = if (correctSpace && value == " " && AppSettings.autoCorrect(this)) currentWord() else ""
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

    private fun backspace(): Boolean {
        val changed = if (mode == Mode.NORMAL) {
            val ic = currentInputConnection ?: return false
            if (selectionStart != selectionEnd) {
                ic.commitText("", 1)
            } else {
                val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
                if (before.isEmpty()) return false
                ic.deleteSurroundingText(lastCharacterLength(before), 0)
            }
        } else {
            if (buffer.isEmpty()) return false
            updateBuffer(lastCharacterLength(buffer.takeLast(64).toString()), "")
            true
        }
        if (changed) updateUi()
        return changed
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
            Mode.OCR -> if (capturing) "OCR активен · защищённый ввод MB2" else "Защищённый ввод MB2 · захват выключен"
            Mode.LIVE -> liveStatus.ifEmpty { "Live: наберите сообщение в пустом поле" }
        }
        preview?.text = when (mode) {
            Mode.NORMAL -> ""
            Mode.BUFFER, Mode.OCR, Mode.LIVE -> if (buffer.isEmpty()) "Введите сообщение…" else buffer.takeLast(160).toString()
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
                key(controls, if (bufferBusy) "Шифрование…" else "Вставить ENC1", 1.6f, true) { encryptBuffer() }
            }
            Mode.OCR -> {
                key(controls, if (capturing) "Стоп OCR" else "Начать OCR", 1.35f, true) {
                    if (capturing) stopService(Intent(this, OcrCaptureService::class.java))
                    else startActivity(Intent(this, OcrActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                key(controls, "Очистить", 1.15f, true) { clearBuffer(); updateUi() }
                key(controls, if (bufferBusy) "Шифрование…" else "Вставить MB2", 1.5f, true) { encryptBuffer(forOcr = true) }
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

    private fun encryptBuffer(forOcr: Boolean = false) {
        val draftMode = mode
        if ((draftMode != Mode.BUFFER && draftMode != Mode.OCR) || bufferBusy || buffer.isEmpty()) return
        val ic = currentInputConnection ?: return
        val text = buffer.toString()
        if (forOcr && text.toByteArray(Charsets.UTF_8).size > OcrMessageCodec.MAX_PLAINTEXT_BYTES) {
            toast("Для OCR сократите сообщение до ${OcrMessageCodec.MAX_PLAINTEXT_BYTES} байт UTF-8")
            return
        }
        val draft = bufferRevision
        val selection = selectionRevision
        val key = AppSettings.secretRevision(this)
        bufferBusy = true
        updateUi()
        worker.execute {
            val secret = AppSettings.loadSecret(this)
            val result = secret?.let {
                runCatching {
                    if (forOcr) OcrMessageCodec.encrypt(text, it) else CryptoEngine.encrypt(text, it)
                }
            }
            handler.post {
                bufferBusy = false
                if (mode != draftMode || draft != bufferRevision || currentInputConnection !== ic) {
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
