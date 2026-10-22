package com.prieto.capturalarga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * What floats over the screen during a capture session: a row of round buttons
 * at the bottom (close, capture, done) and, once there is something to show, a
 * strip with a thumbnail of each capture at the top right, each with a button
 * to take it out. All of it are accessibility overlays, so [context] must be
 * the accessibility service.
 */
class StitchControls(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onCapture()
        fun onDone()
        fun onCancel()

        /** The capture at [index], oldest first, was taken out of the strip. */
        fun onRemove(index: Int)
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val inflater = LayoutInflater.from(context)

    private val bar: View = inflater.inflate(R.layout.stitch_controls, null)
    private val addButton: View = bar.findViewById(R.id.controls_add)
    private val strip: HorizontalScrollView =
        inflater.inflate(R.layout.stitch_thumbnails, null) as HorizontalScrollView
    private val thumbnails: LinearLayout = strip.findViewById(R.id.thumbnail_list)
    private var hint: View? = null

    private val barParams = overlayParams().apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = dp(48)
    }
    private val stripParams = overlayParams().apply {
        gravity = Gravity.TOP or Gravity.END
        x = dp(12)
        y = dp(12)
        height = dp(80)
    }

    private var barAttached = false
    private var stripAttached = false
    private var busy = false
    private var visible = true

    /** Captures shown in the strip. */
    val count: Int get() = thumbnails.childCount

    init {
        bar.findViewById<View>(R.id.controls_close).setOnClickListener { if (!busy) listener.onCancel() }
        addButton.setOnClickListener { if (!busy) listener.onCapture() }
        bar.findViewById<View>(R.id.controls_done).setOnClickListener { if (!busy) listener.onDone() }
    }

    /** Puts the buttons on screen; with [withHint], also the first-use explanation over everything. */
    fun show(withHint: Boolean, onHintClosed: () -> Unit) {
        if (barAttached) return
        windowManager.addView(bar, barParams)
        barAttached = true
        bar.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                bar.viewTreeObserver.removeOnPreDrawListener(this)
                Timing.mark("controls first draw")
                bar.postOnAnimation { Timing.mark("frame after the controls' first draw") }
                return true
            }
        })
        if (withHint) showHint(onHintClosed)
    }

    fun remove() {
        hideHint()
        if (stripAttached) {
            windowManager.removeViewImmediate(strip)
            stripAttached = false
        }
        if (barAttached) {
            windowManager.removeViewImmediate(bar)
            barAttached = false
        }
        for (i in 0 until thumbnails.childCount) recycleThumbnail(thumbnails.getChildAt(i))
        thumbnails.removeAllViews()
    }

    /** Hidden while a capture is taken, so none of this is in the picture. */
    fun setVisible(nowVisible: Boolean) {
        visible = nowVisible
        val state = if (nowVisible) View.VISIBLE else View.INVISIBLE
        bar.visibility = state
        strip.visibility = state
    }

    fun setBusy(nowBusy: Boolean) {
        busy = nowBusy
    }

    fun confirmHaptic() {
        bar.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    /** Adds the newest capture to the end of the strip. The strip owns [thumbnail] from here on. */
    fun addThumbnail(thumbnail: Bitmap) {
        val item = inflater.inflate(R.layout.stitch_thumbnail_item, thumbnails, false)
        val image: ImageView = item.findViewById(R.id.thumbnail_image)
        image.setImageBitmap(thumbnail)
        // Fill the width and show the top of the capture, like a page seen from its start.
        val scale = dp(THUMBNAIL_DP).toFloat() / thumbnail.width
        image.imageMatrix = Matrix().apply { setScale(scale, scale) }
        item.findViewById<View>(R.id.thumbnail_remove).setOnClickListener {
            if (!busy) removeThumbnail(item)
        }
        (item.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(THUMBNAIL_GAP_DP)
        thumbnails.addView(item)
        refreshStrip()
        strip.post { strip.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun removeThumbnail(item: View) {
        val index = thumbnails.indexOfChild(item)
        if (index < 0) return
        thumbnails.removeViewAt(index)
        recycleThumbnail(item)
        refreshStrip()
        listener.onRemove(index)
    }

    private fun recycleThumbnail(item: View) {
        val image: ImageView = item.findViewById(R.id.thumbnail_image)
        val bitmap = (image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        image.setImageDrawable(null)
        bitmap?.recycle()
    }

    /** Shows the strip only while it has captures, sized to them, and the remove buttons only when there are several. */
    private fun refreshStrip() {
        val several = thumbnails.childCount > 1
        for (i in 0 until thumbnails.childCount) {
            thumbnails.getChildAt(i).findViewById<View>(R.id.thumbnail_remove).visibility =
                if (several) View.VISIBLE else View.GONE
        }
        if (thumbnails.childCount == 0) {
            if (stripAttached) {
                windowManager.removeViewImmediate(strip)
                stripAttached = false
            }
            return
        }
        val wanted = thumbnails.childCount * dp(THUMBNAIL_DP + THUMBNAIL_GAP_DP) + dp(2 * STRIP_PADDING_DP)
        val room = windowManager.currentWindowMetrics.bounds.width() - 2 * stripParams.x
        stripParams.width = minOf(wanted, room)
        if (stripAttached) {
            windowManager.updateViewLayout(strip, stripParams)
        } else {
            strip.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            windowManager.addView(strip, stripParams)
            stripAttached = true
        }
    }

    private fun showHint(onClosed: () -> Unit) {
        val view = inflater.inflate(R.layout.stitch_hint, null)
        view.findViewById<View>(R.id.hint_ok).setOnClickListener {
            hideHint()
            onClosed()
        }
        val params = overlayParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        windowManager.addView(view, params)
        hint = view
    }

    private fun hideHint() {
        hint?.let { windowManager.removeViewImmediate(it) }
        hint = null
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    )

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val THUMBNAIL_DP = 72
        const val THUMBNAIL_GAP_DP = 4
        const val STRIP_PADDING_DP = 8
    }
}
