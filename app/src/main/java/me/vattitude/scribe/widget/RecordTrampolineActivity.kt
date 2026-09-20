package me.vattitude.scribe.widget

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import me.vattitude.scribe.capture.RecorderService
import me.vattitude.scribe.capture.RecordingState
import me.vattitude.scribe.ui.MainActivity

/**
 * Invisible, finishes immediately.
 *
 * Its only job is to put the app in a user-visible state for the instant it
 * takes to start the microphone foreground service. Android 14+ throws
 * SecurityException if such a service is started while the app is in the
 * background, and "transitioned from a user-visible state" is the exemption
 * that a widget tap can reliably satisfy.
 */
class RecordTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        if (!granted) {
            // Permissions can't be requested from here without showing UI, so hand
            // off to the main screen, which knows how to ask.
            Toast.makeText(this, "Grant microphone access to record", Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_REQUEST_PERMISSION, true)
            )
        } else {
            if (RecordingState.isRecording) RecorderService.stop(this)
            else RecorderService.start(this)
        }

        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val ACTION_TOGGLE = "me.vattitude.scribe.TOGGLE"
    }
}
