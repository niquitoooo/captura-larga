package com.prieto.capturalarga.stitch

import com.prieto.capturalarga.frame.FramePixels
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds where two captures taken one after the other join, following the rule
 * of Screen Master's automatic stitch.
 *
 * A strip is taken from the bottom of the upper capture, as wide as the capture
 * and a twentieth of its height, and looked for in the upper three quarters of
 * the lower capture. If it is there, the upper capture is cut at the top of the
 * strip and the lower one starts where the strip was found. If it is not, or
 * the strip is one flat colour, the strip just above it is tried, and so on up
 * to a quarter of the upper capture. When no strip is found for sure, the best
 * guess is used all the same: the captures are always cut.
 *
 * "Is there" is OpenCV's TM_CCOEFF_NORMED template match, worked out here the
 * same way: how alike the strip and each place are once the brightness of each
 * is taken away, from -1 to 1.
 */
object StripMatcher {
    /** The strip's height, as a share of the capture's. */
    private const val STRIP_SHARE = 0.05

    /** The share of the lower capture, from its top, where the strip is looked for. */
    private const val SEARCH_SHARE = 0.75

    /** The strip never starts above this share of the upper capture. */
    private const val KEPT_SHARE = 0.25

    /** A strip found at least this alike settles the cut. */
    private const val SURE = 0.98

    /** A strip whose red varies less than this is one flat colour; what it scores counts for no more than [FLAT_SCORE]. */
    private const val FLAT_DEVIATION = 10.0
    private const val FLAT_SCORE = 0.95

    private const val CHANNELS = 3
    private const val BYTES_PER_PIXEL = 4

    /**
     * Where a seam is cut: the capture above keeps its rows up to [upperBottom]
     * and the one below starts at its row [lowerTop]. [score] is how alike the
     * strip and the place it was found are.
     */
    data class Cut(val upperBottom: Int, val lowerTop: Int, val score: Double) {
        /** False when no strip was found for sure and this is only the best guess. */
        val sure: Boolean get() = score >= SURE
    }

    /**
     * The cut of every seam of [count] captures, top to bottom, each capture
     * loaded with [load] when its turn comes. A capture is never cut above
     * where the seam over it made it start.
     */
    fun matchAll(count: Int, load: (index: Int) -> FramePixels): List<Cut?> {
        val cuts = ArrayList<Cut?>()
        if (count < 2) return cuts
        var upper = load(0)
        var upperTop = 0
        for (index in 1 until count) {
            val lower = load(index)
            val cut = match(upper, lower, upperTop)
            cuts += cut
            upperTop = cut?.lowerTop ?: 0
            upper = lower
        }
        return cuts
    }

    /**
     * The cut between [upper] and [lower], two whole captures of the same size;
     * [upperTop] is the row [upper] already starts at. Null when the captures
     * are too short to take a strip from.
     */
    fun match(upper: FramePixels, lower: FramePixels, upperTop: Int = 0): Cut? {
        require(upper.width == lower.width && upper.height == lower.height) { "captures of different sizes" }
        val height = upper.height
        val strip = (height * STRIP_SHARE).toInt()
        val searchRows = (height * SEARCH_SHARE).toInt()
        if (strip < 1 || searchRows < strip) return null
        val highest = max(upperTop + strip, (height * KEPT_SHARE).toInt())

        val search = SearchRows(lower, searchRows)
        var best: Cut? = null
        var top = height - strip
        while (top >= highest) {
            val template = Template(upper, top, strip)
            val found = search.find(template)
            val flat = template.redDeviation < FLAT_DEVIATION
            if (!flat && found.score >= SURE) return Cut(top, found.row, found.score)
            val score = if (flat) min(found.score, FLAT_SCORE) else found.score
            if (score > (best?.score ?: -1.0)) best = Cut(top, found.row, score)
            top -= strip
        }
        return best
    }

    private class Found(val row: Int, val score: Double)

    /** A strip of a capture and the sums the match needs from it. */
    private class Template(val pixels: FramePixels, val top: Int, val rows: Int) {
        val area: Double = rows.toDouble() * pixels.width

        /** Per colour, the mean of the strip. */
        val mean = DoubleArray(CHANNELS)

        /** The strip's spread around its means, all colours together; zero for a strip of one colour. */
        val norm: Double

        /** How much the red of the strip varies (its standard deviation). */
        val redDeviation: Double

        init {
            val sum = LongArray(CHANNELS)
            val squares = LongArray(CHANNELS)
            val bytes = pixels.bytes
            var i = pixels.rowStart(top)
            repeat(rows * pixels.width) {
                for (c in 0 until CHANNELS) {
                    val value = bytes[i + c].toInt() and 0xFF
                    sum[c] += value.toLong()
                    squares[c] += (value * value).toLong()
                }
                i += BYTES_PER_PIXEL
            }
            var spread = 0.0
            for (c in 0 until CHANNELS) {
                mean[c] = sum[c] / area
                spread += squares[c] - sum[c] * mean[c]
            }
            norm = sqrt(max(spread, 0.0))
            redDeviation = sqrt(max(squares[0] / area - mean[0] * mean[0], 0.0))
        }
    }

    /** The rows of a capture a strip is looked for in, with their sums ready for any window of rows. */
    private class SearchRows(val pixels: FramePixels, val rows: Int) {
        /** Per colour, the sum of everything above each row; and the same for the squares, all colours together. */
        private val sumAbove = Array(CHANNELS) { LongArray(rows + 1) }
        private val squaresAbove = LongArray(rows + 1)

        init {
            val bytes = pixels.bytes
            for (y in 0 until rows) {
                var i = pixels.rowStart(y)
                val sum = LongArray(CHANNELS)
                var squares = 0L
                repeat(pixels.width) {
                    for (c in 0 until CHANNELS) {
                        val value = bytes[i + c].toInt() and 0xFF
                        sum[c] += value.toLong()
                        squares += (value * value).toLong()
                    }
                    i += BYTES_PER_PIXEL
                }
                for (c in 0 until CHANNELS) sumAbove[c][y + 1] = sumAbove[c][y] + sum[c]
                squaresAbove[y + 1] = squaresAbove[y] + squares
            }
        }

        /** The place where [template] is most alike these rows: the highest one, if several are equally so. */
        fun find(template: Template): Found {
            // A strip of one colour is as alike one place as any other: OpenCV calls that a full score, everywhere.
            if (template.norm * template.norm / template.area < Math.ulp(1.0)) return Found(0, 1.0)

            val places = rows - template.rows + 1
            val products = LongArray(places)
            IntStream.range(0, places).parallel().forEach { y -> products[y] = product(template, y) }

            var bestRow = 0
            var bestScore = Double.NEGATIVE_INFINITY
            for (y in 0 until places) {
                var alike = products[y].toDouble()
                var windowMeans = 0.0
                for (c in 0 until CHANNELS) {
                    val sum = (sumAbove[c][y + template.rows] - sumAbove[c][y]).toDouble()
                    alike -= sum * template.mean[c]
                    windowMeans += sum * sum
                }
                val squares = (squaresAbove[y + template.rows] - squaresAbove[y]).toDouble()
                val scale = sqrt(max(squares - windowMeans / template.area, 0.0)) * template.norm
                val score = when {
                    abs(alike) < scale -> alike / scale
                    abs(alike) < scale * 1.125 -> if (alike > 0) 1.0 else -1.0
                    else -> 0.0
                }
                if (score > bestScore) {
                    bestScore = score
                    bestRow = y
                }
            }
            return Found(bestRow, bestScore)
        }

        /** The strip times the rows from [y] on, pixel by pixel and colour by colour, added up. */
        private fun product(template: Template, y: Int): Long {
            val a = template.pixels.bytes
            val b = pixels.bytes
            val width = pixels.width
            var total = 0L
            for (row in 0 until template.rows) {
                var i = template.pixels.rowStart(template.top + row)
                var j = pixels.rowStart(y + row)
                var line = 0L
                repeat(width) {
                    line += (a[i].toInt() and 0xFF) * (b[j].toInt() and 0xFF) +
                        (a[i + 1].toInt() and 0xFF) * (b[j + 1].toInt() and 0xFF) +
                        (a[i + 2].toInt() and 0xFF) * (b[j + 2].toInt() and 0xFF)
                    i += BYTES_PER_PIXEL
                    j += BYTES_PER_PIXEL
                }
                total += line
            }
            return total
        }
    }
}
