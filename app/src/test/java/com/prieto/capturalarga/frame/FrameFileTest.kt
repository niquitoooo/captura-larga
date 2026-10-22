package com.prieto.capturalarga.frame

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FrameFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** 3x2 image: row 0 = red, green, blue; row 1 = black, white, 0x102030. */
    private fun sampleRgba(): ByteBuffer {
        val pixels = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0x000000, 0xFFFFFF, 0x102030)
        val buffer = ByteBuffer.allocate(pixels.size * 4)
        for (p in pixels) {
            buffer.put((p shr 16).toByte()).put((p shr 8).toByte()).put(p.toByte()).put(0x7F.toByte())
        }
        buffer.flip()
        return buffer
    }

    private fun sampleFile(): File {
        val file = folder.newFile("frame.raw")
        FrameFile.write(file, 3, 2, sampleRgba())
        return file
    }

    @Test
    fun aWholeCaptureComesBackAsItWasWritten() {
        val pixels = FramePixels.read(sampleFile())
        assertEquals(3, pixels.width)
        assertEquals(2, pixels.height)
        val stored = pixels.bytes.copyOfRange(pixels.offset, pixels.bytes.size)
        assertArrayEquals(sampleRgba().array(), stored)
        // Row 1 starts three pixels in: black is its first pixel.
        assertEquals(pixels.offset + 12, pixels.rowStart(1))
    }

    @Test
    fun aCutShortCaptureIsNotReadWhole() {
        val file = sampleFile()
        RandomAccessFile(file, "rw").use { it.setLength(file.length() - 1) }
        assertThrows(IOException::class.java) { FramePixels.read(file) }
    }

    @Test
    fun rowsComeBackAsOpaqueArgb() {
        FrameReader(sampleFile()).use { reader ->
            assertEquals(3, reader.width)
            assertEquals(2, reader.height)
            val row = IntArray(3)
            reader.readRow(1, row)
            assertArrayEquals(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF102030.toInt()), row)
            reader.readRow(0, row)
            assertArrayEquals(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()), row)
        }
    }

    @Test
    fun writeReplacesAnExistingFile() {
        val file = sampleFile()
        val one = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))
        FrameFile.write(file, 1, 1, one)
        assertEquals((FrameFile.HEADER_BYTES + 4).toLong(), file.length())
    }

    @Test
    fun wrongBufferSizeIsRejected() {
        val file = folder.newFile("bad.raw")
        assertThrows(IllegalArgumentException::class.java) {
            FrameFile.write(file, 2, 2, ByteBuffer.allocate(4))
        }
    }

    @Test
    fun truncatedFileIsRejected() {
        val file = sampleFile()
        RandomAccessFile(file, "rw").use { it.setLength(file.length() - 5) }
        assertThrows(IOException::class.java) { FrameReader(file) }
    }

    @Test
    fun emptyFileIsRejected() {
        assertThrows(IOException::class.java) { FrameReader(folder.newFile("empty.raw")) }
    }

    @Test
    fun rowOutOfRangeIsRejected() {
        FrameReader(sampleFile()).use { reader ->
            assertThrows(IllegalArgumentException::class.java) { reader.readRow(2, IntArray(3)) }
        }
    }
}
