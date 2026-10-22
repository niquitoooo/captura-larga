package com.prieto.capturalarga

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import com.prieto.capturalarga.frame.FrameFile
import com.prieto.capturalarga.frame.SolidColor
import com.prieto.capturalarga.session.CaptureSession
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** Takes one screenshot through the accessibility service and stores it in the session. */
class ScreenGrabber(private val service: AccessibilityService, private val session: CaptureSession) {

    sealed interface Outcome {
        /** [thumbnail] is a small copy of the capture; whoever receives the outcome owns it. */
        data class Saved(val singleColor: Boolean, val thumbnail: Bitmap) : Outcome
        data object SizeChanged : Outcome
        data class Failed(val errorCode: Int) : Outcome
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pixels: ByteBuffer? = null
    private var expectedWidth = 0
    private var expectedHeight = 0

    /** Forgets the capture size of the previous session. */
    fun reset() {
        expectedWidth = 0
        expectedHeight = 0
    }

    fun shutdown() {
        worker.shutdown()
    }

    /** [onOutcome] runs on the main thread, once per call unless the system never calls back. */
    fun grab(onOutcome: (Outcome) -> Unit) {
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY, worker,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        // Throwable, not Exception: an OutOfMemoryError must not escape the worker thread.
                        val outcome = try {
                            store(result)
                        } catch (e: Throwable) {
                            Log.e(TAG, "could not store capture", e)
                            Outcome.Failed(INTERNAL_ERROR)
                        }
                        main.post { onOutcome(outcome) }
                    }

                    override fun onFailure(errorCode: Int) {
                        main.post { onOutcome(Outcome.Failed(errorCode)) }
                    }
                },
            )
        } catch (e: Throwable) {
            Log.e(TAG, "could not request a screenshot", e)
            main.post { onOutcome(Outcome.Failed(INTERNAL_ERROR)) }
        }
    }

    private fun store(result: AccessibilityService.ScreenshotResult): Outcome {
        val hardware = result.hardwareBuffer
        val bitmap: Bitmap
        try {
            val wrapped = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                ?: return Outcome.Failed(INTERNAL_ERROR)
            val copy = wrapped.copy(Bitmap.Config.ARGB_8888, false)
            wrapped.recycle()
            bitmap = toSrgb(copy ?: return Outcome.Failed(INTERNAL_ERROR))
        } finally {
            hardware.close()
        }

        val width = bitmap.width
        val height = bitmap.height
        if (expectedWidth == 0) {
            expectedWidth = width
            expectedHeight = height
        } else if (width != expectedWidth || height != expectedHeight) {
            bitmap.recycle()
            return Outcome.SizeChanged
        }

        val needed = width * height * FrameFile.BYTES_PER_PIXEL
        val buffer = pixels?.takeIf { it.capacity() == needed }
            ?: ByteBuffer.allocateDirect(needed).also { pixels = it }
        buffer.clear()
        bitmap.copyPixelsToBuffer(buffer)
        buffer.flip()
        val singleColor = SolidColor.isSingleColor(width, height) { x, y -> bitmap.getPixel(x, y) }
        val thumbnail = Bitmap.createScaledBitmap(bitmap, THUMBNAIL_WIDTH, height * THUMBNAIL_WIDTH / width, true)
        bitmap.recycle()

        try {
            session.add { file -> FrameFile.write(file, width, height, buffer) }
        } catch (e: Throwable) {
            thumbnail.recycle()
            throw e
        }
        return Outcome.Saved(singleColor, thumbnail)
    }

    /** Wide-gamut screenshots are converted, because the PNG is written without a colour profile. */
    private fun toSrgb(bitmap: Bitmap): Bitmap {
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        val current = bitmap.colorSpace
        if (current == null || current == srgb) return bitmap
        val converted = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888, true, srgb)
        Canvas(converted).drawBitmap(bitmap, 0f, 0f, null)
        bitmap.recycle()
        return converted
    }

    companion object {
        const val INTERNAL_ERROR = -1

        /** Width in pixels of the thumbnail kept for the strip of captures. */
        private const val THUMBNAIL_WIDTH = 216
        private const val TAG = "CapturaLarga"
    }
}
