package com.prieto.capturalarga.png

import java.io.Closeable
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Writes an 8-bit RGB PNG one row at a time, so an image far too tall to
 * hold in memory can still be saved. Rows use the "Up" filter: screenshots
 * repeat vertically, which makes most filtered bytes zero.
 */
class PngWriter(
    target: OutputStream,
    private val width: Int,
    private val height: Int,
    compressionLevel: Int = 4,
) : Closeable {
    private val out = DataOutputStream(target)
    private val deflater = Deflater(compressionLevel)
    private val idat = IdatStream()
    private val compressed = DeflaterOutputStream(idat, deflater, 1 shl 15)
    private val current: ByteArray
    private val previous: ByteArray
    private val filtered: ByteArray
    private var rowsWritten = 0
    private var closed = false

    init {
        require(width > 0 && height > 0) { "invalid size ${width}x$height" }
        current = ByteArray(width * 3)
        previous = ByteArray(width * 3)
        filtered = ByteArray(1 + width * 3)
        out.write(SIGNATURE)
        // 8 bits per channel, colour type 2 (RGB), deflate, adaptive filtering, no interlace.
        val header = ByteBuffer.allocate(13).putInt(width).putInt(height)
            .put(8).put(2).put(0).put(0).put(0).array()
        writeChunk("IHDR", header, header.size)
    }

    /** Writes the next row; [argb] holds at least width pixels, alpha is ignored. */
    fun writeRow(argb: IntArray) {
        check(!closed) { "writer is closed" }
        check(rowsWritten < height) { "all $height rows are already written" }
        require(argb.size >= width) { "row has ${argb.size} pixels, need $width" }
        var i = 0
        for (x in 0 until width) {
            val p = argb[x]
            current[i++] = (p shr 16).toByte()
            current[i++] = (p shr 8).toByte()
            current[i++] = p.toByte()
        }
        filtered[0] = FILTER_UP
        for (j in current.indices) filtered[j + 1] = (current[j] - previous[j]).toByte()
        compressed.write(filtered)
        System.arraycopy(current, 0, previous, 0, current.size)
        rowsWritten++
    }

    /** Finishes the file and closes the target stream. Fails if rows are missing. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            check(rowsWritten == height) { "wrote $rowsWritten of $height rows" }
            compressed.finish()
            idat.flushChunk()
            writeChunk("IEND", ByteArray(0), 0)
            out.flush()
        } finally {
            deflater.end()
            out.close()
        }
    }

    private fun writeChunk(type: String, data: ByteArray, length: Int) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data, 0, length)
        out.writeInt(length)
        out.write(typeBytes)
        out.write(data, 0, length)
        out.writeInt(crc.value.toInt())
    }

    /** Collects deflated bytes and emits them as IDAT chunks of at most IDAT_CHUNK bytes. */
    private inner class IdatStream : OutputStream() {
        private val buffer = ByteArray(IDAT_CHUNK)
        private var size = 0

        override fun write(b: Int) {
            if (size == buffer.size) flushChunk()
            buffer[size++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            while (remaining > 0) {
                if (size == buffer.size) flushChunk()
                val n = minOf(remaining, buffer.size - size)
                System.arraycopy(b, offset, buffer, size, n)
                size += n
                offset += n
                remaining -= n
            }
        }

        fun flushChunk() {
            if (size > 0) {
                writeChunk("IDAT", buffer, size)
                size = 0
            }
        }
    }

    private companion object {
        val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        const val FILTER_UP: Byte = 2
        const val IDAT_CHUNK = 1 shl 16
    }
}
