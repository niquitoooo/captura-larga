package com.prieto.capturalarga.stitch

import com.prieto.capturalarga.frame.FrameReader
import com.prieto.capturalarga.png.PngWriter
import java.io.OutputStream

/** Turns the rows kept of each capture into the final PNG, one row at a time. */
object StitchRenderer {
    private const val PROGRESS_EVERY_ROWS = 64

    /**
     * Writes [segments] one under the other. [openFrame] returns a reader for
     * an original capture index; this function closes every reader it opens,
     * and closes [out] when it is done.
     */
    fun writePng(
        segments: List<Segment>,
        frameHeight: Int,
        width: Int,
        out: OutputStream,
        openFrame: (frameIndex: Int) -> FrameReader,
        onProgress: (rowsDone: Int, totalRows: Int) -> Unit = { _, _ -> },
    ) {
        val total = segments.sumOf { it.height }
        val row = IntArray(width)
        var done = 0
        PngWriter(out, width, total).use { png ->
            for (segment in segments) {
                openFrame(segment.frameIndex).use { frame ->
                    require(frame.width == width && frame.height == frameHeight) {
                        "capture ${segment.frameIndex} is ${frame.width}x${frame.height}, " +
                            "expected ${width}x$frameHeight"
                    }
                    for (y in segment.top until segment.bottom) {
                        frame.readRow(y, row)
                        png.writeRow(row)
                        done++
                        if (done % PROGRESS_EVERY_ROWS == 0 || done == total) onProgress(done, total)
                    }
                }
            }
        }
    }
}
