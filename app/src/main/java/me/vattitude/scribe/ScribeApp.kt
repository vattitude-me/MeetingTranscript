package me.vattitude.scribe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import me.vattitude.scribe.asr.ModelManager

class ScribeApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Reclaim superseded model downloads off the main thread.
        Thread { runCatching { ModelManager.pruneOldModels(this) } }.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RECORDING,
                    getString(R.string.channel_recording),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows while a meeting is being recorded"
                    setShowBadge(false)
                    enableVibration(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TRANSCRIBE,
                    getString(R.string.channel_transcribe),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Progress while a recording is turned into text"
                    setShowBadge(false)
                    enableVibration(false)
                }
            )
        }
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_TRANSCRIBE = "transcribe"
    }
}
