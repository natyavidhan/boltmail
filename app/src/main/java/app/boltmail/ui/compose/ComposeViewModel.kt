package app.boltmail.ui.compose

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.boltmail.Graph
import app.boltmail.data.Account
import app.boltmail.data.Contact
import app.boltmail.mail.Addr
import app.boltmail.mail.Composer
import app.boltmail.mail.Draft
import app.boltmail.mail.DraftFile
import app.boltmail.mail.HtmlText
import app.boltmail.mail.Mail
import app.boltmail.mail.Subjects
import app.boltmail.ui.ComposeMode
import app.boltmail.ui.Fmt
import app.boltmail.ui.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.mail.internet.InternetAddress

enum class Field { To, Cc, Bcc }

class ComposeViewModel(private val route: Route.Compose) : ViewModel() {
    private val dao = Graph.dao
    private val ctx get() = Graph.app

    val accounts: StateFlow<List<Account>> = dao.accountsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    var accountId by mutableLongStateOf(0L)
    var to by mutableStateOf(TextFieldValue(route.to))
    var cc by mutableStateOf(TextFieldValue(route.cc))
    var bcc by mutableStateOf(TextFieldValue(""))
    var subject by mutableStateOf(route.subject)
    var body by mutableStateOf(route.body)
    var showCc by mutableStateOf(route.cc.isNotEmpty())
    val files = mutableStateListOf<DraftFile>()
    var loadingFiles by mutableIntStateOf(0)
    var quote by mutableStateOf<String?>(null)
    var quoteLabel by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var ready by mutableStateOf(route.mode == ComposeMode.New && route.refId == 0L)
    val suggestions = mutableStateListOf<Contact>()
    var suggestFor by mutableStateOf<Field?>(null)
    private var suggestJob: Job? = null
    private var inReplyTo: String? = null
    private var references: String? = null
    private var answered: Long? = null
    private var initial = ""

    val isReply get() = route.mode == ComposeMode.Reply || route.mode == ComposeMode.ReplyAll

    init {
        viewModelScope.launch {
            prefill()
            initial = snapshot()
            ready = true
        }
        if (route.files.isNotEmpty()) addFiles(Graph.app, route.files)
    }

    private fun snapshot() = listOf(to.text, cc.text, bcc.text, subject, body).joinToString("\u0000")

    fun hasChanges() = snapshot() != initial || files.isNotEmpty() && route.mode != ComposeMode.Forward

    private suspend fun prefill() {
        val list = withContext(Dispatchers.IO) { dao.accounts() }
        accountId = list.firstOrNull()?.id ?: 0L
        if (route.refId == 0L) return
        val m = withContext(Dispatchers.IO) { dao.message(route.refId) } ?: return
        accountId = m.accountId
        Mail.ensureBody(m.id)
        val b = withContext(Dispatchers.IO) { dao.body(m.id) }
        val original = b?.text?.takeIf { it.isNotBlank() } ?: b?.html?.let { HtmlText.toText(it) } ?: m.snippet
        val sender = m.fromName.ifBlank { m.fromAddress }
        val who = if (m.fromName.isNotBlank()) "${m.fromName} <${m.fromAddress}>" else m.fromAddress
        when (route.mode) {
            ComposeMode.Reply, ComposeMode.ReplyAll -> {
                val mine = list.map { it.email }.toSet()
                val (t, c) = Addr.replyRecipients(
                    m.fromName, m.fromAddress, m.replyTo, m.toList, m.ccList, mine, route.mode == ComposeMode.ReplyAll,
                )
                to = field(Addr.format(t))
                cc = field(Addr.format(c))
                showCc = c.isNotEmpty()
                subject = Subjects.reply(m.subject)
                quote = Composer.quote(Fmt.attribution(ctx, m.date, who), original)
                quoteLabel = "Quoting $sender"
                inReplyTo = m.messageId
                references = Composer.references(m.refs, m.inReplyTo, m.messageId)
                answered = m.id
            }
            ComposeMode.Forward -> {
                subject = Subjects.forward(m.subject)
                quote = buildString {
                    append("---------- Forwarded message ---------\n")
                    append("From: $who\n")
                    append("Date: ${Fmt.full(ctx, m.date)}\n")
                    append("Subject: ${m.subject}\n")
                    if (m.toList.isNotBlank()) append("To: ${m.toList}\n")
                    if (m.ccList.isNotBlank()) append("Cc: ${m.ccList}\n")
                    append('\n')
                    append(original.trimEnd())
                }
                quoteLabel = "Forwarding $sender's message"
                val atts = withContext(Dispatchers.IO) { dao.attachments(m.id) }.filter { !it.inline }
                for (a in atts) {
                    loadingFiles++
                    viewModelScope.launch {
                        try {
                            val f = Mail.attachmentFile(a.id)
                            files += DraftFile(a.name, a.mime, f.absolutePath, f.length())
                        } catch (t: Throwable) {
                            error = "Couldn't attach ${a.name}."
                        } finally {
                            loadingFiles--
                        }
                    }
                }
            }
            ComposeMode.New -> Unit
        }
    }

    private fun field(text: String) = TextFieldValue(if (text.isEmpty()) "" else "$text, ").let { it.copy(selection = TextRange(it.text.length)) }

    fun value(f: Field) = when (f) {
        Field.To -> to
        Field.Cc -> cc
        Field.Bcc -> bcc
    }

    fun update(f: Field, v: TextFieldValue) {
        when (f) {
            Field.To -> to = v
            Field.Cc -> cc = v
            Field.Bcc -> bcc = v
        }
        error = null
        suggestFor = f
        val token = v.text.substring(0, v.selection.end.coerceIn(0, v.text.length))
            .split(',', ';').lastOrNull()?.trim().orEmpty()
        suggestJob?.cancel()
        if (token.isEmpty()) {
            suggestions.clear()
            return
        }
        suggestJob = viewModelScope.launch {
            val found = Mail.suggest(token).filter { !v.text.contains(it.address, ignoreCase = true) }
            suggestions.clear()
            suggestions += found
        }
    }

    fun pick(f: Field, contact: Contact) {
        val current = value(f).text
        val cut = maxOf(current.lastIndexOf(','), current.lastIndexOf(';'))
        val prefix = if (cut >= 0) current.substring(0, cut + 1) + " " else ""
        val entry = if (contact.name.isNotBlank()) {
            runCatching { InternetAddress(contact.address, contact.name, "UTF-8").toUnicodeString() }.getOrDefault(contact.address)
        } else {
            contact.address
        }
        val text = prefix.trimStart() + entry + ", "
        update(f, TextFieldValue(text, TextRange(text.length)))
        suggestions.clear()
    }

    fun addFiles(context: Context, uris: List<Uri>) {
        for (uri in uris) {
            loadingFiles++
            viewModelScope.launch {
                try {
                    files += withContext(Dispatchers.IO) { copy(context, uri) }
                } catch (t: Throwable) {
                    error = "Couldn't attach that file."
                } finally {
                    loadingFiles--
                }
            }
        }
    }

    fun removeFile(f: DraftFile) {
        files.remove(f)
    }

    private fun copy(context: Context, uri: Uri): DraftFile {
        val cr = context.contentResolver
        var name = "attachment"
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = cr.getType(uri) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val dir = File(context.cacheDir, "outgoing/${UUID.randomUUID()}").apply { mkdirs() }
        val out = File(dir, Mail.safeName(name))
        (cr.openInputStream(uri) ?: throw IllegalStateException("unreadable")).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return DraftFile(name, mime, out.absolutePath, out.length())
    }

    /** Validates and queues the message. Returns true when it's on its way. */
    fun send(): Boolean {
        val (toList, badTo) = Addr.parseInput(to.text)
        val (ccList, badCc) = Addr.parseInput(cc.text)
        val (bccList, badBcc) = Addr.parseInput(bcc.text)
        val bad = badTo + badCc + badBcc
        when {
            bad.isNotEmpty() -> error = "“${bad.first()}” isn't an email address."
            toList.isEmpty() && ccList.isEmpty() && bccList.isEmpty() -> error = "Add someone to send this to."
            loadingFiles > 0 -> error = "Wait a moment, attachments are still loading."
        }
        if (error != null) return false
        val acct = accounts.value.firstOrNull { it.id == accountId } ?: run {
            error = "Choose an account to send from."
            return false
        }
        val text = buildString {
            append(body.trimEnd())
            if (acct.signature.isNotBlank()) append("\n\n-- \n").append(acct.signature.trim())
            quote?.let { append("\n\n").append(it) }
        }
        val draft = Draft(
            accountId = acct.id, to = toList, cc = ccList, bcc = bccList, subject = subject.trim(), body = text,
            files = files.toList(), inReplyTo = inReplyTo, references = references, answeredMessageId = answered,
        )
        Graph.scope.launch { Mail.send(draft) }
        return true
    }

    fun totalSize() = files.sumOf { it.size }
}
