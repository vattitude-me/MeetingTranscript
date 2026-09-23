package me.vattitude.scribe

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Posts or updates a notification when the user has allowed them. Refusing
 * notifications is a choice, not a fault: recording and downloads carry on,
 * and the screen shows the same state.
 */
object Notify {
    fun post(ctx: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }
}
