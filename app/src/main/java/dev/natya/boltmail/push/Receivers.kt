package dev.natya.boltmail.push

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import dev.natya.boltmail.Graph
import dev.natya.boltmail.mail.Mail
import dev.natya.boltmail.mail.OpRunner
import kotlinx.coroutines.launch

/** Wakes the device every few minutes to prove each push connection is still alive. */
object Keepalive {
    const val INTERVAL_MS = 12 * 60_000L

    private fun intent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, KeepaliveReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + INTERVAL_MS
        val pi = intent(ctx)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(intent(ctx))
    }
}

class KeepaliveReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val service = PushService.instance
        if (service != null) {
            service.keepalive()
            return
        }
        val pending = goAsync()
        Graph.scope.launch {
            try {
                PushService.startIfWanted(ctx.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Restarts push after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Graph.scope.launch {
            try {
                PushService.startIfWanted(ctx.applicationContext)
                OpRunner.runDueBlocking()
            } finally {
                pending.finish()
            }
        }
    }
}

/** "Mark read" and "Archive" buttons on new-mail notifications. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(Notifier.EXTRA_MESSAGE, 0)
        if (id == 0L) return
        Notifier.cancel(ctx, id)
        val pending = goAsync()
        Graph.scope.launch {
            try {
                when (intent.action) {
                    Notifier.ACTION_READ -> Mail.setSeen(listOf(id), true)
                    Notifier.ACTION_ARCHIVE -> {
                        Mail.setSeen(listOf(id), true)
                        Mail.archive(id, delayMs = 0)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
