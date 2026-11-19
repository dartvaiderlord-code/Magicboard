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
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
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
        val status: String = "Сеанс не запущен"
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
    private data class ScanFrame(
        val bitmap: Bitmap, val visual: OcrFrameSignature,
        val screenWidth: Int, val screenHeight: Int, val geometry: Int
    )
    private data class VerifiedMessage(val rows: List<OcrMessageCodec.Row>, val plaintext: String)
    private data class Placement(
        val key: Pair<String, Int>, var lastRow: Int, val bounds: Rect,
        var lastBounds: Rect, val text: String
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val collector = OcrMessageCodec.Collector()
    private val verified = LinkedHashMap<Pair<String, Int>, VerifiedMessage>()
    private val overlay by lazy { OcrOverlay(this) }
    private var collectorKey = -1
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var recognizer: TextRecognizer? = null
    private var running = false
    private var analyzing = false
    private var stoppedMessage = "Сеанс завершён"
    private var nextScan = 0L
    private var nextComparison = 0L
    private var geometryRevision = 0
    private var captureWidth = 0
    private var captureHeight = 0
    private var latestVisual: OcrFrameSignature? = null
    private var pendingFrame: ScanFrame? = null
    private var coveredVisual: OcrFrameSignature? = null
    private var coveredKey = -1
    private val refreshOverlay = Runnable { clearOverlay() }
    private val startScan = Runnable { scanPendingFrame() }
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

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (!isVisible) {
                geometryRevision++
                clearOverlay()
                discardPendingFrame()
            }
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
        if (!Settings.canDrawOverlays(this)) {
            stopCapture("Разрешите показ поверх других приложений")
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
            OcrPreview.publish(OcrPreview.State(true, "OCR включён · ищу сообщения MB2"))
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
        if (reader?.width == w && reader?.height == h && captureWidth == width && captureHeight == height) return
        clearOverlay()
        discardPendingFrame()
        captureWidth = width
        captureHeight = height
        geometryRevision++
        latestVisual = null
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
        val elapsed = SystemClock.elapsedRealtime()
        val (width, height) = screenSize()
        val frame = image.use {
            if (!running) return
            if (captureWidth != width || captureHeight != height) {
                clearOverlay()
                discardPendingFrame()
                val message = "Откройте чат на весь экран или выберите захват всего экрана"
                if (OcrPreview.state.status != message) OcrPreview.publish(OcrPreview.State(true, message))
                return
            }
            val covered = coveredVisual
            if (covered != null) {
                if (elapsed < nextComparison) return
                val signature = OcrFrameSignature(image, width, height)
                latestVisual = signature
                nextComparison = elapsed + 150
                if (!Settings.canDrawOverlays(this) ||
                        coveredKey != AppSettings.secretRevision(this) ||
                        !covered.matches(signature, overlay.covered)) {
                    clearOverlay()
                }
                return
            }
            val original = OcrFrameSignature(image, width, height)
            val previous = latestVisual
            latestVisual = original
            if ((analyzing || elapsed < nextScan) && previous?.matches(original) == true) return
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, source.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val bitmap = if (paddedWidth == source.width) padded else
                Bitmap.createBitmap(padded, 0, 0, source.width, source.height).also { padded.recycle() }
            ScanFrame(bitmap, original, width, height, geometryRevision)
        }
        pendingFrame?.bitmap?.recycle()
        pendingFrame = frame
        scanPendingFrame()
    }

    private fun scanPendingFrame() {
        if (analyzing || !running || coveredVisual != null) return
        val engine = recognizer ?: return
        val frame = pendingFrame ?: return
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed < nextScan) {
            main.removeCallbacks(startScan)
            main.postDelayed(startScan, nextScan - elapsed)
            return
        }
        pendingFrame = null
        analyzing = true
        val frameWidth = frame.bitmap.width
        val frameHeight = frame.bitmap.height
        val width = frame.screenWidth
        val height = frame.screenHeight
        nextScan = elapsed + 650
        try {
            engine.process(InputImage.fromBitmap(frame.bitmap, 0)).addOnCompleteListener { task ->
                frame.bitmap.recycle()
                if (!running) return@addOnCompleteListener
                if (!task.isSuccessful) {
                    analyzing = false
                    stopCapture("Не удалось запустить локальное распознавание")
                    return@addOnCompleteListener
                }
                val recognized = task.result
                val key = AppSettings.secretRevision(this)
                worker.execute {
                    val positions = positionedRows(recognized)
                    val placements = decryptPositions(positions, key).map { message ->
                        val r = message.bounds
                        OverlayMessage(Rect(
                            (r.left * width.toFloat() / frameWidth).roundToInt(),
                            (r.top * height.toFloat() / frameHeight).roundToInt(),
                            (r.right * width.toFloat() / frameWidth).roundToInt(),
                            (r.bottom * height.toFloat() / frameHeight).roundToInt()
                        ), message.plaintext)
                    }
                    main.post {
                        analyzing = false
                        val latest = latestVisual
                        if (running && frame.geometry == geometryRevision && key == AppSettings.secretRevision(this) &&
                            latest != null) {
                            val stable = placements.filter {
                                frame.visual.matches(latest, region = it.bounds)
                            }
                            present(stable, latest, width, height, key)
                        }
                        scanPendingFrame()
                    }
                }
            }
        } catch (_: Exception) {
            frame.bitmap.recycle()
            stopCapture("Не удалось обработать кадр")
        }
    }

    private fun decryptPositions(rows: List<PositionedRow>, key: Int): List<OverlayMessage> {
        if (collectorKey != key) {
            collector.clear()
            verified.clear()
            collectorKey = key
        }
        val packets = collector.acceptRows(rows.map { it.row })
        val phrase = if (packets.isEmpty()) null else AppSettings.loadSecret(this)
        if (phrase != null) {
            for (packet in packets) {
                val plaintext = runCatching { CryptoEngine.decrypt(packet.token, phrase) }.getOrNull()
                if (plaintext != null) verified[packet.rows.first().messageKey] = VerifiedMessage(packet.rows, plaintext)
            }
        }
        while (verified.size > 64) verified.remove(verified.keys.first())

        val placements = mutableListOf<Placement>()
        for ((row, bounds) in rows.sortedBy { it.bounds.top }) {
            val message = verified[row.messageKey] ?: continue
            if (message.rows.getOrNull(row.index) != row) continue
            val last = placements.lastOrNull()
            if (last != null && last.key == row.messageKey && row.index > last.lastRow &&
                bounds.top - last.lastBounds.bottom <= last.lastBounds.height() * 2 &&
                bounds.right > last.lastBounds.left && bounds.left < last.lastBounds.right) {
                last.bounds.union(bounds)
                last.lastRow = row.index
                last.lastBounds = bounds
            } else {
                placements.add(Placement(row.messageKey, row.index, Rect(bounds), bounds, message.plaintext))
            }
        }
        return placements.map { OverlayMessage(it.bounds, it.text) }
    }

    private fun present(messages: List<OverlayMessage>, visual: OcrFrameSignature, width: Int, height: Int, key: Int) {
        if (messages.isEmpty()) return
        if (!overlay.show(messages, width, height)) {
            stopCapture("Android отозвал разрешение показа поверх других приложений")
            return
        }
        if (overlay.covered.isNotEmpty()) {
            discardPendingFrame()
            coveredVisual = visual
            coveredKey = key
            main.removeCallbacks(refreshOverlay)
            main.postDelayed(refreshOverlay, 12_000)
            OcrPreview.publish(OcrPreview.State(true, "OCR включён · сообщения распознаны"))
        }
    }

    private fun clearOverlay() {
        main.removeCallbacks(refreshOverlay)
        if (coveredVisual != null) overlay.clear()
        coveredVisual = null
        latestVisual = null
        nextScan = 0
    }

    private fun discardPendingFrame() {
        main.removeCallbacks(startScan)
        pendingFrame?.bitmap?.recycle()
        pendingFrame = null
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
        clearOverlay()
        discardPendingFrame()
        OcrPreview.publish(OcrPreview.State(status = message))
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        clearOverlay()
        discardPendingFrame()
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
