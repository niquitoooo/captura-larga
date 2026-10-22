package com.prieto.capturalarga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.prieto.capturalarga.stitch.Crop
import kotlin.math.roundToInt

/**
 * One capture in the stitch editor, showing only the rows of its [Crop].
 *
 * While its seam is being cut it also shows two handles, one on its top edge
 * and one on its bottom edge: dragging one inwards darkens what would be cut
 * away. Nothing changes until the cut is confirmed and [range] is read.
 */
class CaptureCropView(context: Context) : View(context) {
    private enum class Handle { TOP, BOTTOM }

    /** The whole capture, reduced: one pixel of it for every [step] of the capture. */
    private var bitmap: Bitmap? = null
    private var step = 1
    private var frameWidth = 1
    private var crop = Crop(0, 1)

    private var cutting = false
    private var topY = 0f
    private var bottomY = 0f
    private var grabbed: Handle? = null
    private var lastY = 0f

    private val density = resources.displayMetrics.density
    private val tabWidth = 48f * density
    private val tabHeight = 16f * density
    private val tabRadius = 6f * density
    private val touchSlop = 15f * density

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = context.getColor(R.color.cut_dim) }
    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.cut_handle) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.cut_handle)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.cut_handle_grip)
        strokeWidth = 1.5f * density
    }
    private val tabPath = Path()
    private val tabRect = RectF()

    fun setCapture(bitmap: Bitmap, step: Int, frameWidth: Int) {
        this.bitmap = bitmap
        this.step = step
        this.frameWidth = frameWidth
        requestLayout()
    }

    fun setCrop(newCrop: Crop) {
        if (newCrop == crop) return
        crop = newCrop
        requestLayout()
        invalidate()
    }

    /** Shows the two handles on the edges of what the capture shows now. */
    fun startCut() {
        cutting = true
        grabbed = null
        topY = 0f
        bottomY = height.toFloat()
        invalidate()
    }

    fun endCut() {
        cutting = false
        grabbed = null
        invalidate()
    }

    /** What lies between the handles, in rows of the capture; null when neither was moved. */
    fun range(): Crop? {
        if (!cutting || (topY <= 0f && bottomY >= height)) return null
        val rowsPerPixel = 1f / scale()
        return Crop(crop.top + (topY * rowsPerPixel).roundToInt(), crop.top + (bottomY * rowsPerPixel).roundToInt())
    }

    /** Pixels of this view per row of the capture. */
    private fun scale(): Float = width.toFloat() / frameWidth

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (crop.height <= 0) 0 else maxOf(1, (crop.height.toLong() * width / frameWidth).toInt())
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val image = bitmap ?: return
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.translate(0f, -crop.top * scale())
        val zoom = width.toFloat() / image.width
        canvas.scale(zoom, zoom)
        canvas.drawBitmap(image, 0f, 0f, bitmapPaint)
        canvas.restore()
        if (!cutting) return

        val right = width.toFloat()
        if (topY > 0f) canvas.drawRect(0f, 0f, right, topY, dimPaint)
        if (bottomY < height) canvas.drawRect(0f, bottomY, right, height.toFloat(), dimPaint)

        val half = linePaint.strokeWidth / 2
        canvas.drawLine(0f, topY + half, right, topY + half, linePaint)
        canvas.drawLine(0f, bottomY - half, right, bottomY - half, linePaint)
        drawTab(canvas, topY, hangsDown = true)
        drawTab(canvas, bottomY, hangsDown = false)
    }

    /** The small tab of a handle: it hangs from the cut line into the part that is kept. */
    private fun drawTab(canvas: Canvas, lineY: Float, hangsDown: Boolean) {
        val centre = width / 2f
        val top = if (hangsDown) lineY else lineY - tabHeight
        tabRect.set(centre - tabWidth / 2, top, centre + tabWidth / 2, top + tabHeight)
        val r = tabRadius
        val radii = if (hangsDown) floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r) else floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        tabPath.rewind()
        tabPath.addRoundRect(tabRect, radii, Path.Direction.CW)
        canvas.drawPath(tabPath, tabPaint)
        val grip = 6f * density
        val middle = tabRect.centerY()
        canvas.drawLine(centre - grip, middle - 2f * density, centre + grip, middle - 2f * density, gripPaint)
        canvas.drawLine(centre - grip, middle + 2f * density, centre + grip, middle + 2f * density, gripPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!cutting) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                grabbed = handleAt(event.y) ?: return false
                lastY = event.y
                // The list this view sits in must not scroll while a handle is dragged.
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val handle = grabbed ?: return false
                val dy = event.y - lastY
                lastY = event.y
                // The two tabs never overlap: that is the smallest piece a capture can be cut down to.
                when (handle) {
                    Handle.TOP -> topY = (topY + dy).coerceIn(0f, maxOf(0f, bottomY - 2 * tabHeight))
                    Handle.BOTTOM ->
                        bottomY = (bottomY + dy).coerceIn(minOf(height.toFloat(), topY + 2 * tabHeight), height.toFloat())
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (grabbed == null) return false
                grabbed = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    /** A handle is its line and tab, across the whole width, plus some slack for the finger. */
    private fun handleAt(y: Float): Handle? = when {
        y >= topY - touchSlop && y <= topY + tabHeight + touchSlop -> Handle.TOP
        y >= bottomY - tabHeight - touchSlop && y <= bottomY + touchSlop -> Handle.BOTTOM
        else -> null
    }
}
