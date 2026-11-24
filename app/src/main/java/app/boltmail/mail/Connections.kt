package app.boltmail.mail

import android.content.Context
import android.util.Log
import app.boltmail.data.Account
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.mail.Folder
import kotlin.concurrent.withLock

/**
 * One reusable IMAP connection per account for foreground work (refresh, actions, attachments),
 * closed after a short idle period. Push uses its own dedicated connection.
 */
object Connections {
    private class Entry(val store: IMAPStore, val tracker: SocketTracker, @Volatile var lastUsed: Long)

    private const val IDLE_CLOSE_MS = 90_000L
    private val entries = ConcurrentHashMap<Long, Entry>()
    private val locks = ConcurrentHashMap<Long, ReentrantLock>()
    private val reaper = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "imap-reaper").apply { isDaemon = true } }

    init {
        reaper.scheduleWithFixedDelay({
            val now = System.currentTimeMillis()
            for ((id, e) in entries) {
                val lock = locks[id] ?: continue
                if (now - e.lastUsed > IDLE_CLOSE_MS && lock.tryLock()) {
                    try {
                        if (entries[id] === e) entries.remove(id)?.let(::close)
                    } finally {
                        lock.unlock()
                    }
                }
            }
        }, 30, 30, TimeUnit.SECONDS)
    }

    /** Runs [block] with a connected store; retries once on a fresh connection if the old one died. Blocking. */
    fun <T> withStore(context: Context, account: Account, block: (IMAPStore) -> T): T {
        val lock = locks.getOrPut(account.id) { ReentrantLock() }
        return lock.withLock {
            var retried = false
            while (true) {
                val e = entries[account.id]?.takeIf { runCatching { it.store.isConnected }.getOrDefault(false) }
                    ?: open(context, account)
                try {
                    val r = block(e.store)
                    e.lastUsed = System.currentTimeMillis()
                    return@withLock r
                } catch (t: Throwable) {
                    if (!retried && MailErrors.isTransient(t) && !MailErrors.isAuth(t)) {
                        Log.i("BoltMail", "retrying on a fresh connection: ${t.message}")
                        drop(account.id)
                        retried = true
                        continue
                    }
                    throw t
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        }
    }

    private fun open(context: Context, account: Account): Entry {
        entries.remove(account.id)?.let(::close)
        val tracker = SocketTracker()
        val store = MailSession.connectStore(context, account, tracker)
        return Entry(store, tracker, System.currentTimeMillis()).also { entries[account.id] = it }
    }

    fun drop(accountId: Long) {
        entries.remove(accountId)?.let(::close)
    }

    private fun close(e: Entry) {
        Thread {
            runCatching { e.store.close() }
            e.tracker.closeAll()
        }.start()
    }
}

/** Opens [path] for the duration of [block]. */
inline fun <T> IMAPStore.withFolder(path: String, mode: Int = Folder.READ_ONLY, block: (IMAPFolder) -> T): T {
    val f = getFolder(path) as IMAPFolder
    f.open(mode)
    try {
        return block(f)
    } finally {
        runCatching { if (f.isOpen) f.close(false) }
    }
}
