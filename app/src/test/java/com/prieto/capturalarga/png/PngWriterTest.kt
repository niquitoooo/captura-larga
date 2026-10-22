package com.prieto.capturalarga.png

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PngWriterTest {
    private fun encode(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int): ByteArray {
        val out = ByteArrayOutputStream()
        PngWriter(out, width, height).use { png ->
            val row = IntArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) row[x] = pixel(x, y)
                png.writeRow(row)
            }
        }
        return out.toByteArray()
    }

    private fun assertDecodesTo(bytes: ByteArray, width: Int, height: Int, pixel: (x: Int, y: Int) -> Int) {
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertNotNull("not a readable PNG", image)
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                assertEquals("pixel $x,$y", pixel(x, y) and 0xFFFFFF, image.getRGB(x, y) and 0xFFFFFF)
            }
        }
    }

    @Test
    fun smallImageRoundTrips() {
        val pixel = { x: Int, y: Int -> (x * 50 shl 16) or (y * 60 shl 8) or (x + y) }
        assertDecodesTo(encode(5, 4, pixel), 5, 4, pixel)
    }

    @Test
    fun alphaIsIgnored() {
        val bytes = encode(2, 1) { x, _ -> if (x == 0) 0x00FF8040 else 0x80FF8040.toInt() }
        assertDecodesTo(bytes, 2, 1) { _, _ -> 0xFF8040 }
    }

    @Test
    fun singlePixelColumnRoundTrips() {
        val pixel = { _: Int, y: Int -> y * 0x010203 }
        assertDecodesTo(encode(1, 80, pixel), 1, 80, pixel)
    }

    @Test
    fun noisyImageSpansSeveralDataChunks() {
        val random = Random(3)
        val pixels = IntArray(300 * 300) { random.nextInt() and 0xFFFFFF }
        val pixel = { x: Int, y: Int -> pixels[y * 300 + x] }
        val bytes = encode(300, 300, pixel)
        assertTrue("expected more than one 64 KiB chunk", bytes.size > 2 * 65536)
        assertDecodesTo(bytes, 300, 300, pixel)
    }

    @Test
    fun tallFlatImageCompressesWell() {
        val pixel = { x: Int, y: Int -> if (y % 100 < 50) 0xFFFFFF else (x * 4) }
        val bytes = encode(64, 6000, pixel)
        assertTrue("flat rows should compress, got ${bytes.size} bytes", bytes.size < 64 * 6000 * 3 / 20)
        assertDecodesTo(bytes, 64, 6000, pixel)
    }

    @Test
    fun closingEarlyFails() {
        val png = PngWriter(ByteArrayOutputStream(), 2, 2)
        png.writeRow(IntArray(2))
        assertThrows(IllegalStateException::class.java) { png.close() }
    }

    @Test
    fun extraRowFails() {
        val png = PngWriter(ByteArrayOutputStream(), 2, 1)
        png.writeRow(IntArray(2))
        assertThrows(IllegalStateException::class.java) { png.writeRow(IntArray(2)) }
    }

    @Test
    fun invalidSizeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PngWriter(ByteArrayOutputStream(), 0, 5) }
    }
}
