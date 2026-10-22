package com.prieto.capturalarga

import android.os.SystemClock
import android.util.Log

/**
 * Stage timings of "tile tapped → pill on screen", written to logcat under the
 * tag CapturaLargaTiming so the slow stage can be read off a real device.
 */
object Timing {
    private const val TAG = "CapturaLargaTiming"
    private var start = 0L
    private var last = 0L

    fun begin(stage: String) {
        start = SystemClock.uptimeMillis()
        last = start
        Log.i(TAG, "begin at uptime $start: $stage")
    }

    fun mark(stage: String) {
        val now = SystemClock.uptimeMillis()
        Log.i(TAG, "+${now - last} ms (total ${now - start} ms, uptime $now): $stage")
        last = now
    }
}
