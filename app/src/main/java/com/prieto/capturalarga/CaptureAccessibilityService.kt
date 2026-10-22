package com.prieto.capturalarga

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.prieto.capturalarga.session.CaptureSession
import java.io.File

/**
 * Owns a capture session: shows the floating controls, takes a screenshot on
 * each tap of the capture button, and hands the stored captures to the stitch
 * editor. It listens to no accessibility events and never reads window content.
 */
class CaptureAccessibilityService : AccessibilityService(), StitchControls.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private val tileHandler = Handler(Looper.getMainLooper())
    private lateinit var session: CaptureSession
    private lateinit var grabber: ScreenGrabber
    private var controls: StitchControls? = null
    private var busy = false
    private var retried = false
    private var captureToken = 0
    private var lastCaptureAt = 0L
    private var lastToast: Toast? = null
    private var lastToastAt = 0L

    val isSessionActive: Boolean get() = controls != null

    override fun onServiceConnected() {
        session = CaptureSession(File(cacheDir, CaptureSession.DIR_NAME))
        grabber = ScreenGrabber(this, session)
        instance = this
        refreshTile()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun shutDown() {
        if (instance !== this) return
        removeControls()
        grabber.shutdown()
        instance = null
        refreshTile(again = false)
    }

    /** Called by the tile: starts a session, or cancels the one in progress. */
    fun toggleSession() {
        if (isSessionActive) cancelSession() else startSession()
    }

    private fun startSession() {
        Timing.mark("startSession entered")
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        Timing.mark("dismiss-shade action returned")
        val leftover = session.count
        session.start()
        Timing.mark("session folder reset ($leftover leftover captures)")
        grabber.reset()
        busy = false
        captureToken++
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        controls = StitchControls(this, this).also {
            Timing.mark("controls inflated")
            it.show(withHint = !preferences.getBoolean(KEY_HINT_SEEN, false)) {
                preferences.edit().putBoolean(KEY_HINT_SEEN, true).apply()
            }
            Timing.mark("controls window added")
        }
        refreshTile()
        Timing.mark("startSession done")
    }

    private fun cancelSession() {
        removeControls()
        session.clear()
        refreshTile()
    }

    private fun removeControls() {
        handler.removeCallbacksAndMessages(null)
        controls?.remove()
        controls = null
        busy = false
        captureToken++
    }

    override fun onCapture() {
        val current = controls ?: return
        if (busy) return
        if (session.isFull) {
            toast(getString(R.string.toast_limit, CaptureSession.MAX_CAPTURES))
            return
        }
        busy = true
        retried = false
        val token = ++captureToken
        current.setBusy(true)
        current.setVisible(false)
        // A notice from the previous tap may still be up and would end up in the capture: take it
        // down, and give it longer to leave the screen than the controls need.
        val now = SystemClock.uptimeMillis()
        val toastMayShow = lastToast != null && now - lastToastAt < TOAST_VISIBLE_MS
        lastToast?.cancel()
        lastToast = null
        val hideDelay = if (toastMayShow) TOAST_CLEAR_DELAY_MS else HIDE_DELAY_MS
        // Wait for the hidden controls to leave the screen, and respect Android's minimum gap between screenshots.
        val untilAllowed = lastCaptureAt + MIN_INTERVAL_MS - now
        handler.postDelayed({ grab(token) }, maxOf(hideDelay, untilAllowed))
        // If the system never answers, recover as from a failed capture instead of leaving the controls hidden.
        handler.postDelayed({
            if (token == captureToken && busy) onOutcome(token, ScreenGrabber.Outcome.Failed(ScreenGrabber.INTERNAL_ERROR))
        }, WATCHDOG_MS)
    }

    private fun grab(token: Int) {
        if (token != captureToken) return // the attempt was already recovered or cancelled
        grabber.grab { outcome -> onOutcome(token, outcome) }
    }

    /** [token] identifies the capture attempt the outcome belongs to. */
    private fun onOutcome(token: Int, outcome: ScreenGrabber.Outcome) {
        val current = controls
        if (current == null) {
            // The session ended meanwhile: nobody is left to show the capture.
            (outcome as? ScreenGrabber.Outcome.Saved)?.thumbnail?.recycle()
            return
        }
        if (token != captureToken || !busy) {
            // A late outcome of an attempt already recovered or superseded: if it still stored a
            // capture, the strip has to show it, because the session has it.
            (outcome as? ScreenGrabber.Outcome.Saved)?.let { current.addThumbnail(it.thumbnail) }
            return
        }
        if (outcome is ScreenGrabber.Outcome.Failed &&
            outcome.errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && !retried
        ) {
            retried = true
            handler.postDelayed({ grab(token) }, MIN_INTERVAL_MS)
            return
        }
        captureToken++ // the attempt is over: the watchdog and any late outcome become no-ops
        lastCaptureAt = SystemClock.uptimeMillis()
        busy = false
        current.setVisible(true)
        current.setBusy(false)
        when (outcome) {
            is ScreenGrabber.Outcome.Saved -> {
                current.addThumbnail(outcome.thumbnail)
                current.confirmHaptic()
                if (session.isFull) {
                    toast(getString(R.string.toast_limit, CaptureSession.MAX_CAPTURES))
                } else if (outcome.singleColor) {
                    toast(getString(R.string.toast_blocked))
                }
            }
            ScreenGrabber.Outcome.SizeChanged -> toast(getString(R.string.toast_size_changed))
            is ScreenGrabber.Outcome.Failed -> toast(getString(R.string.toast_capture_failed))
        }
    }

    /** The strip shows the captures in the order the session stores them. */
    override fun onRemove(index: Int) {
        session.files().getOrNull(index)?.let(session::remove)
    }

    override fun onDone() {
        if (busy) return
        val count = session.count
        if (count == 0) {
            removeControls()
            refreshTile()
            session.clear()
            return
        }
        // Start the editor while the controls are still on screen, then take them down.
        startActivity(
            Intent(this, PreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        removeControls()
        refreshTile()
    }

    override fun onCancel() = cancelSession()

    /**
     * Asks the system to let the tile show whether a session is on. A session
     * usually changes on a tap of the tile, with the Quick Settings panel still
     * open, and the panel drops what it was asked as it closes: so the request is
     * made now and, unless [again] is false, once more when the panel has closed.
     */
    private fun refreshTile(again: Boolean = true) {
        val tile = ComponentName(this, CaptureTileService::class.java)
        tileHandler.removeCallbacksAndMessages(null)
        TileService.requestListeningState(this, tile)
        if (again) tileHandler.postDelayed({ TileService.requestListeningState(this, tile) }, PANEL_CLOSE_MS)
    }

    /** Only the newest notice stays up, so there is never more than one to take down before a capture. */
    private fun toast(message: String) {
        lastToast?.cancel()
        lastToast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { it.show() }
        lastToastAt = SystemClock.uptimeMillis()
    }

    companion object {
        /** The connected service, or null while accessibility is switched off. */
        @Volatile
        var instance: CaptureAccessibilityService? = null
            private set

        private const val HIDE_DELAY_MS = 150L
        private const val MIN_INTERVAL_MS = 400L

        /** How long after it was shown a toast may still be on screen, and how long a cancelled one takes to go. */
        private const val TOAST_VISIBLE_MS = 3500L
        private const val TOAST_CLEAR_DELAY_MS = 450L

        /** How long a capture may take before it is treated as failed, so the controls never stay hidden. */
        private const val WATCHDOG_MS = 8000L

        /** Longer than the Quick Settings panel takes to close after a tap on the tile (about 300 ms on a Pixel). */
        private const val PANEL_CLOSE_MS = 1000L

        private const val PREFERENCES = "captura_larga"
        private const val KEY_HINT_SEEN = "hint_seen"
    }
}
