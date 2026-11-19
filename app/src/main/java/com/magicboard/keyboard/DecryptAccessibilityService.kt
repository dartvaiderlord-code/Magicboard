package com.magicboard.keyboard

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.util.ArrayDeque
import java.util.concurrent.Executors

/** Shows authenticated ENC1 messages over visible read-only nodes in opted-in apps. */
class DecryptAccessibilityService : AccessibilityService() {
    private data class Candidate(val token: String, val bounds: Rect)
    private data class Overlay(val candidate: Candidate, val view: TextView)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cache = LruCache<String, String>(32)
    private val failed = LruCache<String, Boolean>(32)
    private val overlays = mutableListOf<Overlay>()
    private var revision = 0
    private var cacheRevision = 0
    private var running = false
    private var destroyed = false
    private var refreshQueued = false
    private var scanQueued = false
    private val scan = Runnable {
        scanQueued = false
        gatherAndDecrypt()
    }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (AppSettings.affectsDecryption(key)) {
            invalidate(clearCache = true)
            requestScan()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppSettings.prefs(this).registerOnSharedPreferenceChangeListener(settingsListener)
        requestScan()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString()
        if (pkg == packageName && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val windowChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val scrolled = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        if (windowChanged || scrolled) invalidate(clearCache = false)
        // Window events can originate from an IME. The active root is checked again before reading text.
        requestScan()
    }

    private fun requestScan() {
        if (destroyed || !AppSettings.consent(this)) return
        if (running) {
            refreshQueued = true
            return
        }
        if (!scanQueued) {
            scanQueued = true
            handler.postDelayed(scan, 140)
        }
    }

    private fun activeRoot(): AccessibilityNodeInfo? {
        if (!AppSettings.consent(this) || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        val root = rootInActiveWindow ?: return null
        if (AppSettings.allows(this, root.packageName?.toString().orEmpty())) return root
        root.recycle()
        return null
    }

    private fun gatherAndDecrypt() {
        val root = activeRoot() ?: run { invalidate(clearCache = true); return }
        val windowId = root.windowId
        val candidates = collect(root)
        val ready = candidates.filter { cache.get(it.token) != null }
        show(ready)
        val missing = candidates.map { it.token }.distinct()
            .filter { cache.get(it) == null && failed.get(it) == null }
        if (missing.isEmpty()) return

        val scanRevision = revision
        val keyRevision = cacheRevision
        running = true
        worker.execute {
            val passphrase = AppSettings.loadSecret(this)
            val decoded = if (passphrase == null) emptyMap() else missing.associateWith {
                runCatching { CryptoEngine.decrypt(it, passphrase) }.getOrNull()
            }
            handler.post {
                running = false
                if (destroyed) return@post
                if (keyRevision == cacheRevision) {
                    decoded.forEach { (token, text) ->
                        if (text == null) failed.put(token, true) else cache.put(token, text)
                    }
                }
                if (scanRevision == revision && !refreshQueued) {
                    val current = activeRoot()
                    val sameWindow = current?.windowId == windowId
                    current?.recycle()
                    if (sameWindow) show(candidates.filter { cache.get(it.token) != null })
                    else invalidate(clearCache = true)
                }
                if (refreshQueued) {
                    refreshQueued = false
                    requestScan()
                }
            }
        }
    }

    private fun collect(root: AccessibilityNodeInfo): List<Candidate> {
        val candidates = linkedSetOf<Candidate>()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val screen = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        var remaining = 400
        try {
            while (stack.isNotEmpty() && remaining-- > 0 && candidates.size < 8) {
                val node = stack.removeLast()
                try {
                    if (node.isVisibleToUser && !node.isPassword && !node.isEditable) {
                        val text = node.text?.trim()
                        val exact = text != null && CryptoEngine.isToken(text)
                        val token = if (exact) text.toString() else
                            (text?.let(CryptoEngine::visibleToken)
                                ?: node.contentDescription?.let(CryptoEngine::visibleToken))
                        if (token != null) {
                            val bounds = Rect().also(node::getBoundsInScreen)
                            if ((exact || bounds.height() <= screen.height() / 2) &&
                                bounds.intersect(screen) && bounds.width() > 0 &&
                                bounds.height() >= Ui.dp(this, 20)) {
                                candidates.add(Candidate(token, bounds))
                                continue
                            }
                        }
                        for (i in minOf(node.childCount, 100) - 1 downTo 0) {
                            node.getChild(i)?.let(stack::add)
                        }
                    }
                } finally {
                    node.recycle()
                }
            }
        } finally {
            stack.forEach(AccessibilityNodeInfo::recycle)
        }
        return candidates.toList()
    }

    private fun show(items: List<Candidate>) {
        if (overlays.map { it.candidate } == items) return
        clearOverlays()
        val wm = getSystemService(WindowManager::class.java)
        for (item in items) {
            val plain = cache.get(item.token) ?: continue
            val text = Ui.text(this, plain, 15f, Ui.dark).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0)
                background = Ui.rounded(Ui.privateSurface, 8, context)
                maxLines = (item.bounds.height() / lineHeight).coerceAtLeast(1)
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val lp = WindowManager.LayoutParams(
                item.bounds.width(), item.bounds.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_SECURE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.getAbsoluteGravity(Gravity.START, View.LAYOUT_DIRECTION_LTR)
                x = item.bounds.left
                y = item.bounds.top
            }
            try {
                wm.addView(text, lp)
                overlays.add(Overlay(item, text))
            } catch (_: WindowManager.BadTokenException) {
                // The accessibility window was removed during the update.
                break
            }
        }
    }

    private fun clearOverlays() {
        val wm = getSystemService(WindowManager::class.java)
        overlays.forEach {
            it.view.text = ""
            try {
                wm.removeViewImmediate(it.view)
            } catch (_: IllegalArgumentException) {
                // The window may already have been detached by the system.
            }
        }
        overlays.clear()
    }

    private fun invalidate(clearCache: Boolean) {
        revision++
        refreshQueued = false
        handler.removeCallbacks(scan)
        scanQueued = false
        clearOverlays()
        if (clearCache) {
            cacheRevision++
            cache.evictAll()
            failed.evictAll()
        }
    }

    override fun onInterrupt() = invalidate(clearCache = true)

    override fun onDestroy() {
        destroyed = true
        AppSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(settingsListener)
        invalidate(clearCache = true)
        worker.shutdownNow()
        super.onDestroy()
    }
}
