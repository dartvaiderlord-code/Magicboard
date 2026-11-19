package com.magicboard.keyboard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class OcrActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private var launchPending = false
    private val update: (OcrPreview.State) -> Unit = { state ->
        launchPending = false
        render(state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.background) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            Ui.pad(this, 18)
        }
        scroll.addView(column)
        column.addView(Ui.text(this, "OCR поверх чата", 26f).apply {
            Ui.bold(this)
            gravity = Gravity.START
        })
        column.addView(Ui.text(
            this,
            "Разрешите показ поверх приложений и подтвердите захват. Если Android предложит выбор, выберите полноэкранное окно чата. Чтобы остановить захват, нажмите «Стоп OCR» на клавиатуре.",
            15f
        ).apply { gravity = Gravity.START })

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Ui.dark)
            background = Ui.keyBackground(context, Ui.special, 12)
            setOnClickListener { onClick() }
            column.addView(this, LinearLayout.LayoutParams(-1, Ui.dp(context, 48)).apply {
                topMargin = Ui.dp(context, 8)
            })
        }
        start = button("Начать захват", ::authorizeCapture)
        stop = button("Завершить сеанс") { stopService(Intent(this, OcrCaptureService::class.java)) }
        button("Вернуться") { finish() }
        status = Ui.text(this, "", 14f).apply { gravity = Gravity.START }
        column.addView(status)
        setContentView(scroll)
        Ui.applyInsets(scroll)
        OcrPreview.observe(update)
    }

    override fun onResume() {
        super.onResume()
        render(OcrPreview.state)
    }

    private fun render(state: OcrPreview.State) {
        start.text = if (Settings.canDrawOverlays(this)) "Начать захват" else "Разрешить показ поверх чата"
        start.isEnabled = !state.active && !launchPending
        stop.isEnabled = state.active
        status.text = if (launchPending) "Запускаю распознавание…" else state.status
    }

    private fun authorizeCapture() {
        if (!Settings.canDrawOverlays(this)) {
            val settings = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            try {
                startActivity(settings)
            } catch (_: android.content.ActivityNotFoundException) {
                status.text = "На этом устройстве недоступно разрешение показа поверх приложений."
            }
            return
        }
        if (!AppSettings.hasSecret(this)) {
            status.text = "Сначала сохраните общую фразу в настройках magicboard."
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !askedForNotification
        ) {
            askedForNotification = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
        } else requestCapture()
    }

    @Suppress("DEPRECATION")
    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == REQUEST_NOTIFICATION && !isFinishing) requestCapture()
    }

    @Deprecated("MediaProjection uses the system activity result on Android 8 and later.")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code != REQUEST_CAPTURE || result != RESULT_OK || data == null) return
        launchPending = true
        render(OcrPreview.state)
        val startIntent = Intent(this, OcrCaptureService::class.java).apply {
            action = OcrCaptureService.ACTION_START
            putExtra(OcrCaptureService.EXTRA_RESULT_CODE, result)
            putExtra(OcrCaptureService.EXTRA_AUTHORIZATION, data)
        }
        try {
            startForegroundService(startIntent)
        } catch (_: IllegalStateException) {
            launchPending = false
            render(OcrPreview.State(status = "Не удалось запустить сеанс. Откройте magicboard и повторите"))
        }
    }

    override fun onDestroy() {
        OcrPreview.remove(update)
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_NOTIFICATION = 5
        private const val REQUEST_CAPTURE = 6
        private var askedForNotification = false
    }
}
