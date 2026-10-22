package com.prieto.capturalarga.frame

/** Detects a capture whose content is one flat colour, as apps that block screenshots produce. */
object SolidColor {
    private const val GRID = 13

    /** Rows above and below this share of the height are skipped: the system bars are drawn even over a blocked app. */
    private const val EDGE_PERCENT = 15

    /** Samples a grid over the middle of the capture, always including the exact centre. */
    fun isSingleColor(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int): Boolean {
        val top = height * EDGE_PERCENT / 100
        val bottom = height - 1 - top
        val first = pixel(0, top)
        for (gy in 0 until GRID) {
            val y = top + (bottom - top) * gy / (GRID - 1)
            for (gx in 0 until GRID) {
                val x = (width - 1) * gx / (GRID - 1)
                if (pixel(x, y) != first) return false
            }
        }
        return pixel(width / 2, height / 2) == first
    }
}
