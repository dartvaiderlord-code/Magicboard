package com.magicboard.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Ui.dark
        window.navigationBarColor = Ui.background
        buildPage()
    }

    private fun buildPage() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.background) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            Ui.pad(this, 18)
        }
        scroll.addView(col)
        fun text(value: String, size: Float, bold: Boolean = false): TextView =
            Ui.text(this, value, size).apply {
                gravity = Gravity.START
                setPadding(0, Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 8))
                if (bold) Ui.bold(this)
                col.addView(this)
            }
        fun button(title: String, action: (Button) -> Unit): Button = Button(this).apply {
            this.text = title
            isAllCaps = false
            setTextColor(Ui.dark)
            background = Ui.keyBackground(context, Ui.special, 13)
            setOnClickListener { action(this) }
            col.addView(this, LinearLayout.LayoutParams(-1, Ui.dp(this@MainActivity, 52)).apply {
                bottomMargin = Ui.dp(this@MainActivity, 8)
            })
        }

        text("magicboard", 32f, true)
        text("Клавиатура с шифрованием сообщений", 15f)
        text("1. Включите клавиатуру", 20f, true)
        button("Открыть настройки клавиатур") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        button("Выбрать magicboard") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        col.addView(EditText(this).apply {
            hint = "Попробуйте клавиатуру здесь"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSaveEnabled = false
        }, LinearLayout.LayoutParams(-1, -2))
        col.addView(CheckBox(this).apply {
            text = "Автоисправление распространённых опечаток"
            isChecked = AppSettings.autoCorrect(this@MainActivity)
            setOnCheckedChangeListener { _, value -> AppSettings.setAutoCorrect(this@MainActivity, value) }
        })
        col.addView(CheckBox(this).apply {
            text = "Виброотклик клавиш"
            isChecked = AppSettings.hapticFeedback(this@MainActivity)
            setOnCheckedChangeListener { _, value -> AppSettings.setHapticFeedback(this@MainActivity, value) }
        })
        button("Повторы строк MB2: ${AppSettings.ocrExtraCopies(this)}") { setting ->
            val choices = Array(OcrMessageCodec.MAX_EXTRA_COPIES + 1) { index ->
                if (index == 0) "Без повторов" else "$index"
            }
            AlertDialog.Builder(this)
                .setTitle("Дополнительные копии каждой строки")
                .setSingleChoiceItems(choices, AppSettings.ocrExtraCopies(this)) { dialog, index ->
                    AppSettings.setOcrExtraCopies(this, index)
                    setting.text = "Повторы строк MB2: $index"
                    dialog.dismiss()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        text("Каждая копия помогает OCR восстановить пропущенные строки и увеличивает длину сообщения. Действует в OCR и Live.", 13f)
        text("2. Общая секретная фраза", 20f, true)
        text("Используйте одну и ту же фразу минимум из 12 символов на обоих телефонах. Без неё расшифровать сообщения нельзя. Фраза хранится в зашифрованном виде через Android Keystore.", 14f)
        val phrase = EditText(this).apply {
            hint = "Секретная фраза (12+ символов)"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setPadding(Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 12))
        }
        col.addView(phrase, LinearLayout.LayoutParams(-1, Ui.dp(this, 55)))
        val secretStatus = text(if (AppSettings.hasSecret(this)) "Ключ сохранён на устройстве" else "Ключ пока не задан", 13f)
        button("Сохранить фразу") {
            if (phrase.text.length < 12) {
                secretStatus.text = "Введите не меньше 12 символов"
            } else {
                try {
                    AppSettings.saveSecret(this, phrase.text.toString())
                    phrase.text.clear()
                    secretStatus.text = "Ключ сохранён на устройстве"
                } catch (_: Exception) {
                    secretStatus.text = "Ошибка сохранения ключа"
                }
            }
        }
        button("Удалить сохранённую фразу") {
            if (AppSettings.hasSecret(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Удалить фразу с этого устройства?")
                    .setMessage("Чтобы прочитать старые сообщения, понадобится снова ввести ту же фразу.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить") { _, _ ->
                        AppSettings.clearSecret(this)
                        phrase.text.clear()
                        secretStatus.text = "Ключ пока не задан"
                    }.show()
            }
        }
        button("Открыть шифротекст вручную") {
            startActivity(Intent(this, DecryptActivity::class.java))
        }
        button("Настроить OCR поверх чата") {
            startActivity(Intent(this, OcrActivity::class.java))
        }
        text("Как пользоваться", 20f, true)
        text("Откройте меню с четырьмя квадратами на клавиатуре. Режим «OCR» показывает расшифровку поверх сообщений и вставляет MB2. «Буфер» вставляет ENC1 по кнопке, а Live обновляет MB2 в пустом поле по мере набора.", 14f)
        setContentView(scroll)
        Ui.applyInsets(scroll)
    }
}
