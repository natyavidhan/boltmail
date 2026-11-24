package app.boltmail.mail

import android.util.Base64
import app.boltmail.Graph
import app.boltmail.data.Account
import app.boltmail.data.Contact
import app.boltmail.data.ERR_AUTH
import app.boltmail.data.Folder
import app.boltmail.data.Message
import app.boltmail.data.Op
import app.boltmail.data.OpKind
import app.boltmail.data.Role
import app.boltmail.push.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import javax.mail.MessagingException
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/** What the UI and notifications call. Local state changes immediately; servers catch up via [OpRunner]. */
object Mail {
    const val ARCHIVE_AUTO = "@archive"
    const val UNDO_MS = 6_000L

    private val dao get() = Graph.dao
    private val ctx get() = Graph.app

    private suspend inline fun <T> io(crossinline block: () -> T): T = withContext(Dispatchers.IO) { block() }

    // ---------------------------------------------------------------- sync

    /** Refreshes a folder, or every inbox when [folderId] is null. Returns readable errors. */
    suspend fun refresh(folderId: Long?): List<String> = coroutineScope {
        val targets: List<Pair<Account, Long?>> = io {
            if (folderId == null) {
                dao.accounts().map { it to null }
            } else {
                val f = dao.folder(folderId)
                listOfNotNull(f?.let { dao.account(it.accountId) }?.let { it to folderId })
            }
        }
        targets.map { (a, fid) ->
            async(Dispatchers.IO) {
                runCatching { refreshFolder(a, fid) }.exceptionOrNull()?.let { "${a.label}: ${MailErrors.describe(it, a.imapHost)}" }
            }
        }.awaitAll().filterNotNull()
    }

    /** Blocking. */
    fun refreshFolder(a: Account, folderId: Long?) {
        try {
            Connections.withStore(ctx, a) { store ->
                Syncer.ensureFolders(a, store)
                val f = (folderId?.let { dao.folder(it) } ?: dao.folderByRole(a.id, Role.INBOX)) ?: return@withStore
                store.withFolder(f.path) { imap ->
                    val r = Syncer.syncFolder(a, f.id, imap, full = true)
                    Notifier.onSync(ctx, a, f, r)
                    Syncer.backfillBodies(f.id, imap)
                }
            }
            clearError(a.id)
        } catch (t: Throwable) {
            recordError(a, t)
            throw t
        }
    }

    suspend fun loadOlder(folderIds: List<Long>) = coroutineScope {
        folderIds.map { fid ->
            async(Dispatchers.IO) {
                runCatching {
                    val f = dao.folder(fid) ?: return@runCatching 0
                    val a = dao.account(f.accountId) ?: return@runCatching 0
                    Connections.withStore(ctx, a) { s -> s.withFolder(f.path) { Syncer.loadOlder(a, fid, it) } }
                }.getOrDefault(0)
            }
        }.awaitAll().sum()
    }

    /** Makes sure a message's body is stored locally. Returns an error message on failure. */
    suspend fun ensureBody(id: Long): String? = io {
        val m = dao.message(id) ?: return@io null
        if (dao.body(id) != null) return@io null
        val a = dao.account(m.accountId) ?: return@io null
        val f = dao.folder(m.folderId) ?: return@io null
        try {
            Connections.withStore(ctx, a) { s ->
                s.withFolder(f.path) { imap ->
                    val remote = imap.getMessageByUID(m.uid)
                        ?: throw FileNotFoundException("This message was removed from the server.")
                    Syncer.storeBody(m, remote)
                }
            }
            null
        } catch (t: Throwable) {
            if (t is FileNotFoundException) t.message else MailErrors.describe(t, a.imapHost)
        }
    }

    // ---------------------------------------------------------------- actions

    private fun folderPath(m: Message) = dao.folder(m.folderId)?.path

    suspend fun setSeen(ids: Collection<Long>, seen: Boolean) = io {
        for (id in ids) {
            val m = dao.message(id) ?: continue
            if (seen) Notifier.cancel(ctx, id)
            if (m.seen == seen) continue
            dao.setSeen(id, seen)
            val path = folderPath(m) ?: continue
            dao.insertOp(Op(accountId = m.accountId, kind = OpKind.SEEN, messageId = id, folderPath = path, uid = m.uid, arg = if (seen) "1" else "0"))
        }
        OpRunner.kick()
    }

    suspend fun setFlagged(id: Long, flagged: Boolean) = io {
        val m = dao.message(id) ?: return@io
        dao.setFlagged(id, flagged)
        val path = folderPath(m) ?: return@io
        dao.insertOp(Op(accountId = m.accountId, kind = OpKind.FLAG, messageId = id, folderPath = path, uid = m.uid, arg = if (flagged) "1" else "0"))
        OpRunner.kick()
    }

    fun canArchive(role: String?) = role != Role.ARCHIVE && role != Role.ALL

    /** Archives with an undo window; returns the op id to pass to [undo], or null if not possible. */
    suspend fun archive(id: Long, delayMs: Long = UNDO_MS): Long? = io {
        val m = dao.message(id) ?: return@io null
        val f = dao.folder(m.folderId) ?: return@io null
        if (!canArchive(f.role)) return@io null
        val target = dao.folderByRole(m.accountId, Role.ARCHIVE)?.path
            ?: dao.folderByRole(m.accountId, Role.ALL)?.path
            ?: ARCHIVE_AUTO
        hideAndQueue(m, f, OpKind.MOVE, target, delayMs)
    }

    /** Moves to Trash, or deletes for good when already in Trash or there is none. */
    suspend fun delete(id: Long, delayMs: Long = UNDO_MS): Long? = io {
        val m = dao.message(id) ?: return@io null
        val f = dao.folder(m.folderId) ?: return@io null
        val trash = dao.folderByRole(m.accountId, Role.TRASH)
        if (trash == null || f.role == Role.TRASH) {
            hideAndQueue(m, f, OpKind.DELETE, "", delayMs)
        } else {
            hideAndQueue(m, f, OpKind.MOVE, trash.path, delayMs)
        }
    }

    suspend fun moveTo(id: Long, folderId: Long, delayMs: Long = UNDO_MS): Long? = io {
        val m = dao.message(id) ?: return@io null
        val f = dao.folder(m.folderId) ?: return@io null
        val target = dao.folder(folderId) ?: return@io null
        if (target.id == f.id || target.accountId != m.accountId) return@io null
        hideAndQueue(m, f, OpKind.MOVE, target.path, delayMs)
    }

    private fun hideAndQueue(m: Message, f: Folder, kind: String, arg: String, delayMs: Long): Long {
        dao.setHidden(m.id, true)
        Notifier.cancel(ctx, m.id)
        val opId = dao.insertOp(
            Op(
                accountId = m.accountId, kind = kind, messageId = m.id, folderPath = f.path, uid = m.uid,
                arg = arg, notBefore = System.currentTimeMillis() + delayMs,
            ),
        )
        OpRunner.kick(delayMs)
        return opId
    }

    suspend fun undo(opId: Long): Boolean = io {
        val op = dao.op(opId) ?: return@io false
        dao.deleteOp(opId)
        if (op.messageId != 0L) dao.setHidden(op.messageId, false)
        true
    }

    // ---------------------------------------------------------------- sending

    suspend fun send(d: Draft) = io {
        val a = dao.account(d.accountId) ?: return@io
        val file = Composer.write(ctx, a, d)
        dao.insertOp(Op(accountId = a.id, kind = OpKind.SEND, arg = file.absolutePath, title = d.subject.ifBlank { "(no subject)" }))
        d.answeredMessageId?.let { mid ->
            val m = dao.message(mid) ?: return@let
            dao.setFlags(m.id, m.seen, m.flagged, true)
            folderPath(m)?.let { path ->
                dao.insertOp(Op(accountId = m.accountId, kind = OpKind.ANSWERED, messageId = m.id, folderPath = path, uid = m.uid))
            }
        }
        OpRunner.kick()
    }

    suspend fun retrySend(opId: Long) = io {
        dao.op(opId)?.let { dao.updateOp(it.copy(error = null, attempts = 0, notBefore = 0)) }
        OpRunner.kick()
    }

    suspend fun discardSend(opId: Long) = io {
        dao.op(opId)?.let {
            File(it.arg).delete()
            dao.deleteOp(it.id)
        }
    }

    /** Gmail and Microsoft file sent mail themselves; everyone else needs a copy appended to Sent. */
    fun savesSentCopy(a: Account) =
        a.imapHost.endsWith("gmail.com") || a.imapHost.contains("office365") || a.imapHost.contains("outlook.")

    fun rememberRecipients(msg: MimeMessage) {
        val now = System.currentTimeMillis()
        for (addr in msg.allRecipients.orEmpty()) {
            val ia = addr as? InternetAddress ?: continue
            val key = ia.address?.lowercase() ?: continue
            val old = dao.contact(key)
            dao.upsertContact(Contact(key, ia.personal?.ifBlank { null } ?: old?.name.orEmpty(), now, (old?.count ?: 0) + 1))
        }
    }

    suspend fun suggest(q: String): List<Contact> = io {
        val t = q.trim()
        if (t.isEmpty()) return@io emptyList()
        val like = "%$t%"
        (dao.searchContacts(like) + dao.searchSenders(like)).distinctBy { it.address.lowercase() }.take(5)
    }

    // ---------------------------------------------------------------- attachments

    suspend fun attachmentFile(attId: Long): File = io {
        val att = dao.attachment(attId) ?: throw FileNotFoundException("Attachment not found.")
        att.file?.let(::File)?.takeIf { it.exists() && it.length() > 0 }?.let { return@io it }
        val m = dao.message(att.messageId) ?: throw FileNotFoundException("Message not found.")
        val a = dao.account(m.accountId) ?: throw FileNotFoundException("Account not found.")
        val f = dao.folder(m.folderId) ?: throw FileNotFoundException("Folder not found.")
        val dir = File(ctx.cacheDir, "attachments/${att.id}").apply { mkdirs() }
        val out = File(dir, safeName(att.name))
        val tmp = File(dir, out.name + ".part")
        Connections.withStore(ctx, a) { store ->
            store.withFolder(f.path) { imap ->
                val msg = imap.getMessageByUID(m.uid) ?: throw FileNotFoundException("This message was removed from the server.")
                val part = MimeParser.find(msg, att.part) ?: throw FileNotFoundException("Couldn't find the attachment.")
                part.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
        }
        tmp.renameTo(out)
        dao.setAttachmentFile(att.id, out.absolutePath)
        out
    }

    /** cid → data: URI for small inline images referenced by [html]. */
    suspend fun inlineImages(messageId: Long, html: String): Map<String, String> {
        val wanted = io { dao.attachments(messageId) }.filter {
            it.cid != null && it.mime.startsWith("image/") && it.size < 3_000_000 && html.contains("cid:${it.cid}", true)
        }
        val out = HashMap<String, String>()
        for (att in wanted) {
            runCatching {
                val bytes = attachmentFile(att.id).readBytes()
                out[att.cid!!] = "data:${att.mime};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }
        return out
    }

    fun safeName(n: String) = n.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim().take(120).ifBlank { "attachment" }

    // ---------------------------------------------------------------- accounts

    /** Signs in over IMAP and checks the inbox exists. Blocking. */
    fun verifyImap(a: Account) {
        val tracker = SocketTracker()
        val store = MailSession.connectStore(ctx, a, tracker)
        try {
            if (!store.getFolder("INBOX").exists()) throw MessagingException("The server has no inbox for this account.")
        } finally {
            runCatching { store.close() }
            tracker.closeAll()
        }
    }

    /** Signs in over SMTP without sending anything. Blocking. */
    fun verifySmtp(a: Account) {
        val t = MailSession.connectTransport(ctx, a)
        runCatching { t.close() }
    }

    suspend fun addAccount(a: Account): Long = io {
        val id = dao.insertAccount(a.copy(sortOrder = dao.accounts().size))
        val saved = a.copy(id = id)
        Notifier.ensureAccountChannel(ctx, saved)
        Graph.scope.launch { runCatching { refreshFolder(saved, null) } }
        id
    }

    suspend fun updateAccount(a: Account) = io {
        dao.updateAccount(a)
        Connections.drop(a.id)
        Notifier.ensureAccountChannel(ctx, a)
    }

    suspend fun removeAccount(id: Long) = io {
        Connections.drop(id)
        Notifier.removeAccount(ctx, id)
        dao.deleteAccount(id)
    }

    fun recordError(a: Account, t: Throwable) {
        if (!MailErrors.isAuth(t)) return
        val fresh = dao.account(a.id) ?: return
        if (fresh.errorKind == ERR_AUTH) return
        dao.setAccountError(a.id, MailErrors.describe(t, a.imapHost), ERR_AUTH)
        Notifier.signInProblem(ctx, fresh)
    }

    fun clearError(id: Long) {
        val a = dao.account(id) ?: return
        if (a.error != null) {
            dao.setAccountError(id, null, null)
            Notifier.cancelSignInProblem(ctx, id)
        }
    }
}
