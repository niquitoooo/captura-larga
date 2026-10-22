package com.prieto.capturalarga.stitch

import com.prieto.capturalarga.TestFrames
import com.prieto.capturalarga.TestFrames.HEIGHT
import com.prieto.capturalarga.TestFrames.WIDTH
import com.prieto.capturalarga.frame.FrameFile
import com.prieto.capturalarga.frame.FramePixels
import com.prieto.capturalarga.frame.FrameReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StitchRendererTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val page = TestFrames.page(8000, seed = 11)

    private fun store(offsets: List<Int>): List<File> = offsets.mapIndexed { i, offset ->
        folder.newFile("frame_$i.raw").also {
            FrameFile.write(it, WIDTH, HEIGHT, TestFrames.toRgba(TestFrames.frame(page, offset)))
        }
    }

    /** The captures joined by the automatic stitch. */
    private fun joined(files: List<File>): StitchEdit =
        StitchEdit.whole(HEIGHT, files.size).withAuto(StripMatcher.matchAll(files.size) { FramePixels.read(files[it]) })

    private fun render(edit: StitchEdit, files: List<File>): ByteArray {
        val out = ByteArrayOutputStream()
        StitchRenderer.writePng(edit.segments(), HEIGHT, WIDTH, out, { FrameReader(files[it]) })
        return out.toByteArray()
    }

    private fun assertPng(expected: List<IntArray>, bytes: ByteArray) {
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(WIDTH, image.width)
        assertEquals(expected.size, image.height)
        for (y in expected.indices) {
            for (x in 0 until WIDTH) {
                assertEquals("pixel $x,$y", expected[y][x] and 0xFFFFFF, image.getRGB(x, y) and 0xFFFFFF)
            }
        }
    }

    @Test
    fun filesOnDiskBecomeTheStitchedPage() {
        val files = store(listOf(0, 150, 330))
        assertPng(TestFrames.expected(page, 0, 330), render(joined(files), files))
    }

    @Test
    fun twentyFramesRender() {
        val offsets = (0 until 20).map { it * 200 }
        val files = store(offsets)
        assertPng(TestFrames.expected(page, 0, offsets.last()), render(joined(files), files))
    }

    @Test
    fun progressEndsAtTheTotal() {
        val files = store(listOf(0, 150))
        val edit = joined(files)
        var last = 0 to 0
        StitchRenderer.writePng(edit.segments(), HEIGHT, WIDTH, ByteArrayOutputStream(), { FrameReader(files[it]) }) { done, total ->
            assertTrue(done >= last.first)
            last = done to total
        }
        assertEquals(edit.totalHeight to edit.totalHeight, last)
    }

    @Test
    fun segmentsCutByHandAreWrittenAsTheyAre() {
        val files = store(listOf(0, 150))
        val edit = StitchEdit.whole(HEIGHT, 2).cut(0, upper = Crop(0, 300), lower = Crop(120, HEIGHT))
        val out = ByteArrayOutputStream()
        StitchRenderer.writePng(edit.segments(), HEIGHT, WIDTH, out, { FrameReader(files[it]) })
        val first = TestFrames.frame(page, 0)
        val second = TestFrames.frame(page, 150)
        assertPng(first.take(300) + second.drop(120), out.toByteArray())
    }

    @Test
    fun wrongWidthIsRejected() {
        val files = store(listOf(0, 150))
        val segments = StitchEdit.whole(HEIGHT, 2).segments()
        assertThrows(IllegalArgumentException::class.java) {
            StitchRenderer.writePng(segments, HEIGHT, WIDTH + 1, ByteArrayOutputStream(), { FrameReader(files[it]) })
        }
    }
}
