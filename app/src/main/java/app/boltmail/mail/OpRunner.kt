package app.boltmail.mail

import android.util.Log
import app.boltmail.Graph
import app.boltmail.data.Account
import app.boltmail.data.ERR_AUTH
import app.boltmail.data.Folder
import app.boltmail.data.Op
import app.boltmail.data.OpKind
import app.boltmail.data.Role
import app.boltmail.push.Notifier
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.mail.Flags
import javax.mail.MessagingException
import javax.mail.internet.MimeMessage
import javax.mail.Folder as JFolder

/** Replays queued operations against the servers, with backoff when offline. */
object OpRunner {
    private const val TAG = "BoltMail"
    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    @Volatile private var timer: Job? = null
    private val dao get() = Graph.dao
    private val ctx get() = Graph.app

    fun kick(delayMs: Long = 0) {
        if (delayMs > 0) {
            Graph.scope.launch { delay(delayMs + 250); kick() }
            return
        }
        if (!running.compareAndSet(false, true)) {
            again.set(true)
            return
        }
        Graph.scope.launch {
            try {
                do {
                    again.set(false)
                    runDue()
                } while (again.get())
            } finally {
                running.set(false)
            }
            scheduleNext()
        }
    }

    /** For broadcast receivers and the keep-alive alarm, which already run on a worker thread. */
    fun runDueBlocking() {
        if (!running.compareAndSet(false, true)) return
        try {
            runDue()
        } finally {
            running.set(false)
        }
    }

    private fun scheduleNext() {
        val next = dao.nextOpTime() ?: return
        val wait = (next - System.currentTimeMillis()).coerceAtLeast(1_000)
        timer?.cancel()
        timer = Graph.scope.launch { delay(wait); kick() }
    }

    private fun runDue() {
        val ops = dao.dueOps(System.currentTimeMillis())
        if (ops.isEmpty()) return
        for ((accountId, list) in ops.groupBy { it.accountId }) {
            val a = dao.account(accountId) ?: continue
            if (a.errorKind == ERR_AUTH) continue
            val (sends, others) = list.partition { it.kind == OpKind.SEND }
            try {
                if (others.isNotEmpty()) Connections.withStore(ctx, a) { store -> runImap(a, store, others) }
            } catch (t: Throwable) {
                Log.w(TAG, "ops for ${a.email} failed", t)
                if (MailErrors.isAuth(t)) Mail.recordError(a, t)
                others.forEach { op -> dao.op(op.id)?.let { backoff(it) } }
            }
            for (op in sends) {
                try {
                    send(a, op)
                } catch (t: Throwable) {
                    Log.w(TAG, "send failed", t)
                    when {
                        MailErrors.isAuth(t) -> {
                            Mail.recordError(a, t); backoff(op)
                        }
                        MailErrors.isTransient(t) -> backoff(op)
                        else -> failSend(a, op, MailErrors.describe(t, a.smtpHost))
                    }
                }
            }
        }
    }

    private fun backoff(op: Op) {
        val attempts = op.attempts + 1
        val wait = (30_000L shl minOf(attempts, 5)).coerceAtMost(15 * 60_000L)
        dao.updateOp(op.copy(attempts = attempts, notBefore = System.currentTimeMillis() + wait))
    }

    private fun runImap(a: Account, store: IMAPStore, ops: List<Op>) {
        val open = HashMap<String, IMAPFolder>()
        fun folder(path: String) = open.getOrPut(path) {
            (store.getFolder(path) as IMAPFolder).apply { open(JFolder.READ_WRITE) }
        }
        try {
            for (op in ops) {
                if (dao.op(op.id) == null) continue // undone meanwhile
                try {
                    val src = folder(op.folderPath)
                    val msg = src.getMessageByUID(op.uid)
                    val removes = op.kind == OpKind.MOVE || op.kind == OpKind.DELETE
                    if (msg == null) {
                        finish(op, removes)
                        continue
                    }
                    when (op.kind) {
                        OpKind.SEEN -> msg.setFlag(Flags.Flag.SEEN, op.arg == "1")
                        OpKind.FLAG -> msg.setFlag(Flags.Flag.FLAGGED, op.arg == "1")
                        OpKind.ANSWERED -> msg.setFlag(Flags.Flag.ANSWERED, true)
                        OpKind.MOVE -> {
                            val target = target(a, store, op.arg)
                            if (target.fullName != src.fullName) move(store, src, msg, target)
                        }
                        OpKind.DELETE -> {
                            src.setFlags(arrayOf(msg), Flags(Flags.Flag.DELETED), true)
                            expunge(store, src, msg)
                        }
                    }
                    finish(op, removes)
                } catch (e: MessagingException) {
                    if (MailErrors.isTransient(e)) throw e
                    Log.w(TAG, "server refused ${op.kind}", e)
                    dao.deleteOp(op.id)
                    if (op.messageId != 0L) dao.setHidden(op.messageId, false)
                    Graph.events.tryEmit(MailErrors.describe(e, a.imapHost))
                }
            }
        } finally {
            open.values.forEach { runCatching { if (it.isOpen) it.close(false) } }
        }
    }

    private fun finish(op: Op, removeLocal: Boolean) {
        dao.deleteOp(op.id)
        if (removeLocal && op.messageId != 0L) dao.deleteMessage(op.messageId)
    }

    /** Resolves a move target, creating the Archive folder on first use. */
    private fun target(a: Account, store: IMAPStore, arg: String): IMAPFolder {
        val path = if (arg == Mail.ARCHIVE_AUTO) {
            val ns = runCatching { store.personalNamespaces.firstOrNull() }.getOrNull()
            if (ns == null || ns.fullName.isEmpty()) "Archive" else "${ns.fullName}${ns.separator}Archive"
        } else {
            arg
        }
        val f = store.getFolder(path) as IMAPFolder
        if (!f.exists()) {
            f.create(JFolder.HOLDS_MESSAGES)
            runCatching { f.setSubscribed(true) }
            val role = if (arg == Mail.ARCHIVE_AUTO) Role.ARCHIVE else null
            dao.insertFolder(Folder(accountId = a.id, path = path, name = if (role != null) "Archive" else f.name, role = role, sort = Role.sort(role)))
        }
        return f
    }

    private fun move(store: IMAPStore, src: IMAPFolder, msg: javax.mail.Message, dst: IMAPFolder) {
        if (store.hasCapability("MOVE")) {
            src.moveMessages(arrayOf(msg), dst)
        } else {
            src.copyMessages(arrayOf(msg), dst)
            src.setFlags(arrayOf(msg), Flags(Flags.Flag.DELETED), true)
            expunge(store, src, msg)
        }
    }

    private fun expunge(store: IMAPStore, src: IMAPFolder, msg: javax.mail.Message) {
        if (store.hasCapability("UIDPLUS")) src.expunge(arrayOf(msg)) else src.expunge()
    }

    // ---------------------------------------------------------------- sending

    private fun send(a: Account, op: Op) {
        val file = File(op.arg)
        if (!file.exists()) {
            dao.deleteOp(op.id)
            return
        }
        val session = MailSession.smtpSession(a)
        val msg = file.inputStream().use { MimeMessage(session, it) }
        val transport = MailSession.connectTransport(ctx, a, session)
        try {
            transport.sendMessage(msg, msg.allRecipients)
        } finally {
            runCatching { transport.close() }
        }
        // Sent: never resend, even if saving a copy fails below.
        dao.deleteOp(op.id)
        Mail.rememberRecipients(msg)
        if (!Mail.savesSentCopy(a)) {
            runCatching {
                Connections.withStore(ctx, a) { store ->
                    val known = dao.folderByRole(a.id, Role.SENT)
                    val path = known?.path ?: run {
                        val ns = runCatching { store.personalNamespaces.firstOrNull() }.getOrNull()
                        if (ns == null || ns.fullName.isEmpty()) "Sent" else "${ns.fullName}${ns.separator}Sent"
                    }
                    val sent = store.getFolder(path) as IMAPFolder
                    if (!sent.exists()) {
                        sent.create(JFolder.HOLDS_MESSAGES)
                        runCatching { sent.setSubscribed(true) }
                    }
                    if (known == null) {
                        dao.insertFolder(Folder(accountId = a.id, path = path, name = "Sent", role = Role.SENT, sort = Role.sort(Role.SENT)))
                    }
                    msg.setFlag(Flags.Flag.SEEN, true)
                    sent.appendMessages(arrayOf(msg))
                }
            }.onFailure { Log.w(TAG, "couldn't save to Sent", it) }
        }
        file.delete()
        Graph.events.tryEmit("Sent")
    }

    private fun failSend(a: Account, op: Op, reason: String) {
        dao.updateOp(op.copy(error = reason))
        Notifier.sendFailed(ctx, a, op, reason)
    }
}
