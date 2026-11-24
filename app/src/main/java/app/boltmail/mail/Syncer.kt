package app.boltmail.mail

import app.boltmail.Graph
import app.boltmail.data.Account
import app.boltmail.data.Attachment
import app.boltmail.data.BODY_FAILED
import app.boltmail.data.BODY_LOADED
import app.boltmail.data.Body
import app.boltmail.data.Folder
import app.boltmail.data.Message
import app.boltmail.data.Role
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPMessage
import com.sun.mail.imap.IMAPStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.MessagingException
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeUtility
import kotlin.concurrent.withLock
import javax.mail.Folder as JFolder
import javax.mail.Message as JMessage

/** Mirrors server state into the local database. Everything here is blocking. */
object Syncer {
    const val INITIAL = 50
    const val PAGE = 50
    const val BODIES = 25
    private const val MAX_HTML = 600_000
    private const val MAX_TEXT = 300_000
    private const val FOLDER_LIST_TTL = 30 * 60_000L

    private val dao get() = Graph.dao
    private val locks = ConcurrentHashMap<Long, ReentrantLock>()
    private val folderListSynced = ConcurrentHashMap<Long, Long>()

    class Result(
        val inserted: List<Message> = emptyList(),
        val initial: Boolean = false,
        /** Messages read or removed elsewhere; their notifications should go away. */
        val settled: List<Long> = emptyList(),
    )

    // ---------------------------------------------------------------- folders

    fun ensureFolders(account: Account, store: IMAPStore, force: Boolean = false) {
        val last = folderListSynced[account.id] ?: 0L
        val stale = System.currentTimeMillis() - last > FOLDER_LIST_TTL
        if (force || stale || dao.folderByRole(account.id, Role.INBOX) == null) {
            syncFolderList(account, store)
            folderListSynced[account.id] = System.currentTimeMillis()
        }
    }

    fun syncFolderList(account: Account, store: IMAPStore) {
        class Cand(val folder: Folder, val strong: Boolean)

        val found = mutableListOf<Cand>()
        for (raw in store.defaultFolder.list("*")) {
            val f = raw as IMAPFolder
            val attrs = runCatching { f.attributes }.getOrNull().orEmpty().map { it.lowercase() }
            if ("\\noselect" in attrs || "\\nonexistent" in attrs) continue
            val holds = runCatching { f.type and JFolder.HOLDS_MESSAGES != 0 }.getOrDefault(true)
            if (!holds) continue
            val path = f.fullName
            val sep = runCatching { f.separator }.getOrDefault('/')
            val (role, strong) = role(path, f.name, attrs)
            found += Cand(
                Folder(accountId = account.id, path = path, name = displayName(path, role, sep), role = role, sort = Role.sort(role)),
                strong,
            )
        }
        if (found.none { it.folder.role == Role.INBOX }) {
            found += Cand(Folder(accountId = account.id, path = "INBOX", name = "Inbox", role = Role.INBOX, sort = 0), true)
        }
        // One folder per role; special-use attributes beat name guesses.
        val byRole = found.filter { it.folder.role != null }.groupBy { it.folder.role }
        val keep = byRole.mapValues { (_, list) -> (list.firstOrNull { it.strong } ?: list.first()).folder.path }
        val final = found.map { c ->
            val r = c.folder.role
            if (r != null && keep[r] != c.folder.path) {
                c.folder.copy(role = null, sort = 50, name = displayName(c.folder.path, null, '/'))
            } else {
                c.folder
            }
        }
        val paths = final.map { it.path }.toSet()
        Graph.db.runInTransaction {
            for (f in final) {
                val existing = dao.folderByPath(account.id, f.path)
                if (existing == null) {
                    dao.insertFolder(f)
                } else if (existing.role != f.role || existing.name != f.name || existing.sort != f.sort) {
                    dao.updateFolder(existing.copy(role = f.role, name = f.name, sort = f.sort))
                }
            }
            for (local in dao.folders(account.id)) if (local.path !in paths) dao.deleteFolder(local.id)
        }
    }

    private fun role(path: String, name: String, attrs: List<String>): Pair<String?, Boolean> {
        if (path.equals("INBOX", ignoreCase = true)) return Role.INBOX to true
        val byAttr = when {
            "\\sent" in attrs -> Role.SENT
            "\\drafts" in attrs -> Role.DRAFTS
            "\\trash" in attrs -> Role.TRASH
            "\\junk" in attrs -> Role.SPAM
            "\\archive" in attrs -> Role.ARCHIVE
            "\\all" in attrs -> Role.ALL
            else -> null
        }
        if (byAttr != null) return byAttr to true
        val byName = when (name.trim().lowercase()) {
            "sent", "sent items", "sent messages", "sent mail" -> Role.SENT
            "drafts", "draft" -> Role.DRAFTS
            "trash", "deleted items", "deleted messages", "deleted", "bin" -> Role.TRASH
            "junk", "spam", "junk e-mail", "junk email", "bulk mail" -> Role.SPAM
            "archive", "archives" -> Role.ARCHIVE
            "all mail" -> Role.ALL
            else -> null
        }
        return byName to false
    }

    private fun displayName(path: String, role: String?, sep: Char): String = when (role) {
        Role.INBOX -> "Inbox"
        Role.SENT -> "Sent"
        Role.DRAFTS -> "Drafts"
        Role.TRASH -> "Trash"
        Role.SPAM -> "Spam"
        Role.ARCHIVE -> "Archive"
        Role.ALL -> "All mail"
        else -> {
            var p = path
            if (p.length > 6 && p.startsWith("INBOX$sep", ignoreCase = true)) p = p.substring(6)
            if (p.startsWith("[Gmail]$sep")) p = p.substring(8)
            p.split(sep).filter { it.isNotEmpty() }.joinToString(" / ").ifEmpty { path }
        }
    }

    // ---------------------------------------------------------------- messages

    /**
     * Pulls new messages and (when [full]) reconciles flags and deletions for the cached window.
     * The first sync of a folder fetches the newest [INITIAL] messages and never notifies.
     */
    fun syncFolder(account: Account, folderId: Long, imap: IMAPFolder, full: Boolean): Result =
        locks.getOrPut(folderId) { ReentrantLock() }.withLock {
            var f = dao.folder(folderId) ?: return@withLock Result()
            val uidValidity = imap.uidValidity
            if (f.uidValidity != uidValidity) {
                if (f.uidValidity != 0L) dao.clearFolder(f.id)
                dao.resetFolder(f.id, uidValidity)
                f = dao.folder(f.id) ?: return@withLock Result()
            }
            val initial = !f.synced
            val count = imap.messageCount
            val fresh: Array<JMessage> = when {
                count <= 0 -> emptyArray()
                initial -> imap.getMessages(maxOf(1, count - INITIAL + 1), count)
                else -> imap.getMessagesByUID(f.highestUid + 1, UIDFolder.LASTUID)
            }
            val inserted = insertMessages(account, f, imap, fresh, notified = initial)
            val settled = if (initial) emptyList() else syncFlags(f, imap, if (full) Int.MAX_VALUE else 300)
            dao.markSynced(f.id, System.currentTimeMillis(), if (initial) count > INITIAL else f.hasMore)
            // Brand-new mail gets its body first so the notification can show a preview.
            fetchBodies(imap, inserted.sortedByDescending { it.uid }.take(10))
            Result(inserted.map { dao.message(it.id) ?: it }, initial, settled)
        }

    fun backfillBodies(folderId: Long, imap: IMAPFolder, limit: Int = BODIES) {
        fetchBodies(imap, dao.needingBodies(folderId, limit))
    }

    /** Fetches the next page of older messages. Returns how many were added. */
    fun loadOlder(account: Account, folderId: Long, imap: IMAPFolder): Int =
        locks.getOrPut(folderId) { ReentrantLock() }.withLock {
            val f = dao.folder(folderId) ?: return@withLock 0
            if (f.uidValidity != imap.uidValidity || !f.synced) return@withLock 0
            val minUid = dao.minUid(f.id)
            if (minUid == null) {
                dao.setHasMore(f.id, false)
                return@withLock 0
            }
            val anchor = imap.getMessageByUID(minUid)
            val end = anchor?.messageNumber?.minus(1)
                ?: imap.getMessagesByUID(1, minUid - 1).lastOrNull()?.messageNumber
                ?: 0
            if (end < 1) {
                dao.setHasMore(f.id, false)
                return@withLock 0
            }
            val start = maxOf(1, end - PAGE + 1)
            val inserted = insertMessages(account, f, imap, imap.getMessages(start, end), notified = true)
            dao.setHasMore(f.id, start > 1)
            fetchBodies(imap, inserted.sortedByDescending { it.uid })
            inserted.size
        }

    private fun insertMessages(
        account: Account,
        f: Folder,
        imap: IMAPFolder,
        msgs: Array<JMessage>,
        notified: Boolean,
    ): List<Message> {
        if (msgs.isEmpty()) return emptyList()
        fetchHeaders(imap, msgs)
        val out = mutableListOf<Message>()
        var maxUid = f.highestUid
        for (m in msgs) {
            if (m.isExpunged) continue
            val uid = imap.getUID(m)
            if (uid <= 0) continue
            if (uid > maxUid) maxUid = uid
            if (dao.messageByUid(f.id, uid) != null) continue
            val e = runCatching { toEntity(account, f, m as IMAPMessage, uid, notified) }.getOrNull() ?: continue
            val id = dao.insertMessage(e)
            if (id > 0) out += e.copy(id = id)
        }
        dao.bumpHighestUid(f.id, maxUid)
        return out
    }

    private fun fetchHeaders(imap: IMAPFolder, msgs: Array<JMessage>) {
        val fp = FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(FetchProfile.Item.FLAGS)
            add(FetchProfile.Item.CONTENT_INFO)
            add(FetchProfile.Item.SIZE)
            add(UIDFolder.FetchProfileItem.UID)
            add("References")
            add("In-Reply-To")
        }
        imap.fetch(msgs, fp)
    }

    private fun toEntity(account: Account, f: Folder, m: IMAPMessage, uid: Long, notified: Boolean): Message {
        val from = runCatching { m.from?.firstOrNull() as? InternetAddress }.getOrNull()
        val flags = m.flags
        val seen = flags.contains(Flags.Flag.SEEN)
        val structure = runCatching { MimeParser.parse(m, fetchText = false) }.getOrNull()
        val replyTo = Addr.format(runCatching { m.replyTo }.getOrNull())
            .takeIf { rt -> rt.isNotEmpty() && Addr.parse(rt).any { !Addr.same(it.address, from?.address) } }
            .orEmpty()
        val date = runCatching { m.receivedDate }.getOrNull() ?: runCatching { m.sentDate }.getOrNull()
        return Message(
            accountId = account.id,
            folderId = f.id,
            uid = uid,
            messageId = runCatching { m.messageID }.getOrNull()?.trim(),
            inReplyTo = header(m, "In-Reply-To"),
            refs = header(m, "References"),
            subject = runCatching { m.subject }.getOrNull()?.trim().orEmpty(),
            fromName = from?.personal?.trim().orEmpty(),
            fromAddress = from?.address?.trim().orEmpty(),
            replyTo = replyTo,
            toList = Addr.format(runCatching { m.getRecipients(JMessage.RecipientType.TO) }.getOrNull()),
            ccList = Addr.format(runCatching { m.getRecipients(JMessage.RecipientType.CC) }.getOrNull()),
            date = date?.time ?: System.currentTimeMillis(),
            seen = seen,
            flagged = flags.contains(Flags.Flag.FLAGGED),
            answered = flags.contains(Flags.Flag.ANSWERED),
            hasAttachments = structure?.hasAttachments ?: false,
            size = runCatching { m.size }.getOrDefault(0),
            notified = notified || seen,
        )
    }

    private fun header(m: IMAPMessage, name: String): String? =
        runCatching { m.getHeader(name)?.firstOrNull() }.getOrNull()
            ?.let { MimeUtility.unfold(it).trim() }?.ifBlank { null }

    private fun syncFlags(f: Folder, imap: IMAPFolder, window: Int): List<Long> {
        val minUid = dao.minUid(f.id) ?: return emptyList()
        val states = dao.flagStates(f.id, minUid).sortedByDescending { it.uid }
        if (states.isEmpty()) return emptyList()
        val scope = if (states.size > window) states.take(window) else states
        val lo = scope.last().uid
        val hi = maxOf(scope.first().uid, dao.folder(f.id)?.highestUid ?: 0)
        val msgs = imap.getMessagesByUID(lo, hi)
        val fp = FetchProfile().apply {
            add(FetchProfile.Item.FLAGS)
            add(UIDFolder.FetchProfileItem.UID)
        }
        imap.fetch(msgs, fp)
        val server = HashMap<Long, Flags>(msgs.size * 2)
        for (m in msgs) {
            if (m.isExpunged) continue
            val u = imap.getUID(m)
            if (u >= lo) server[u] = m.flags
        }
        val pending = dao.messagesWithOps().toHashSet()
        val settled = mutableListOf<Long>()
        val gone = mutableListOf<Long>()
        Graph.db.runInTransaction {
            for (s in scope) {
                if (s.id in pending) continue
                val fl = server[s.uid]
                if (fl == null) {
                    gone += s.uid
                    settled += s.id
                    continue
                }
                val seen = fl.contains(Flags.Flag.SEEN)
                val flagged = fl.contains(Flags.Flag.FLAGGED)
                val answered = fl.contains(Flags.Flag.ANSWERED)
                if (seen != s.seen || flagged != s.flagged || answered != s.answered) {
                    dao.setFlags(s.id, seen, flagged, answered)
                    if (seen && !s.seen) settled += s.id
                }
            }
            gone.chunked(500).forEach { dao.deleteUids(f.id, it) }
        }
        return settled
    }

    // ---------------------------------------------------------------- bodies

    private fun fetchBodies(imap: IMAPFolder, list: List<Message>) {
        if (list.isEmpty()) return
        val remote = imap.getMessagesByUID(list.map { it.uid }.toLongArray())
        for ((i, local) in list.withIndex()) {
            val m = remote.getOrNull(i) ?: continue
            try {
                storeBody(local, m)
            } catch (e: MessagingException) {
                if (MailErrors.isTransient(e)) throw e
                dao.setBodyState(local.id, BODY_FAILED)
            } catch (e: RuntimeException) {
                dao.setBodyState(local.id, BODY_FAILED)
            }
        }
    }

    fun storeBody(local: Message, m: JMessage) {
        val r = MimeParser.parse(m, fetchText = true)
        val html = r.html?.let { if (it.length > MAX_HTML) it.substring(0, MAX_HTML) else it }
        val text = r.text?.let { if (it.length > MAX_TEXT) it.substring(0, MAX_TEXT) else it }
        val textSnippet = text?.let { HtmlText.snippet(it) }.orEmpty()
        val snippet = if (textSnippet.length >= 24 || html == null) textSnippet else HtmlText.snippet(HtmlText.toText(html))
        Graph.db.runInTransaction {
            dao.insertBody(Body(local.id, html, text))
            dao.clearAttachments(local.id)
            if (r.items.isNotEmpty()) {
                dao.insertAttachments(
                    r.items.map {
                        Attachment(
                            messageId = local.id, part = it.path, name = it.name, mime = it.mime,
                            size = it.size, cid = it.cid, inline = it.inline,
                        )
                    },
                )
            }
            dao.setBodyInfo(local.id, snippet, BODY_LOADED, r.hasAttachments)
        }
    }
}
