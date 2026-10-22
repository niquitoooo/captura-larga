package com.prieto.capturalarga

import android.app.Activity
import android.os.Bundle

/**
 * What a tap on the tile opens. It shows nothing: it does what the tap asks and
 * closes at once.
 *
 * The system keeps only a few apps' tiles connected at a time, and a tap on one
 * that is not connected waits, for seconds, until its turn comes. A tile that
 * names a screen to open is not held up that way: the system opens it itself.
 */
class TileClickActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timing.begin("tile tap opened TileClickActivity")
        if (!TileClick.toggle()) startActivity(TileClick.app(this))
        finish()
    }
}
