package me.vattitude.scribe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import me.vattitude.scribe.asr.ModelManager
import me.vattitude.scribe.store.AudioRetention

class ScribeApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Reclaim superseded model downloads off the main thread, and collect
        // the audio of meetings whose retention window has closed. Both touch
        // the filesystem, so neither belongs on the main thread; both are safe
        // to repeat and safe to miss, so a failure is swallowed rather than
        // retried. See AudioRetention for why the sweep runs here rather than
        // on a schedule.
        Thread {
            runCatching { ModelManager.pruneOldModels(this) }
            runCatching { AudioRetention.sweep(this) }
        }.start()
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
            // HIGH, unlike the other two, and deliberately.
            //
            // The recording notification is silent because it says nothing that
            // needs answering. This one does: ignore it and the recording
            // pauses. A silent prompt with a deadline is a trap, so this is the
            // one notification in the app allowed to make noise.
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CHECK_IN,
                    getString(R.string.channel_check_in),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Asks whether a long recording should keep going"
                    setShowBadge(true)
                    enableVibration(true)
                }
            )
        }
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_TRANSCRIBE = "transcribe"
        const val CHANNEL_CHECK_IN = "check_in"
    }
}
