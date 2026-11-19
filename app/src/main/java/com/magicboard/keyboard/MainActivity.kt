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
import android.widget.Toast

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
        fun button(title: String, action: () -> Unit): Button = Button(this).apply {
            this.text = title
            isAllCaps = false
            setTextColor(Ui.dark)
            background = Ui.keyBackground(context, Ui.special, 13)
            setOnClickListener { action() }
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
        text("2. Общая секретная фраза", 20f, true)
        text("Используйте одну и ту же фразу минимум из 12 символов на обоих телефонах. Без неё расшифровать сообщения нельзя. Фраза хранится в зашифрованном виде через Android Keystore.", 14f)
        val phrase = EditText(this).apply {
            hint = "Секретная фраза (12+ символов)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setPadding(Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 12))
        }
        col.addView(phrase, LinearLayout.LayoutParams(-1, Ui.dp(this, 55)))
        val secretStatus = text(if (AppSettings.hasSecret(this)) "Ключ сохранён на устройстве" else "Ключ пока не задан", 13f)
        button("Сохранить фразу") {
            if (phrase.text.length < 12) {
                Toast.makeText(this, "Введите не меньше 12 символов", Toast.LENGTH_LONG).show()
            } else {
                try {
                    AppSettings.saveSecret(this, phrase.text.toString())
                    phrase.text.clear()
                    secretStatus.text = "Ключ сохранён на устройстве"
                    Toast.makeText(this, "Ключ сохранён", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "Ошибка сохранения ключа", Toast.LENGTH_LONG).show()
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
        text("3. Автоматическая расшифровка", 20f, true)
        text("Выберите приложения, в которых разрешено искать только сообщения ENC1. Текст показывается поверх зашифрованного сообщения. Доступ к экрану — мощное разрешение Android: он потенциально позволяет читать другие видимые данные, хотя эта программа обрабатывает только ENC1 в выбранных приложениях.", 14f)
        AppSettings.supportedApps.forEach { (pkg, label) ->
            col.addView(CheckBox(this).apply {
                text = label
                isChecked = AppSettings.allows(this@MainActivity, pkg)
                setOnCheckedChangeListener { _, value -> AppSettings.setAllowed(this@MainActivity, pkg, value) }
            })
        }
        button("Включить авторасшифровку") {
            AlertDialog.Builder(this)
                .setTitle("Разрешение на чтение видимого текста")
                .setMessage("Служба специальных возможностей получит технический доступ к содержимому видимого экрана. magicboard проверяет наличие ENC1 только в отмеченных мессенджерах, расшифровывает локально и накладывает текст поверх сообщения. Данные никуда не передаются, сеть не используется. Службу можно отключить в настройках Android или приложении. Разрешить?")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Разрешить") { _, _ ->
                    AppSettings.setConsent(this, true)
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }.show()
        }
        button("Отключить авторасшифровку") {
            AppSettings.setConsent(this, false)
            Toast.makeText(this, "Чтение отключено. Можно также выключить службу в настройках Android.", Toast.LENGTH_LONG).show()
        }
        text("Как пользоваться", 20f, true)
        text("В режиме «Буфер» наберите сообщение и нажмите «Зашифровать и вставить». Затем отправьте шифротекст кнопкой чата. Экспериментальный Live обновляет шифротекст в пустом поле с задержкой. Перед отправкой дождитесь статуса готовности.", 14f)
        setContentView(scroll)
        Ui.applyInsets(scroll)
    }
}
