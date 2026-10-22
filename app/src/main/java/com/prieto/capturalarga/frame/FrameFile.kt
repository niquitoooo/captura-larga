package com.prieto.capturalarga.frame

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * A raw capture on disk: an 8-byte header (width and height as big-endian
 * ints) followed by height * width pixels, 4 bytes each in R, G, B, A order.
 * That is the layout Bitmap.copyPixelsToBuffer produces for ARGB_8888, so a
 * capture is stored without any encoding and read back one row at a time.
 */
object FrameFile {
    const val HEADER_BYTES = 8
    const val BYTES_PER_PIXEL = 4

    /** Writes [rgba] (exactly width * height * 4 remaining bytes) to [file], replacing it. */
    fun write(file: File, width: Int, height: Int, rgba: ByteBuffer) {
        require(width > 0 && height > 0) { "invalid size ${width}x$height" }
        require(rgba.remaining() == width * height * BYTES_PER_PIXEL) {
            "expected ${width * height * BYTES_PER_PIXEL} bytes, got ${rgba.remaining()}"
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val channel = raf.channel
            val header = ByteBuffer.allocate(HEADER_BYTES).putInt(width).putInt(height)
            header.flip()
            while (header.hasRemaining()) channel.write(header)
            while (rgba.hasRemaining()) channel.write(rgba)
        }
    }
}

/** Reads a [FrameFile] one row at a time. */
class FrameReader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val width: Int
    val height: Int
    private val rowBytes: ByteArray

    init {
        var w = 0
        var h = 0
        try {
            if (raf.length() < FrameFile.HEADER_BYTES) throw IOException("frame file too short: $file")
            w = raf.readInt()
            h = raf.readInt()
            val expected = FrameFile.HEADER_BYTES + w.toLong() * h * FrameFile.BYTES_PER_PIXEL
            if (w <= 0 || h <= 0 || raf.length() != expected) throw IOException("corrupt frame file: $file")
        } catch (e: Exception) {
            raf.close()
            throw e
        }
        width = w
        height = h
        rowBytes = ByteArray(w * FrameFile.BYTES_PER_PIXEL)
    }

    /** Reads row [y] into [into] as opaque 0xFFRRGGBB values. */
    fun readRow(y: Int, into: IntArray) {
        require(y in 0 until height) { "row $y outside 0 until $height" }
        require(into.size >= width) { "row buffer too small" }
        raf.seek(FrameFile.HEADER_BYTES + y.toLong() * width * FrameFile.BYTES_PER_PIXEL)
        raf.readFully(rowBytes)
        var i = 0
        for (x in 0 until width) {
            val r = rowBytes[i].toInt() and 0xFF
            val g = rowBytes[i + 1].toInt() and 0xFF
            val b = rowBytes[i + 2].toInt() and 0xFF
            into[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            i += FrameFile.BYTES_PER_PIXEL
        }
    }

    override fun close() = raf.close()
}
