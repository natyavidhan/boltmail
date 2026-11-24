package dev.natya.boltmail.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.natya.boltmail.Graph
import dev.natya.boltmail.data.Account
import dev.natya.boltmail.data.ERR_AUTH
import dev.natya.boltmail.mail.OpRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Always-on foreground service holding one IMAP IDLE connection per account, so the server can
 * tell the phone about new mail the moment it arrives.
 */
class PushService : Service() {
    companion object {
        private const val TAG = "BoltMail"
        const val ACTION_KEEPALIVE = "dev.natya.boltmail.push.KEEPALIVE"

        @Volatile
        var instance: PushService? = null
            private set

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, PushService::class.java))
            } catch (e: Exception) {
                // Android may refuse a start from the background; the next app open or alarm retries.
                Log.w(TAG, "push service not started: ${e.message}")
            }
        }

        /** Starts the service if any account wants notifications. Blocking (reads the database). */
        fun startIfWanted(ctx: Context) {
            if (Graph.dao.accounts().any { it.notify }) start(ctx)
        }
    }

    private val workers = ConcurrentHashMap<Long, IdleWorker>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var network: Network? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            val previous = network
            network = n
            if (previous != null && previous != n) workers.values.forEach { it.reconnect() } else workers.values.forEach { it.wake() }
            updateNotification()
        }

        override fun onLost(n: Network) {
            if (network == n) network = null
            updateNotification()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        goForeground("Connecting…")
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback) }
        scope.launch {
            Graph.dao.accountsFlow()
                .map { list -> list.filter { it.notify && it.errorKind != ERR_AUTH } }
                .distinctUntilChanged()
                .collect { reconcile(it) }
        }
        Keepalive.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(statusText())
        if (intent?.action == ACTION_KEEPALIVE) keepalive()
        return START_STICKY
    }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.ID_PUSH, Notifier.pushNotification(this, text), type)
    }

    private fun reconcile(wanted: List<Account>) {
        val byId = wanted.associateBy { it.id }
        for ((id, w) in workers) {
            val a = byId[id]
            if (a == null || connectionKey(a) != connectionKey(w.account)) {
                w.shutdown()
                workers.remove(id)
            }
        }
        for (a in wanted) {
            if (!workers.containsKey(a.id)) workers[a.id] = IdleWorker(applicationContext, a) { updateNotification() }.also { it.start() }
        }
        if (wanted.isEmpty()) {
            stopSelf()
            return
        }
        updateNotification()
    }

    private fun connectionKey(a: Account) =
        listOf(a.imapHost, a.imapPort, a.imapSecurity, a.username, a.secret, a.auth, a.email).joinToString("|")

    private fun statusText(): String {
        if (network == null && workers.isNotEmpty()) return "Waiting for a connection"
        val names = workers.values.map { it.account.label }
        return when {
            names.isEmpty() -> "Connecting…"
            workers.values.any { !it.connected } -> "Connecting to ${names.joinToString(", ")}"
            names.size == 1 -> "Watching ${names[0]}"
            else -> "Watching ${names.dropLast(1).joinToString(", ")} and ${names.last()}"
        }
    }

    private fun updateNotification() {
        if (instance == null) return
        runCatching {
            NotificationManagerCompat.from(this).notify(Notifier.ID_PUSH, Notifier.pushNotification(this, statusText()))
        }
    }

    /** Called by the keep-alive alarm: ping every connection, replay queued ops, schedule the next alarm. */
    fun keepalive() {
        Keepalive.schedule(this)
        val wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BoltMail:keepalive")
            .apply { setReferenceCounted(false); acquire(60_000) }
        thread(name = "keepalive") {
            try {
                workers.values.map { w -> thread { w.ping() } }.forEach { it.join(40_000) }
                OpRunner.runDueBlocking()
            } finally {
                wl.release()
            }
        }
    }

    override fun onDestroy() {
        instance = null
        workers.values.forEach { it.shutdown() }
        workers.clear()
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        Keepalive.cancel(this)
        scope.cancel()
        super.onDestroy()
    }
}
