package com.prieto.capturalarga.frame

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A whole capture held in memory: [height] rows of [width] pixels, 4 bytes each
 * in R, G, B, A order, from [offset] of [bytes] on.
 */
class FramePixels(val width: Int, val height: Int, val bytes: ByteArray, val offset: Int = 0) {
    init {
        require(width > 0 && height > 0) { "invalid size ${width}x$height" }
        require(bytes.size - offset == width * height * FrameFile.BYTES_PER_PIXEL) {
            "expected ${width * height * FrameFile.BYTES_PER_PIXEL} bytes of pixels, got ${bytes.size - offset}"
        }
    }

    /** Where row [y] starts in [bytes]. */
    fun rowStart(y: Int): Int = offset + y * width * FrameFile.BYTES_PER_PIXEL

    companion object {
        /** Reads a whole [FrameFile]. */
        fun read(file: File): FramePixels {
            val bytes = file.readBytes()
            if (bytes.size < FrameFile.HEADER_BYTES) throw IOException("frame file too short: $file")
            val header = ByteBuffer.wrap(bytes, 0, FrameFile.HEADER_BYTES)
            val width = header.int
            val height = header.int
            val expected = FrameFile.HEADER_BYTES + width.toLong() * height * FrameFile.BYTES_PER_PIXEL
            if (width <= 0 || height <= 0 || bytes.size.toLong() != expected) throw IOException("corrupt frame file: $file")
            return FramePixels(width, height, bytes, FrameFile.HEADER_BYTES)
        }
    }
}
