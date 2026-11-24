package app.boltmail.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.boltmail.Graph
import app.boltmail.MainActivity
import app.boltmail.R
import app.boltmail.data.Account
import app.boltmail.data.Folder
import app.boltmail.data.Message
import app.boltmail.data.Op
import app.boltmail.data.Role
import app.boltmail.mail.Syncer

object Notifier {
    const val CH_PUSH = "push"
    private const val CH_ALERTS = "alerts"
    private const val GROUP = "accounts"
    const val ID_PUSH = 1
    private const val ID_SIGN_IN_BASE = 1_000_000
    private const val ID_SEND_BASE = 2_000_000
    private const val ID_SUMMARY_BASE = 3_000_000

    const val ACTION_READ = "app.boltmail.action.READ"
    const val ACTION_ARCHIVE = "app.boltmail.action.ARCHIVE"
    const val EXTRA_MESSAGE = "messageId"
    const val EXTRA_ACCOUNT = "accountId"

    fun channelId(accountId: Long) = "mail_$accountId"

    fun init(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP, "New mail"))
        nm.createNotificationChannel(
            NotificationChannel(CH_PUSH, "Push connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps BoltMail connected so new mail arrives instantly. You can hide this one."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Sign-in and sending problems", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** One channel per account, so each inbox can have its own sound in system settings. */
    fun ensureAccountChannel(ctx: Context, a: Account) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(channelId(a.id), "${a.label} · ${a.email}", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "New mail for ${a.email}"
            group = GROUP
            enableLights(true)
            lightColor = a.color
        }
        nm.createNotificationChannel(ch)
    }

    fun removeAccount(ctx: Context, id: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.notification.group == group(id) }.forEach { nm.cancel(it.id) }
        nm.cancel(ID_SIGN_IN_BASE + id.toInt())
        nm.deleteNotificationChannel(channelId(id))
    }

    private fun group(accountId: Long) = "account_$accountId"

    fun canPost(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun openApp(ctx: Context, requestCode: Int, messageId: Long? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (messageId != null) putExtra(EXTRA_MESSAGE, messageId)
        }
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(ctx: Context, action: String, m: Message): PendingIntent {
        val i = Intent(ctx, NotificationActionReceiver::class.java).setAction(action)
            .putExtra(EXTRA_MESSAGE, m.id).putExtra(EXTRA_ACCOUNT, m.accountId)
        return PendingIntent.getBroadcast(ctx, (m.id * 4 + action.length).toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun pushNotification(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_PUSH)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("Instant notifications are on")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setContentIntent(openApp(ctx, 0))
            .build()

    /** Posts new-mail notifications after a sync and clears ones for mail handled elsewhere. */
    fun onSync(ctx: Context, a: Account, folder: Folder, r: Syncer.Result) {
        r.settled.forEach { cancel(ctx, it) }
        if (r.initial || folder.role != Role.INBOX || r.inserted.isEmpty()) return
        val fresh = r.inserted.filter { !it.seen && !it.notified }
        if (fresh.isEmpty()) return
        fresh.forEach { Graph.dao.markNotified(it.id) }
        val account = Graph.dao.account(a.id) ?: return
        if (!account.notify || Graph.inboxVisible) return
        newMail(ctx, account, fresh.sortedBy { it.date })
    }

    @SuppressLint("MissingPermission")
    private fun newMail(ctx: Context, a: Account, msgs: List<Message>) {
        if (!canPost(ctx)) return
        val nm = NotificationManagerCompat.from(ctx)
        for (m in msgs) {
            val sender = m.fromName.ifBlank { m.fromAddress.ifBlank { "Unknown sender" } }
            val subject = m.subject.ifBlank { "(no subject)" }
            val big = SpannableStringBuilder(subject).apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (m.snippet.isNotBlank()) append('\n').append(m.snippet)
            }
            val n = NotificationCompat.Builder(ctx, channelId(a.id))
                .setSmallIcon(R.drawable.ic_bolt)
                .setColor(a.color)
                .setContentTitle(sender)
                .setContentText(subject)
                .setStyle(NotificationCompat.BigTextStyle().bigText(big))
                .setSubText(a.label)
                .setWhen(m.date)
                .setShowWhen(true)
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setGroup(group(a.id))
                .setSortKey((Long.MAX_VALUE - m.date).toString())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(ctx, m.id.toInt(), m.id))
                .addAction(R.drawable.ic_mark_email_read, "Mark read", action(ctx, ACTION_READ, m))
                .addAction(R.drawable.ic_archive, "Archive", action(ctx, ACTION_ARCHIVE, m))
                .build()
            nm.notify(m.id.toInt(), n)
        }
        val summary = NotificationCompat.Builder(ctx, channelId(a.id))
            .setSmallIcon(R.drawable.ic_bolt)
            .setColor(a.color)
            .setSubText(a.label)
            .setGroup(group(a.id))
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, ID_SUMMARY_BASE + a.id.toInt()))
            .build()
        nm.notify(ID_SUMMARY_BASE + a.id.toInt(), summary)
    }

    fun cancel(ctx: Context, messageId: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val active = nm.activeNotifications
        val target = active.firstOrNull { it.id == messageId.toInt() && it.tag == null } ?: return
        nm.cancel(messageId.toInt())
        val group = target.notification.group ?: return
        val left = active.count { it.notification.group == group && it.id != messageId.toInt() && it.id < ID_SIGN_IN_BASE }
        if (left == 0) active.filter { it.notification.group == group }.forEach { nm.cancel(it.id) }
    }

    @SuppressLint("MissingPermission")
    fun signInProblem(ctx: Context, a: Account) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_bolt)
            .setColor(a.color)
            .setContentTitle("Can't sign in to ${a.label}")
            .setContentText("New mail for ${a.email} won't arrive until you sign in again.")
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, ID_SIGN_IN_BASE + a.id.toInt()))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_SIGN_IN_BASE + a.id.toInt(), n)
    }

    fun cancelSignInProblem(ctx: Context, accountId: Long) {
        NotificationManagerCompat.from(ctx).cancel(ID_SIGN_IN_BASE + accountId.toInt())
    }

    @SuppressLint("MissingPermission")
    fun sendFailed(ctx: Context, a: Account, op: Op, reason: String) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_bolt)
            .setColor(a.color)
            .setContentTitle("Couldn't send “${op.title}”")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, ID_SEND_BASE + op.id.toInt()))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_SEND_BASE + op.id.toInt(), n)
    }
}
