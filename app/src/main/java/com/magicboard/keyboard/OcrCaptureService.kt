package com.magicboard.keyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** In-process preview for the explicitly active capture session. Accessed on the main thread. */
internal object OcrPreview {
    data class State(
        val active: Boolean = false,
        val status: String = "Сеанс не запущен",
        val plaintext: String = ""
    )

    private val listeners = mutableSetOf<(State) -> Unit>()
    var state = State()
        private set

    fun publish(next: State) {
        state = next
        listeners.toList().forEach { it(next) }
    }

    fun observe(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun remove(listener: (State) -> Unit) {
        listeners.remove(listener)
    }
}

class OcrCaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val collector = OcrMessageCodec.Collector()
    private var collectorKey = -1
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var recognizer: TextRecognizer? = null
    private var running = false
    private var analyzing = false
    private var stoppedMessage = "Сеанс завершён"
    private var nextScan = 0L
    private var screenReceiverRegistered = false
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) stopCapture("Сеанс завершён при выключении экрана")
        }
    }
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (running) stopCapture("Android завершил показ экрана")
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (running) runCatching { attachReader(width, height) }
                .onFailure { stopCapture("Не удалось изменить размер захвата") }
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        if (intent?.action != ACTION_START) {
            stopCapture()
            return START_NOT_STICKY
        }
        @Suppress("DEPRECATION")
        val authorization = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_AUTHORIZATION, Intent::class.java)
            else intent.getParcelableExtra<Intent>(EXTRA_AUTHORIZATION)
        if (authorization == null) {
            stopCapture()
            return START_NOT_STICKY
        }

        try {
            val notification = captureNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = checkNotNull(manager.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), authorization))
            projection!!.registerCallback(callback, main)
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val (width, height) = screenSize()
            attachReader(width, height)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            }
            screenReceiverRegistered = true
            running = true
            OcrPreview.publish(OcrPreview.State(true, "Ищу сообщения MB2 в выбранном окне…"))
        } catch (_: Exception) {
            stopCapture("Не удалось начать захват. Запустите новый сеанс")
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val windows = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windows.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = android.util.DisplayMetrics()
        windows.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun attachReader(width: Int, height: Int) {
        val scale = minOf(1f, 1920f / max(width, height))
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        if (reader?.width == w && reader?.height == h) return
        val density = resources.displayMetrics.densityDpi
        val incoming = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        try {
            val previous = display
            if (previous == null) {
                display = checkNotNull(projection?.createVirtualDisplay(
                    "magicboard OCR", w, h, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    incoming.surface, null, main
                ))
            } else {
                previous.resize(w, h, density)
                previous.surface = incoming.surface
            }
            reader?.close()
            reader = incoming
            incoming.setOnImageAvailableListener(::onFrame, main)
        } catch (error: Exception) {
            incoming.close()
            throw error
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT >= 34 || !running) return
        val (width, height) = screenSize()
        runCatching { attachReader(width, height) }.onFailure { stopCapture("Не удалось повернуть захват") }
    }

    private fun onFrame(source: ImageReader) {
        if (source !== reader) return
        val image = source.acquireLatestImage() ?: return
        val engine = recognizer
        val elapsed = SystemClock.elapsedRealtime()
        val bitmap = image.use {
            if (!running || analyzing || engine == null || elapsed < nextScan) return
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, source.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            if (paddedWidth == source.width) padded else
                Bitmap.createBitmap(padded, 0, 0, source.width, source.height).also { padded.recycle() }
        }
        analyzing = true
        nextScan = elapsed + 850
        try {
            engine!!.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                bitmap.recycle()
                if (!running) return@addOnCompleteListener
                if (!task.isSuccessful) {
                    analyzing = false
                    stopCapture("Не удалось запустить локальное распознавание")
                    return@addOnCompleteListener
                }
                val recognized = task.result.text
                val key = AppSettings.secretRevision(this)
                worker.execute { decryptFrame(recognized, key) }
            }
        } catch (_: Exception) {
            bitmap.recycle()
            stopCapture("Не удалось обработать кадр")
        }
    }

    private fun decryptFrame(text: String, key: Int) {
        val keyChanged = collectorKey != key
        if (keyChanged) {
            collector.clear()
            collectorKey = key
        }
        val tokens = collector.accept(text).takeLast(3)
        val phrase = if (tokens.isEmpty()) null else AppSettings.loadSecret(this)
        val messages = if (phrase == null) emptyList() else tokens.mapNotNull {
            runCatching { CryptoEngine.decrypt(it, phrase) }.getOrNull()?.take(500)
        }
        main.post {
            analyzing = false
            if (!running || key != AppSettings.secretRevision(this)) return@post
            val state = OcrPreview.state
            when {
                messages.isNotEmpty() -> OcrPreview.publish(OcrPreview.State(true, "Сообщение прочитано", messages.joinToString("\n\n")))
                tokens.isNotEmpty() -> OcrPreview.publish(OcrPreview.State(true, "Проверьте общую секретную фразу"))
                keyChanged && state.plaintext.isNotEmpty() ->
                    OcrPreview.publish(OcrPreview.State(true, "Ищу сообщения MB2 в выбранном окне…"))
            }
        }
    }

    private fun captureNotification(): Notification {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Распознавание окна", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OcrCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, OcrActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_magicboard)
            .setContentTitle("magicboard · сеанс OCR")
            .setContentText("Идёт локальное распознавание текста")
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .build()
    }

    private fun stopCapture(message: String = "Сеанс завершён") {
        stoppedMessage = message
        running = false
        OcrPreview.publish(OcrPreview.State(status = message))
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        if (screenReceiverRegistered) unregisterReceiver(screenOff)
        display?.release()
        reader?.close()
        projection?.unregisterCallback(callback)
        projection?.stop()
        recognizer?.close()
        worker.shutdownNow()
        OcrPreview.publish(OcrPreview.State(status = stoppedMessage))
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_START = "com.magicboard.keyboard.OCR_START"
        internal const val EXTRA_AUTHORIZATION = "authorization"
        internal const val EXTRA_RESULT_CODE = "resultCode"
        private const val ACTION_STOP = "com.magicboard.keyboard.OCR_STOP"
        private const val CHANNEL = "ocr_capture"
        private const val NOTIFICATION_ID = 7
    }
}
