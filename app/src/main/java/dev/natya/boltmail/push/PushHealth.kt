package dev.natya.boltmail.push

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** The two things Android needs from the user before push can be trusted. */
object PushHealth {
    data class State(val notifications: Boolean, val unrestricted: Boolean) {
        val ok get() = notifications && unrestricted
    }

    fun check(ctx: Context) = State(
        notifications = Notifier.canPost(ctx),
        unrestricted = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
    )

    /** Opens the system prompt that lets BoltMail run in the background. Fine for a sideloaded app. */
    @SuppressLint("BatteryLife")
    fun unrestrictIntent(ctx: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

    fun notificationSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

    fun needsRuntimePermission() = Build.VERSION.SDK_INT >= 33
}
