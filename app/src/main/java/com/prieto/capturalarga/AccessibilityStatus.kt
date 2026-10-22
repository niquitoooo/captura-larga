package com.prieto.capturalarga

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object AccessibilityStatus {
    /** True when the user has switched the capture service on in the accessibility settings. */
    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val mine = ComponentName(context, CaptureAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == mine }
    }
}
