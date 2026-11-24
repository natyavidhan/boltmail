package dev.natya.boltmail.push

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.util.Log
import dev.natya.boltmail.Graph
import dev.natya.boltmail.data.Account
import dev.natya.boltmail.data.ERR_SERVER
import dev.natya.boltmail.data.Role
import dev.natya.boltmail.mail.MailErrors
import dev.natya.boltmail.mail.MailSession
import dev.natya.boltmail.mail.Mail
import dev.natya.boltmail.mail.SocketTracker
import dev.natya.boltmail.mail.Syncer
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import javax.mail.Folder as JFolder

/** One thread per account: connect, sync the inbox, IDLE until the server says something, repeat. */
class IdleWorker(
    private val ctx: Context,
    val account: Account,
    private val onStateChanged: () -> Unit,
) : Thread("idle-${account.id}") {
    @Volatile private var running = true
    @Volatile private var folder: IMAPFolder? = null
    @Volatile private var store: IMAPStore? = null
    @Volatile var connected = false
        private set
    private val tracker = SocketTracker()
    private val lock = Object()
    private val wakeLock = ctx.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BoltMail:sync:${account.id}")
        .apply { setReferenceCounted(false) }

    init {
        isDaemon = true
    }

    override fun run() {
        var backoff = 10_000L
        var failures = 0
        while (running) {
            if (!online()) {
                sleepFor(15 * 60_000L)
                continue
            }
            try {
                session()
                backoff = 10_000L
                failures = 0
            } catch (t: Throwable) {
                if (!running) break
                Log.w(TAG, "push for ${account.email}: ${t.message}")
                if (MailErrors.isAuth(t)) {
                    Mail.recordError(account, t)
                    break // the service stops this worker; it restarts once sign-in is fixed
                }
                if (++failures >= 4) {
                    Graph.dao.setAccountError(account.id, MailErrors.describe(t, account.imapHost), ERR_SERVER)
                }
            } finally {
                setConnected(false)
                close()
            }
            if (running) {
                sleepFor(backoff)
                backoff = (backoff * 2).coerceAtMost(10 * 60_000L)
            }
        }
    }

    private fun session() {
        wakeLock.acquire(120_000)
        val s = MailSession.connectStore(ctx, account, tracker, MailSession.IDLE_READ_TIMEOUT)
        store = s
        Syncer.ensureFolders(account, s)
        val inbox = Graph.dao.folderByRole(account.id, Role.INBOX) ?: throw IllegalStateException("no inbox")
        val f = s.getFolder(inbox.path) as IMAPFolder
        f.open(JFolder.READ_ONLY)
        folder = f
        setConnected(true)
        Mail.clearError(account.id)
        sync(inbox.id, f, full = true)
        Syncer.backfillBodies(inbox.id, f)
        wakeLock.release()
        val canIdle = s.hasCapability("IDLE")
        while (running && f.isOpen) {
            if (canIdle) f.idle(true) else sleepFor(Keepalive.INTERVAL_MS)
            if (!running) break
            wakeLock.acquire(120_000)
            try {
                sync(inbox.id, f, full = false)
            } finally {
                wakeLock.release()
            }
        }
    }

    private fun sync(folderId: Long, f: IMAPFolder, full: Boolean) {
        val inbox = Graph.dao.folder(folderId) ?: return
        val r = Syncer.syncFolder(account, folderId, f, full)
        Notifier.onSync(ctx, account, inbox, r)
    }

    /** Sends NOOP; if the connection doesn't answer in time it's dead, so kill it and reconnect. */
    fun ping() {
        val f = folder
        if (f == null || !connected) {
            wake()
            return
        }
        val done = CountDownLatch(1)
        var ok = false
        thread(name = "ping-${account.id}") {
            try {
                f.doCommand { p -> p.simpleCommand("NOOP", null); null }
                ok = true
            } catch (_: Throwable) {
            } finally {
                done.countDown()
            }
        }
        if (!done.await(25, TimeUnit.SECONDS) || !ok) {
            Log.i(TAG, "push for ${account.email}: connection dead, reconnecting")
            tracker.closeAll()
        }
    }

    /** The network changed; sockets bound to the old one are useless. */
    fun reconnect() {
        tracker.closeAll()
        wake()
    }

    fun wake() = synchronized(lock) { lock.notifyAll() }

    fun shutdown() {
        running = false
        tracker.closeAll()
        wake()
    }

    private fun sleepFor(ms: Long) = synchronized(lock) {
        if (running) lock.wait(ms)
    }

    private fun setConnected(v: Boolean) {
        if (connected != v) {
            connected = v
            onStateChanged()
        }
    }

    private fun close() {
        val f = folder
        val s = store
        folder = null
        store = null
        if (wakeLock.isHeld) wakeLock.release()
        thread {
            runCatching { if (f?.isOpen == true) f.close(false) }
            runCatching { s?.close() }
            tracker.closeAll()
        }
    }

    private fun online(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val TAG = "BoltMail"
    }
}
