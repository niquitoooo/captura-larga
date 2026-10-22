package com.prieto.capturalarga

import com.prieto.capturalarga.frame.FramePixels
import java.nio.ByteBuffer
import java.util.Random

/** Synthetic pages and screen captures shared by the stitching tests. */
object TestFrames {
    const val WIDTH = 64
    const val HEIGHT = 400
    const val HEADER = 40
    const val FOOTER = 30
    const val CONTENT = HEIGHT - HEADER - FOOTER

    private const val WHITE = 0xFFFFFFFF.toInt()

    val headerRows: Array<IntArray> = noiseRows(HEADER, seed = 101)
    val footerRows: Array<IntArray> = noiseRows(FOOTER, seed = 202)

    private fun gray(value: Int): Int = (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    fun noiseRows(count: Int, seed: Long): Array<IntArray> {
        val random = Random(seed)
        return Array(count) { IntArray(WIDTH) { gray(random.nextInt(256)) } }
    }

    /** A tall page: blocks of noisy rows separated by blank gaps, like paragraphs of text. */
    fun page(height: Int, seed: Long): Array<IntArray> {
        val random = Random(seed)
        val rows = Array(height) { IntArray(WIDTH) { WHITE } }
        var y = 0
        while (y < height) {
            val block = 8 + random.nextInt(23)
            var i = 0
            while (i < block && y < height) {
                for (x in 0 until WIDTH) rows[y][x] = gray(random.nextInt(256))
                y++
                i++
            }
            y += 4 + random.nextInt(17)
        }
        return rows
    }

    /** One screen capture: fixed header, CONTENT rows of [page] starting at [offset], fixed footer. */
    fun frame(page: Array<IntArray>, offset: Int): Array<IntArray> = Array(HEIGHT) { y ->
        when {
            y < HEADER -> headerRows[y].copyOf()
            y < HEADER + CONTENT -> page[offset + y - HEADER].copyOf()
            else -> footerRows[y - HEADER - CONTENT].copyOf()
        }
    }

    /** One screen capture with its own bars: [top], rows of [page] starting at [offset], then [bottom]. */
    fun frameWith(
        page: Array<IntArray>,
        offset: Int,
        top: Array<IntArray>,
        bottom: Array<IntArray> = footerRows,
    ): Array<IntArray> {
        val content = HEIGHT - top.size - bottom.size
        return Array(HEIGHT) { y ->
            when {
                y < top.size -> top[y].copyOf()
                y < top.size + content -> page[offset + y - top.size].copyOf()
                else -> bottom[y - top.size - content].copyOf()
            }
        }
    }

    /** What a perfect stitch of captures from [firstOffset] to [lastOffset] looks like. */
    fun expected(page: Array<IntArray>, firstOffset: Int, lastOffset: Int): List<IntArray> =
        headerRows.toList() +
            (firstOffset until lastOffset + CONTENT).map { page[it] } +
            footerRows.toList()

    fun toRgba(frame: Array<IntArray>): ByteBuffer {
        val buffer = ByteBuffer.allocate(frame.size * WIDTH * 4)
        for (row in frame) {
            for (p in row) {
                buffer.put((p shr 16).toByte()).put((p shr 8).toByte()).put(p.toByte()).put(0xFF.toByte())
            }
        }
        buffer.flip()
        return buffer
    }

    /** [frame] as a capture held in memory. */
    fun pixels(frame: Array<IntArray>): FramePixels {
        val width = frame.firstOrNull()?.size ?: WIDTH
        val buffer = ByteBuffer.allocate(frame.size * width * 4)
        for (row in frame) {
            for (p in row) {
                buffer.put((p shr 16).toByte()).put((p shr 8).toByte()).put(p.toByte()).put(0xFF.toByte())
            }
        }
        return FramePixels(width, frame.size, buffer.array())
    }
}
