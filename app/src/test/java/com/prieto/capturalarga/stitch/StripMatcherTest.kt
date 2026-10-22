package com.prieto.capturalarga.stitch

import com.prieto.capturalarga.TestFrames
import com.prieto.capturalarga.TestFrames.HEIGHT
import com.prieto.capturalarga.TestFrames.WIDTH
import com.prieto.capturalarga.frame.FramePixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Captures are 400 rows: the strip is 20 rows, it is looked for in rows 0 until 300, and it never starts above row 100. */
class StripMatcherTest {
    private val page = TestFrames.page(8000, seed = 11)

    private fun pixels(frame: Array<IntArray>) = TestFrames.pixels(frame)

    /** A capture that is nothing but [HEIGHT] rows of the page, from [offset] on. */
    private fun plain(offset: Int): Array<IntArray> = Array(HEIGHT) { page[offset + it].copyOf() }

    private fun match(upper: Array<IntArray>, lower: Array<IntArray>, upperTop: Int = 0) =
        StripMatcher.match(pixels(upper), pixels(lower), upperTop)

    @Test
    fun cutsAtTheLowestStripOfTheUpperCapture() {
        // The page moved 150 rows: the upper capture's last 20 rows are rows 230 until 250 of the lower one.
        val cut = match(plain(0), plain(150))!!
        assertEquals(380, cut.upperBottom)
        assertEquals(230, cut.lowerTop)
        assertTrue(cut.sure)
    }

    @Test
    fun aBarOverTheBottomOfEveryCaptureIsLeftOut() {
        // Rows 370 until 400 are the same footer in both captures, so the two lowest strips are
        // not in the upper three quarters of the lower capture. The third one, rows 340 until 360, is.
        val cut = match(TestFrames.frameWith(page, 0, top = emptyArray()), TestFrames.frameWith(page, 150, top = emptyArray()))!!
        assertEquals(340, cut.upperBottom)
        assertEquals(190, cut.lowerTop)
        assertTrue(cut.sure)
    }

    @Test
    fun theLowerCapturesFixedHeaderIsLeftOut() {
        // Both captures start with the same 40-row header; the page under it moved 150 rows.
        val upper = TestFrames.frameWith(page, 0, top = TestFrames.headerRows, bottom = emptyArray())
        val lower = TestFrames.frameWith(page, 150, top = TestFrames.headerRows, bottom = emptyArray())
        val cut = match(upper, lower)!!
        assertEquals(380, cut.upperBottom)
        assertEquals(230, cut.lowerTop)
    }

    @Test
    fun aStripOfOneFlatColourIsNotTrusted() {
        // Rows 370 until 400 of the page are blank, so the upper capture's lowest strip says nothing about
        // where the page is. The next one up, half text and half blank, does.
        val blank = Array(page.size) { y -> if (y in 370 until 400) IntArray(WIDTH) { 0xFFFFFFFF.toInt() } else page[y] }
        val upper = Array(HEIGHT) { blank[it].copyOf() }
        val lower = Array(HEIGHT) { blank[150 + it].copyOf() }
        val cut = match(upper, lower)!!
        assertEquals(360, cut.upperBottom)
        assertEquals(210, cut.lowerTop)
        assertTrue(cut.sure)
    }

    @Test
    fun capturesWithNothingInCommonAreStillCutAtTheBestGuess() {
        val other = TestFrames.page(HEIGHT, seed = 77)
        val cut = match(plain(0), other)
        assertNotNull(cut)
        assertFalse(cut!!.sure)
        assertTrue(cut.upperBottom in 100..380)
        assertTrue(cut.lowerTop in 0..280)
    }

    @Test
    fun aRepeatedCaptureCollapsesIntoOne() {
        // The same capture twice: the first strip that lies inside the searched rows is found where it is,
        // so the two crops together are one capture.
        val cut = match(plain(0), plain(0))!!
        assertEquals(cut.upperBottom, cut.lowerTop)
        assertEquals(280, cut.upperBottom)
    }

    @Test
    fun theStripNeverGoesAboveWhatTheUpperCaptureAlreadyLost() {
        // The upper capture was already cut at its row 250 by the seam above, so strips start at row 270 or lower.
        val cut = match(plain(0), TestFrames.page(HEIGHT, seed = 77), upperTop = 250)!!
        assertTrue(cut.upperBottom >= 270)
    }

    @Test
    fun aSmallShiftIsFound() {
        val cut = match(plain(0), plain(30))!!
        // Rows 380 until 400 of the upper capture are outside the searched rows of the lower one (350 until 370),
        // so the strip moves up until it fits: rows 300 until 320 are found at 270.
        assertEquals(30, cut.upperBottom - cut.lowerTop)
        assertTrue(cut.sure)
    }

    @Test
    fun capturesTooShortForAStripAreLeftAlone() {
        val tiny = Array(10) { IntArray(WIDTH) { 0xFF336699.toInt() } }
        assertNull(StripMatcher.match(pixels(tiny), pixels(tiny)))
    }

    @Test
    fun everyCaptureIsJoinedToTheNext() {
        val frames = listOf(plain(0), plain(150), plain(330))
        val cuts = StripMatcher.matchAll(frames.size) { pixels(frames[it]) }
        assertEquals(listOf(380, 380), cuts.map { it!!.upperBottom })
        assertEquals(listOf(230, 200), cuts.map { it!!.lowerTop })
    }
}
