package com.prieto.capturalarga.stitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StitchEditTest {
    // Three captures of 400 rows.
    private val whole = StitchEdit.whole(frameHeight = 400, count = 3)

    @Test
    fun startsWithEveryCaptureWhole() {
        assertEquals(listOf(Crop(0, 400), Crop(0, 400), Crop(0, 400)), whole.crops)
        assertEquals(listOf(Segment(0, 0, 400), Segment(1, 0, 400), Segment(2, 0, 400)), whole.segments())
        assertEquals(1200, whole.totalHeight)
        assertFalse(whole.canUndo(0))
        assertFalse(whole.canUndo(1))
    }

    @Test
    fun cuttingASeamTrimsTheCaptureAboveAndTheOneBelow() {
        val edit = whole.cut(0, upper = Crop(0, 300), lower = Crop(120, 400))
        assertEquals(listOf(Crop(0, 300), Crop(120, 400), Crop(0, 400)), edit.crops)
        assertEquals(300 + 280 + 400, edit.totalHeight)
        assertTrue(edit.canUndo(0))
        assertFalse(edit.canUndo(1))
    }

    @Test
    fun aCutCanTrimBothEndsOfACapture() {
        val edit = whole.cut(1, upper = Crop(60, 350), lower = Crop(0, 400))
        assertEquals(listOf(Crop(0, 400), Crop(60, 350), Crop(0, 400)), edit.crops)
    }

    @Test
    fun aCutNeverGrowsACaptureBackNorEmptiesIt() {
        val once = whole.cut(0, upper = Crop(0, 300), lower = Crop(120, 400))
        val again = once.cut(0, upper = Crop(-50, 900), lower = Crop(390, 100))
        assertEquals(Crop(0, 300), again.crops[0])
        assertEquals(Crop(390, 391), again.crops[1])
    }

    @Test
    fun aCutThatChangesNothingLeavesNoUndoStep() {
        val same = whole.cut(0, upper = Crop(0, 400), lower = Crop(0, 400))
        assertSame(whole, same)
        assertFalse(same.canUndo(0))
    }

    @Test
    fun undoPutsBackTheLastCutOfThatSeam() {
        val first = whole.cut(0, upper = Crop(0, 300), lower = Crop(120, 400))
        val second = first.cut(0, upper = Crop(0, 250), lower = Crop(150, 400))
        assertEquals(first.crops, second.undo(0).crops)
        assertEquals(whole.crops, second.undo(0).undo(0).crops)
        assertFalse(second.undo(0).undo(0).canUndo(0))
        assertEquals(whole.crops, whole.undo(0).crops)
    }

    @Test
    fun undoKeepsWhatALaterCutAtAnotherSeamChanged() {
        val edit = whole
            .cut(0, upper = Crop(0, 300), lower = Crop(100, 400))
            .cut(1, upper = Crop(100, 350), lower = Crop(50, 400))
            .undo(0)
        // Seam 0 is undone: capture 0 is whole again and capture 1 gets its top back,
        // but the bottom of capture 1 was cut at seam 1 and stays cut.
        assertEquals(listOf(Crop(0, 400), Crop(0, 350), Crop(50, 400)), edit.crops)
        assertTrue(edit.canUndo(1))
    }

    @Test
    fun resetMakesEveryCaptureWholeAgain() {
        val edit = whole.cut(0, upper = Crop(0, 300), lower = Crop(120, 400)).reset()
        assertEquals(whole.crops, edit.crops)
        assertFalse(edit.canUndo(0))
    }

    @Test
    fun autoTurnsEveryCutItFoundIntoACutOfThatSeam() {
        // Seam 0: capture 0 stops at its row 380 and capture 1 takes over at its row 230. Seam 1 was left alone.
        val edit = whole.withAuto(listOf(StripMatcher.Cut(380, 230, 1.0), null))
        assertEquals(listOf(Crop(0, 380), Crop(230, 400), Crop(0, 400)), edit.crops)
        assertTrue(edit.canUndo(0))
        assertFalse(edit.canUndo(1))
        assertEquals(whole.crops, edit.undo(0).crops)
    }

    @Test
    fun autoCutsOfNeighbouringSeamsMeetInTheCaptureBetweenThem() {
        val edit = whole.withAuto(listOf(StripMatcher.Cut(380, 230, 1.0), StripMatcher.Cut(360, 180, 0.99)))
        assertEquals(listOf(Crop(0, 380), Crop(230, 360), Crop(180, 400)), edit.crops)
        assertEquals(380 + 130 + 220, edit.totalHeight)
    }

    @Test
    fun autoStartsOverFromWholeCaptures() {
        val cuts = listOf(StripMatcher.Cut(380, 230, 1.0), null)
        val cutByHand = whole.cut(1, upper = Crop(0, 100), lower = Crop(300, 400))
        assertEquals(whole.withAuto(cuts).crops, cutByHand.withAuto(cuts).crops)
    }

    @Test
    fun savedCropsComeBackAsAnEditWithNothingToUndo() {
        val cut = whole.cut(0, upper = Crop(0, 300), lower = Crop(120, 400))
        val back = StitchEdit.restore(400, cut.crops.map { it.top }.toIntArray(), cut.crops.map { it.bottom }.toIntArray())
        assertEquals(cut.crops, back?.crops)
        assertFalse(back!!.canUndo(0))
    }

    @Test
    fun savedCropsThatDoNotFitAreRefused() {
        assertEquals(null, StitchEdit.restore(400, intArrayOf(0, 0), intArrayOf(400)))
        assertEquals(null, StitchEdit.restore(400, intArrayOf(0, 50), intArrayOf(400, 401)))
        assertEquals(null, StitchEdit.restore(400, intArrayOf(0, 300), intArrayOf(400, 200)))
        assertEquals(null, StitchEdit.restore(400, intArrayOf(), intArrayOf()))
    }

    @Test
    fun aSingleCaptureHasNoSeams() {
        val single = StitchEdit.whole(frameHeight = 400, count = 1)
        assertEquals(0, single.seamCount)
        assertEquals(listOf(Segment(0, 0, 400)), single.segments())
    }
}
