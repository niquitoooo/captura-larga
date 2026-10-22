package com.prieto.capturalarga

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The Quick Settings tile: starts a capture session, or cancels the one in progress. */
class CaptureTileService : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        val active = CaptureAccessibilityService.instance?.isSessionActive == true
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        // From here on the system answers a tap by itself, without waiting to be connected to this service.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            tile.activityLaunchForClick = TileClick.launch(this)
        }
        tile.updateTile()
    }

    /** Only reached while the tile has no screen to open yet: before Android 14, or before its first update. */
    override fun onClick() {
        Timing.begin("tile onClick, locked=$isLocked")
        if (isLocked) unlockAndRun(::act) else act()
    }

    private fun act() {
        if (TileClick.toggle()) {
            onStartListening()
            return
        }
        val app = TileClick.app(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, app, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(app)
        }
    }
}
