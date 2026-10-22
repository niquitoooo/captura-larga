package com.prieto.capturalarga

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** What a tap on the tile does, whichever way the system delivers it. */
object TileClick {

    /**
     * Starts a session, or cancels the one in progress. False when accessibility
     * is off: then the app has to be opened, which explains how to turn it on.
     */
    fun toggle(): Boolean {
        val service = CaptureAccessibilityService.instance ?: return false
        service.toggleSession()
        return true
    }

    fun app(context: Context): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The screen the system opens by itself on a tap: [TileClickActivity]. */
    fun launch(context: Context): PendingIntent {
        val intent = Intent(context, TileClickActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
