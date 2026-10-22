package com.prieto.capturalarga

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.prieto.capturalarga.frame.FramePixels
import com.prieto.capturalarga.frame.FrameReader
import com.prieto.capturalarga.session.CaptureSession
import com.prieto.capturalarga.stitch.Segment
import com.prieto.capturalarga.stitch.StitchEdit
import com.prieto.capturalarga.stitch.StitchRenderer
import com.prieto.capturalarga.stitch.StripMatcher
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The stitch editor: the captures of the session stacked in order, whole, with
 * a scissors button at each seam. Cutting a seam puts handles on the two
 * captures next to it; the Auto button tries to find the cuts by itself.
 * Saving writes the trimmed captures one under the other.
 */
class PreviewActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var session: CaptureSession
    private lateinit var files: List<File>
    private var frameWidth = 0
    private var frameHeight = 0
    private var previewStep = 1
    private var edit: StitchEdit? = null
    private var busy = false

    /** The seam whose two captures show their handles, or -1. */
    private var cuttingSeam = -1

    private lateinit var content: FrameLayout
    private lateinit var list: LinearLayout
    private lateinit var busyView: TextView
    private val captureViews = ArrayList<CaptureCropView>()
    private val seamRows = ArrayList<View>()
    private val previews = ArrayList<Bitmap>()
    private var sessionSnapshot: List<FileStamp>? = null
    private val onBack = OnBackInvokedCallback { handleBack() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        content = findViewById(R.id.editor_content)
        list = findViewById(R.id.editor_list)
        // The seam buttons follow the seams: after every layout, since a cut moves them even
        // when the stack as a whole keeps its height.
        content.viewTreeObserver.addOnGlobalLayoutListener { placeSeamButtons() }
        busyView = findViewById(R.id.editor_busy)
        findViewById<View>(R.id.editor_back).setOnClickListener { handleBack() }
        findViewById<View>(R.id.editor_share).setOnClickListener { save(thenShare = true) }
        findViewById<View>(R.id.editor_save).setOnClickListener { save(thenShare = false) }
        findViewById<View>(R.id.editor_auto).setOnClickListener { runAuto() }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, onBack)

        session = CaptureSession(File(cacheDir, CaptureSession.DIR_NAME))
        files = session.files()
        if (files.isEmpty()) {
            finish()
            return
        }
        sessionSnapshot = snapshotOf(files)

        val savedTops = savedInstanceState?.getIntArray(KEY_TOPS)
        val savedBottoms = savedInstanceState?.getIntArray(KEY_BOTTOMS)
        setBusy(true, getString(R.string.editor_loading))
        worker.execute {
            try {
                val bitmaps = loadPreviews()
                runOnUiThread { showCaptures(bitmaps, savedTops, savedBottoms) }
            } catch (e: Throwable) {
                // Throwable, not Exception: an OutOfMemoryError here must not kill the process,
                // which is also the one running the capture service.
                Log.e(TAG, "could not load the captures", e)
                runOnUiThread {
                    Toast.makeText(this, R.string.load_failed, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Opened again for the session already on screen (from the main screen): keep it as it is.
        val loaded = sessionSnapshot
        if (loaded != null && snapshotOf(session.files()) == loaded) return
        // A new session replaced the files this screen loaded: start over from scratch,
        // without carrying the old cuts through the saved instance state.
        sessionSnapshot = null
        edit = null
        recreate()
    }

    override fun onStart() {
        super.onStart()
        finishIfSessionReplaced()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A session started from the notification shade never stops this screen; it only takes its focus.
        if (hasFocus) finishIfSessionReplaced()
    }

    /**
     * The session folder changed behind this screen (a newer session reused the files), so what
     * is shown here is stale: leave without touching the new session.
     */
    private fun finishIfSessionReplaced() {
        val loaded = sessionSnapshot ?: return
        if (snapshotOf(session.files()) != loaded) {
            sessionSnapshot = null
            finish()
        }
    }

    private fun snapshotOf(captures: List<File>) = captures.map { FileStamp(it.name, it.lastModified(), it.length()) }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val current = edit ?: return
        outState.putIntArray(KEY_TOPS, current.crops.map { it.top }.toIntArray())
        outState.putIntArray(KEY_BOTTOMS, current.crops.map { it.bottom }.toIntArray())
    }

    override fun onDestroy() {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(onBack)
        worker.shutdownNow()
        content.removeAllViews()
        previews.forEach { it.recycle() }
        previews.clear()
        super.onDestroy()
    }

    // --- Loading and showing the captures ---

    /** A reduced copy of every capture, whole. Sets the size all of them must share. Worker thread. */
    private fun loadPreviews(): List<Bitmap> {
        val bitmaps = ArrayList<Bitmap>()
        try {
            for ((index, file) in files.withIndex()) {
                FrameReader(file).use { reader ->
                    if (index == 0) {
                        frameWidth = reader.width
                        frameHeight = reader.height
                        previewStep = maxOf(1, (frameWidth + PREVIEW_MAX_WIDTH - 1) / PREVIEW_MAX_WIDTH)
                    } else if (reader.width != frameWidth || reader.height != frameHeight) {
                        throw IOException("capture $index has another size")
                    }
                    bitmaps += reduced(reader)
                }
            }
        } catch (e: Throwable) {
            bitmaps.forEach { it.recycle() }
            throw e
        }
        return bitmaps
    }

    private fun reduced(reader: FrameReader): Bitmap {
        val step = previewStep
        val width = maxOf(1, reader.width / step)
        val height = maxOf(1, reader.height / step)
        val pixels = IntArray(width * height)
        val row = IntArray(reader.width)
        for (j in 0 until height) {
            reader.readRow(j * step, row)
            for (i in 0 until width) pixels[j * width + i] = row[i * step]
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565)
    }

    private fun showCaptures(bitmaps: List<Bitmap>, savedTops: IntArray?, savedBottoms: IntArray?) {
        if (isDestroyed) {
            bitmaps.forEach { it.recycle() }
            return
        }
        previews.addAll(bitmaps)
        val restored = if (savedTops != null && savedBottoms != null && savedTops.size == files.size) {
            StitchEdit.restore(frameHeight, savedTops, savedBottoms)
        } else {
            null
        }
        val current = restored ?: StitchEdit.whole(frameHeight, files.size)
        edit = current

        for (i in files.indices) {
            val capture = CaptureCropView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setCapture(previews[i], previewStep, frameWidth)
            }
            captureViews += capture
            list.addView(capture)
            if (i < files.lastIndex) {
                val row = layoutInflater.inflate(R.layout.row_seam, content, false)
                row.findViewById<View>(R.id.seam_cut).setOnClickListener { startCut(i) }
                row.findViewById<View>(R.id.seam_undo).setOnClickListener { undoCut(i) }
                row.findViewById<View>(R.id.seam_confirm).setOnClickListener { confirmCut() }
                row.findViewById<View>(R.id.seam_cancel).setOnClickListener { cancelCut() }
                seamRows += row
                // Over the margin beside the captures, not between them: it takes no room in the stack.
                content.addView(
                    row,
                    FrameLayout.LayoutParams(dp(SEAM_BUTTONS_WIDTH_DP), dp(SEAM_BUTTONS_HEIGHT_DP), Gravity.TOP or Gravity.END),
                )
            }
        }
        refresh()
        setBusy(false, "")
    }

    /**
     * Puts the buttons of each seam level with the line where its two captures meet. When
     * captures are so short that two sets would overlap, the later one goes right below.
     */
    private fun placeSeamButtons() {
        val height = dp(SEAM_BUTTONS_HEIGHT_DP)
        var free = 0
        for (i in seamRows.indices) {
            val seamY = list.top + captureViews[i].bottom
            val top = maxOf(seamY - height / 2, free)
            seamRows[i].translationY = top.toFloat()
            free = top + height
        }
    }

    /** Brings every row in line with the current edit. */
    private fun refresh() {
        val current = edit ?: return
        for (i in captureViews.indices) captureViews[i].setCrop(current.crops[i])
        for (i in seamRows.indices) {
            val row = seamRows[i]
            val cutting = i == cuttingSeam
            row.findViewById<View>(R.id.seam_idle).visibility = if (cutting) View.GONE else View.VISIBLE
            row.findViewById<View>(R.id.seam_cutting).visibility = if (cutting) View.VISIBLE else View.GONE
            row.findViewById<View>(R.id.seam_undo).visibility =
                if (current.canUndo(i)) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun setBusy(nowBusy: Boolean, message: String) {
        busy = nowBusy
        busyView.text = message
        busyView.visibility = if (nowBusy) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // --- Cutting a seam ---

    private fun startCut(seam: Int) {
        if (busy || edit == null) return
        if (cuttingSeam != -1) cancelCut()
        cuttingSeam = seam
        captureViews[seam].startCut()
        captureViews[seam + 1].startCut()
        refresh()
    }

    private fun confirmCut() {
        val current = edit ?: return
        val seam = cuttingSeam
        if (seam == -1) return
        val upper = captureViews[seam].range() ?: current.crops[seam]
        val lower = captureViews[seam + 1].range() ?: current.crops[seam + 1]
        endCut()
        edit = current.cut(seam, upper, lower)
        refresh()
    }

    private fun cancelCut() {
        if (cuttingSeam == -1) return
        endCut()
        refresh()
    }

    private fun endCut() {
        val seam = cuttingSeam
        if (seam == -1) return
        captureViews[seam].endCut()
        captureViews[seam + 1].endCut()
        cuttingSeam = -1
    }

    private fun undoCut(seam: Int) {
        val current = edit ?: return
        if (busy) return
        if (cuttingSeam != -1) cancelCut()
        edit = current.undo(seam)
        refresh()
    }

    // --- Auto ---

    private fun runAuto() {
        val current = edit ?: return
        if (busy || current.seamCount == 0) return
        cancelCut()
        setBusy(true, getString(R.string.analyzing))
        worker.execute {
            try {
                val started = SystemClock.uptimeMillis()
                val cuts = StripMatcher.matchAll(files.size) { FramePixels.read(files[it]) }
                Log.i(TAG, "automatic stitch of ${files.size} captures took ${SystemClock.uptimeMillis() - started} ms: $cuts")
                runOnUiThread { applyAuto(current, cuts) }
            } catch (e: Throwable) {
                Log.e(TAG, "automatic stitch failed", e)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    setBusy(false, "")
                    Toast.makeText(this, R.string.auto_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Like the app this follows, every seam is cut at the best place found, with no notice for the doubtful ones. */
    private fun applyAuto(from: StitchEdit, cuts: List<StripMatcher.Cut?>) {
        if (isDestroyed) return
        edit = from.withAuto(cuts)
        refresh()
        setBusy(false, "")
    }

    // --- Leaving ---

    private fun handleBack() {
        if (busy) return
        if (cuttingSeam != -1) {
            cancelCut()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_message)
            .setPositiveButton(R.string.exit_confirm) { _, _ -> finish() }
            .setNegativeButton(R.string.exit_cancel, null)
            .show()
    }

    // --- Saving ---

    private fun save(thenShare: Boolean) {
        val current = edit ?: return
        if (busy) return
        cancelCut()
        val segments = current.segments()
        setBusy(true, getString(R.string.saving, 0))
        worker.execute {
            val uri = try {
                writeToGallery(segments)
            } catch (e: Exception) {
                Log.e(TAG, "could not save", e)
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (uri == null) {
                    setBusy(false, "")
                    Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
                } else {
                    // From here the session is ours to clear: do not mistake that for a replaced session.
                    sessionSnapshot = null
                    session.clear()
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                    if (thenShare) share(uri)
                    finish()
                }
            }
        }
    }

    private fun writeToGallery(segments: List<Segment>): Uri {
        val name = "CapturaLarga_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values) ?: throw IOException("MediaStore rejected the image")
        try {
            val out = contentResolver.openOutputStream(uri) ?: throw IOException("no output stream for $uri")
            var lastPercent = -1
            StitchRenderer.writePng(
                segments, frameHeight, frameWidth, BufferedOutputStream(out, 1 shl 16), { FrameReader(files[it]) },
            ) { done, total ->
                val percent = (done.toLong() * 100 / total).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    runOnUiThread { if (busy) busyView.text = getString(R.string.saving, percent) }
                }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    /** What identifies a capture file as the one this screen loaded. */
    private data class FileStamp(val name: String, val modified: Long, val length: Long)


    private companion object {
        const val TAG = "CapturaLarga"
        const val PREVIEW_MAX_WIDTH = 540
        const val KEY_TOPS = "tops"
        const val KEY_BOTTOMS = "bottoms"

        /** The seam buttons, in the margin the layout leaves on the right of the captures. */
        const val SEAM_BUTTONS_WIDTH_DP = 72
        const val SEAM_BUTTONS_HEIGHT_DP = 40
    }
}
