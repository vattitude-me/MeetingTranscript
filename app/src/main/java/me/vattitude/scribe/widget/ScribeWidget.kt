package me.vattitude.scribe.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import me.vattitude.scribe.R
import me.vattitude.scribe.capture.RecordingState

/**
 * The 1x1 home-screen entry point. One tap starts a meeting; one tap stops it.
 *
 * The tap goes to [RecordTrampolineActivity] rather than straight to the service
 * on purpose — see the note in AndroidManifest.xml.
 */
class ScribeWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
    }

    companion object {
        private fun buildViews(context: Context): RemoteViews {
            val recording = RecordingState.isRecording
            val views = RemoteViews(context.packageName, R.layout.widget_scribe)

            views.setImageViewResource(
                R.id.widget_icon,
                if (recording) R.drawable.ic_stop else R.drawable.ic_mic
            )
            views.setTextViewText(
                R.id.widget_label,
                context.getString(if (recording) R.string.stop_recording else R.string.start_recording)
            )
            views.setInt(
                R.id.widget_root, "setBackgroundResource",
                if (recording) R.drawable.widget_background_active else R.drawable.widget_background
            )
            // Colours are set here, not left to the icons' theme tint: a widget
            // is drawn in the launcher's theme, which made the icon and label
            // near-invisible on the dark tile. Idle is the app's cyan on dark;
            // recording is the mic's magenta with its matching dark ink.
            val ink = ContextCompat.getColor(
                context, if (recording) R.color.s_on_mic else R.color.s_cyan
            )
            views.setInt(R.id.widget_icon, "setColorFilter", ink)
            views.setTextColor(
                R.id.widget_label,
                ContextCompat.getColor(context, if (recording) R.color.s_on_mic else R.color.s_text)
            )
            views.setContentDescription(
                R.id.widget_root,
                context.getString(if (recording) R.string.stop_recording else R.string.start_recording)
            )

            val intent = Intent(context, RecordTrampolineActivity::class.java)
                .setAction(RecordTrampolineActivity.ACTION_TOGGLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            return views
        }

        /** Repaint every placed widget — called whenever recording starts or stops. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ScribeWidget::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
