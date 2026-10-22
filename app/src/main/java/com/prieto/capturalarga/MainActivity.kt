package com.prieto.capturalarga

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.prieto.capturalarga.session.CaptureSession
import java.io.File

/** The launcher screen: shows whether the service is on and how to set the app up. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var resumeSession: Button
    private lateinit var restrictedHelp: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.main_status)
        resumeSession = findViewById(R.id.main_resume_session)
        restrictedHelp = findViewById(R.id.main_restricted_help)

        resumeSession.setOnClickListener {
            // Checked again: a session may have started from the tile while this screen stayed up.
            if (refreshResumeSession()) {
                startActivity(
                    Intent(this, PreviewActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
        }
        findViewById<View>(R.id.main_open_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<View>(R.id.main_add_tile).setOnClickListener {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, CaptureTileService::class.java),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_tile),
                mainExecutor,
            ) { }
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = AccessibilityStatus.isEnabled(this)
        status.setText(if (enabled) R.string.main_status_on else R.string.main_status_off)
        restrictedHelp.visibility = if (enabled) View.GONE else View.VISIBLE
        refreshResumeSession()
    }

    /**
     * Captures left behind by a preview that was closed without saving can still be opened, until
     * the next session replaces them; while a session is running they belong to it. Shows or hides
     * the button accordingly and returns whether there is a session to open.
     */
    private fun refreshResumeSession(): Boolean {
        val captures = CaptureSession(File(cacheDir, CaptureSession.DIR_NAME)).count
        val capturing = CaptureAccessibilityService.instance?.isSessionActive == true
        val canOpen = captures > 0 && !capturing
        if (canOpen) resumeSession.text = getString(R.string.main_resume_session, captures)
        resumeSession.visibility = if (canOpen) View.VISIBLE else View.GONE
        return canOpen
    }
}
