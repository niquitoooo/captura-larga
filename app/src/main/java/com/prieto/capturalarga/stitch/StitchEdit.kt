package com.prieto.capturalarga.stitch

/** Rows [top, bottom) of one capture that go into the result; empty when the capture is left out. */
data class Crop(val top: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

/** Rows [top, bottom) of the original capture number [frameIndex]. */
data class Segment(val frameIndex: Int, val top: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

/**
 * The state of the stitch editor: every capture stacked in order, each one
 * trimmed to its [Crop]. The result is simply the crops one under the other.
 *
 * Seam i lies between capture i and capture i + 1. Cutting at a seam trims the
 * two captures next to it, and each seam remembers its own cuts so they can be
 * undone one at a time.
 */
class StitchEdit private constructor(
    val frameHeight: Int,
    val crops: List<Crop>,
    private val history: List<List<Step>>,
) {
    /** One cut made at a seam: what its two captures kept before and after. */
    private data class Step(
        val upperBefore: Crop,
        val upperAfter: Crop,
        val lowerBefore: Crop,
        val lowerAfter: Crop,
    )

    val seamCount: Int get() = crops.size - 1

    fun canUndo(seam: Int): Boolean = history[seam].isNotEmpty()

    /**
     * Cuts at [seam]: the capture above keeps [upper] and the one below keeps
     * [lower]. A cut only trims: each range is brought inside what the capture
     * shows now, and always keeps at least one row.
     */
    fun cut(seam: Int, upper: Crop, lower: Crop): StitchEdit =
        changed(seam, within(crops[seam], upper), within(crops[seam + 1], lower))

    /** Takes back the last cut made at [seam], leaving alone whatever other seams cut since. */
    fun undo(seam: Int): StitchEdit {
        val step = history[seam].lastOrNull() ?: return this
        val newCrops = crops.toMutableList()
        newCrops[seam] = restored(crops[seam], step.upperBefore, step.upperAfter)
        newCrops[seam + 1] = restored(crops[seam + 1], step.lowerBefore, step.lowerAfter)
        val newHistory = history.toMutableList()
        newHistory[seam] = history[seam].dropLast(1)
        return StitchEdit(frameHeight, newCrops, newHistory)
    }

    /** Every capture whole again, with nothing left to undo. */
    fun reset(): StitchEdit = whole(frameHeight, crops.size)

    /**
     * Starts over from whole captures and cuts every seam where the automatic
     * stitch found it joins: [cuts] has one entry per seam, null for a seam it
     * left alone. Each one can be undone like a cut made by hand.
     */
    fun withAuto(cuts: List<StripMatcher.Cut?>): StitchEdit {
        require(cuts.size == seamCount) { "expected $seamCount cuts, got ${cuts.size}" }
        var edit = reset()
        for ((seam, cut) in cuts.withIndex()) {
            if (cut == null) continue
            edit = edit.cut(
                seam,
                upper = Crop(edit.crops[seam].top, cut.upperBottom),
                lower = Crop(cut.lowerTop, edit.crops[seam + 1].bottom),
            )
        }
        return edit
    }

    /** The rows that make up the result, top to bottom; captures cut to nothing are skipped. */
    fun segments(): List<Segment> =
        crops.mapIndexedNotNull { i, crop -> if (crop.height > 0) Segment(i, crop.top, crop.bottom) else null }

    val totalHeight: Int get() = crops.sumOf { it.height }

    private fun changed(seam: Int, upper: Crop, lower: Crop): StitchEdit {
        val step = Step(crops[seam], upper, crops[seam + 1], lower)
        if (step.upperBefore == upper && step.lowerBefore == lower) return this
        val newCrops = crops.toMutableList()
        newCrops[seam] = upper
        newCrops[seam + 1] = lower
        val newHistory = history.toMutableList()
        newHistory[seam] = history[seam] + step
        return StitchEdit(frameHeight, newCrops, newHistory)
    }

    companion object {
        fun whole(frameHeight: Int, count: Int): StitchEdit {
            require(frameHeight > 0 && count > 0) { "nothing to edit" }
            return StitchEdit(frameHeight, List(count) { Crop(0, frameHeight) }, List(count - 1) { emptyList() })
        }

        /**
         * An edit with the given crops and nothing to undo, or null when they do
         * not describe captures of [frameHeight] rows.
         */
        fun restore(frameHeight: Int, tops: IntArray, bottoms: IntArray): StitchEdit? {
            if (frameHeight <= 0 || tops.isEmpty() || tops.size != bottoms.size) return null
            val crops = tops.indices.map { Crop(tops[it], bottoms[it]) }
            if (crops.any { it.top < 0 || it.bottom > frameHeight || it.top > it.bottom }) return null
            return StitchEdit(frameHeight, crops, List(crops.size - 1) { emptyList() })
        }

        /** [wanted] brought inside [current], keeping at least one row; an empty capture stays empty. */
        private fun within(current: Crop, wanted: Crop): Crop {
            if (current.height <= 0) return current
            val top = wanted.top.coerceIn(current.top, current.bottom - 1)
            return Crop(top, wanted.bottom.coerceIn(top + 1, current.bottom))
        }

        /** Undoes one step on [current]: an edge goes back only if it is still where that step left it. */
        private fun restored(current: Crop, before: Crop, after: Crop): Crop = Crop(
            if (current.top == after.top) before.top else current.top,
            if (current.bottom == after.bottom) before.bottom else current.bottom,
        )
    }
}
