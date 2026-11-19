package com.magicboard.keyboard

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class OcrActivity : Activity() {
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var message: TextView
    private lateinit var controls: LinearLayout
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var pip: Button
    private var launchPending = false
    private val update: (OcrPreview.State) -> Unit = { state ->
        launchPending = false
        render(state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.background)
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            Ui.pad(this, 18)
        }
        scroll.addView(column)
        title = Ui.text(this, "Распознавание чата", 26f).apply {
            Ui.bold(this)
            gravity = Gravity.START
        }
        column.addView(title)
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(controls)
        controls.addView(Ui.text(
            this,
            "Выберите окно чата в системном диалоге. Кадры обрабатываются на устройстве во время сеанса. Для чтения сообщений MB2 нужна общая секретная фраза.",
            15f
        ).apply { gravity = Gravity.START })
        start = button("Начать захват", ::authorizeCapture)
        pip = button("Показать в мини-окне") { enterPreview() }
        stop = button("Завершить сеанс") { finishCapture() }
        status = Ui.text(this, "", 14f).apply { gravity = Gravity.START }
        column.addView(status)
        message = Ui.text(this, "", 18f).apply {
            gravity = Gravity.START
            background = Ui.rounded(Ui.key, 12, context)
            Ui.pad(this, 12)
            isSaveEnabled = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        column.addView(message)
        setContentView(scroll)
        Ui.applyInsets(scroll)
        OcrPreview.observe(update)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Ui.dark)
        background = Ui.keyBackground(context, Ui.special, 12)
        setOnClickListener { onClick() }
        controls.addView(this, LinearLayout.LayoutParams(-1, Ui.dp(context, 48)).apply {
            topMargin = Ui.dp(context, 8)
        })
    }

    private fun render(state: OcrPreview.State) {
        val mini = isInPictureInPictureMode
        title.visibility = if (mini) View.GONE else View.VISIBLE
        controls.visibility = if (mini) View.GONE else View.VISIBLE
        start.isEnabled = !state.active && !launchPending
        stop.isEnabled = state.active || launchPending
        pip.visibility = if (supportsPreview()) View.VISIBLE else View.GONE
        pip.isEnabled = state.active
        status.text = if (launchPending) "Запускаю распознавание…" else state.status
        status.visibility = if (mini && state.plaintext.isNotEmpty()) View.GONE else View.VISIBLE
        message.text = state.plaintext
        message.maxLines = if (mini) 5 else Int.MAX_VALUE
        message.ellipsize = if (mini) TextUtils.TruncateAt.END else null
        message.visibility = if (state.plaintext.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun authorizeCapture() {
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

    private fun finishCapture() {
        stopService(Intent(this, OcrCaptureService::class.java))
        launchPending = false
        render(OcrPreview.State(status = "Сеанс завершён"))
    }

    private fun supportsPreview() =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterPreview() {
        if (!OcrPreview.state.active || isInPictureInPictureMode || !supportsPreview()) return
        val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPreview()
    }

    override fun onPictureInPictureModeChanged(mini: Boolean, config: Configuration) {
        super.onPictureInPictureModeChanged(mini, config)
        render(OcrPreview.state)
    }

    override fun onDestroy() {
        OcrPreview.remove(update)
        if (isFinishing) finishCapture()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_NOTIFICATION = 5
        private const val REQUEST_CAPTURE = 6
        private var askedForNotification = false
    }
}
