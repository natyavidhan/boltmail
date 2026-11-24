package dev.natya.boltmail.ui.message

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.natya.boltmail.Graph
import dev.natya.boltmail.R
import dev.natya.boltmail.data.Account
import dev.natya.boltmail.data.Attachment
import dev.natya.boltmail.data.Body
import dev.natya.boltmail.data.Folder
import dev.natya.boltmail.data.Message
import dev.natya.boltmail.data.Role
import dev.natya.boltmail.mail.Addr
import dev.natya.boltmail.mail.HtmlText
import dev.natya.boltmail.mail.Mail
import dev.natya.boltmail.mail.MailErrors
import dev.natya.boltmail.ui.ComposeMode
import dev.natya.boltmail.ui.Fmt
import dev.natya.boltmail.ui.LocalNav
import dev.natya.boltmail.ui.LocalToaster
import dev.natya.boltmail.ui.Route
import dev.natya.boltmail.ui.Toaster
import dev.natya.boltmail.ui.components.AccountTag
import dev.natya.boltmail.ui.components.BoltSnackbarHost
import dev.natya.boltmail.ui.components.IconBtn
import dev.natya.boltmail.ui.components.Monogram
import dev.natya.boltmail.ui.components.Pill
import dev.natya.boltmail.ui.components.ShadeCell
import dev.natya.boltmail.ui.components.ShadeGroup
import dev.natya.boltmail.ui.components.ShadeRow
import dev.natya.boltmail.ui.components.TextAction
import dev.natya.boltmail.ui.components.TopBar
import dev.natya.boltmail.ui.theme.Bolt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme.typography as type

class MessageViewModel(private val id: Long) : ViewModel() {
    private val dao = Graph.dao
    val message: StateFlow<Message?> = dao.messageFlow(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val body: StateFlow<Body?> = dao.bodyFlow(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val attachments: StateFlow<List<Attachment>> = dao.attachmentsFlow(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val accounts: StateFlow<List<Account>> = dao.accountsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val folders: StateFlow<List<Folder>> = dao.foldersFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val error = MutableStateFlow<String?>(null)
    val showImages = MutableStateFlow(Graph.prefs.loadImages.value)
    val inline = MutableStateFlow<Map<String, String>>(emptyMap())
    val downloading = MutableStateFlow<Set<Long>>(emptySet())
    var seenOnce = false

    init {
        viewModelScope.launch { Mail.setSeen(listOf(id), true) }
        load()
        viewModelScope.launch {
            val html = body.filterNotNull().first().html ?: return@launch
            if (html.contains("cid:", ignoreCase = true)) inline.value = Mail.inlineImages(id, html)
        }
    }

    fun load() {
        viewModelScope.launch {
            error.value = null
            error.value = Mail.ensureBody(id)
        }
    }

    fun open(att: Attachment, ctx: Context, toaster: Toaster) {
        if (att.id in downloading.value) return
        viewModelScope.launch {
            downloading.value = downloading.value + att.id
            try {
                val f = Mail.attachmentFile(att.id)
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, att.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.startActivity(i)
                } catch (e: ActivityNotFoundException) {
                    toaster.show("No app on this phone can open ${att.name.substringAfterLast('.', "this").uppercase()} files")
                }
            } catch (t: Throwable) {
                toaster.show(t.message?.takeIf { t is java.io.FileNotFoundException } ?: MailErrors.describe(t))
            } finally {
                downloading.value = downloading.value - att.id
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(id: Long) {
    val vm: MessageViewModel = viewModel(factory = viewModelFactory { initializer { MessageViewModel(id) } })
    val nav = LocalNav.current
    val toaster = LocalToaster.current
    val ctx = LocalContext.current
    val c = Bolt.colors
    val message by vm.message.collectAsStateWithLifecycle()
    val body by vm.body.collectAsStateWithLifecycle()
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val showImages by vm.showImages.collectAsStateWithLifecycle()
    val inline by vm.inline.collectAsStateWithLifecycle()
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var moveSheet by remember { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var quoted by rememberSaveable { mutableStateOf(false) }

    val m = message
    LaunchedEffect(m) {
        if (m != null && !m.hidden) vm.seenOnce = true
        if ((m == null || m.hidden) && vm.seenOnce && nav.top.route == Route.Message(id)) nav.pop()
    }
    if (m == null) {
        Box(Modifier.fillMaxSize().background(c.ground))
        return
    }
    val account = accounts.firstOrNull { it.id == m.accountId }
    val folder = folders.firstOrNull { it.id == m.folderId }
    val spam = folders.firstOrNull { it.accountId == m.accountId && it.role == Role.SPAM }
    val inbox = folders.firstOrNull { it.accountId == m.accountId && it.role == Role.INBOX }
    val mine = remember(accounts) { accounts.map { it.email }.toSet() }
    val (allTo, allCc) = remember(m, mine) {
        Addr.replyRecipients(m.fromName, m.fromAddress, m.replyTo, m.toList, m.ccList, mine, all = true)
    }
    val replyAllUseful = allTo.size + allCc.size > 1

    fun leave(label: String, op: suspend () -> Long?) {
        toaster.launch { toaster.undoable(label, op()) }
        nav.pop()
    }

    Scaffold(
        containerColor = c.ground,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopBar(onBack = { nav.pop() }) {
                if (Mail.canArchive(folder?.role)) IconBtn(R.drawable.ic_archive, "Archive", { leave("Archived") { Mail.archive(m.id) } })
                IconBtn(R.drawable.ic_delete, "Delete", { leave("Deleted") { Mail.delete(m.id) } })
                IconBtn(R.drawable.ic_mark_email_unread, "Mark as unread", {
                    toaster.launch { Mail.setSeen(listOf(m.id), false) }
                    nav.pop()
                })
                Box {
                    IconBtn(R.drawable.ic_more_vert, "More", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
                        DropdownMenuItem(text = { Text("Move to…") }, onClick = { menu = false; moveSheet = true })
                        if (spam != null && folder?.role != Role.SPAM) {
                            DropdownMenuItem(text = { Text("Report spam") }, onClick = {
                                menu = false
                                leave("Moved to Spam") { Mail.moveTo(m.id, spam.id) }
                            })
                        }
                        if (folder?.role == Role.SPAM && inbox != null) {
                            DropdownMenuItem(text = { Text("Not spam") }, onClick = {
                                menu = false
                                leave("Moved to Inbox") { Mail.moveTo(m.id, inbox.id) }
                            })
                        }
                    }
                }
            }
        },
        snackbarHost = { BoltSnackbarHost(Modifier.navigationBarsPadding().padding(bottom = 64.dp)) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .fillMaxSize()
                    .dragScroll(scroll)
                    .verticalScroll(scroll),
            ) {
                Text(
                    m.subject.ifBlank { "(no subject)" },
                    style = type.headlineSmall,
                    color = c.ink,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 16.dp),
                )
                ShadeGroup {
                    item { shape -> SenderCard(shape, m, account, accounts.size > 1, details) { details = !details } }
                    item { shape ->
                        ShadeCell(shape, padding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp)) {
                            val b = body
                            val html = b?.html
                            val text = b?.text
                            when {
                                html != null -> {
                                    val remote = remember(html) { HtmlText.hasRemoteContent(html) }
                                    if (remote && !showImages) ImagesHidden { vm.showImages.value = true }
                                    HtmlBody(html, showImages, inline)
                                }
                                text != null -> {
                                    val (main, rest) = remember(text) { splitQuoted(text) }
                                    TextBody(main)
                                    if (rest != null) {
                                        if (quoted) {
                                            Spacer(Modifier.height(12.dp))
                                            TextBody(rest)
                                        } else {
                                            QuoteToggle { quoted = true }
                                        }
                                    }
                                }
                                b != null -> Text("This message has no text.", style = type.bodyMedium, color = c.ink3)
                                error != null -> {
                                    Text(error ?: "", style = type.bodyMedium, color = c.ink2)
                                    TextAction("Try again", vm::load, modifier = Modifier.padding(top = 6.dp))
                                }
                                else -> BodySkeleton()
                            }
                            val files = attachments.filter { a -> !a.inline || (a.cid != null && html?.contains("cid:${a.cid}", true) != true) }
                            if (files.isNotEmpty()) {
                                Spacer(Modifier.height(16.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (a in files) AttachmentChip(a, a.id in downloading) { vm.open(a, ctx.applicationContext, toaster) }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.navigationBarsPadding().height(92.dp))
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to c.ground))
                    .navigationBarsPadding()
                    .padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill("Reply", { nav.push(Route.Compose(ComposeMode.Reply, m.id)) }, Modifier.weight(1f), R.drawable.ic_reply, primary = true)
                if (replyAllUseful) Pill("Reply all", { nav.push(Route.Compose(ComposeMode.ReplyAll, m.id)) }, Modifier.weight(1f), R.drawable.ic_reply_all)
                Pill("Forward", { nav.push(Route.Compose(ComposeMode.Forward, m.id)) }, Modifier.weight(1f), R.drawable.ic_forward)
            }
        }
    }

    if (moveSheet) {
        val targets = folders.filter { it.accountId == m.accountId && it.id != m.folderId }
        ModalBottomSheet(
            onDismissRequest = { moveSheet = false },
            containerColor = c.ground,
            dragHandle = { BottomSheetDefaults.DragHandle(color = c.ink3.copy(alpha = 0.5f)) },
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
                Text("Move to", style = type.titleLarge, color = c.ink, modifier = Modifier.padding(start = 24.dp, bottom = 12.dp))
                ShadeGroup {
                    for (f in targets) item { shape ->
                        ShadeRow(shape, f.name, icon = R.drawable.ic_folder, onClick = {
                            moveSheet = false
                            leave("Moved to ${f.name}") { Mail.moveTo(m.id, f.id) }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun SenderCard(shape: Shape, m: Message, account: Account?, showTag: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    val c = Bolt.colors
    val ctx = LocalContext.current
    val name = m.fromName.ifBlank { m.fromAddress.ifBlank { "(no sender)" } }
    ShadeCell(shape, onClick = onToggle, padding = PaddingValues(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Monogram(name, m.fromAddress)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = type.titleMedium.copy(fontWeight = FontWeight(620)), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "to " + (account?.label ?: Addr.names(m.toList).ifBlank { "you" }),
                        style = type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (account != null && showTag) {
                        Spacer(Modifier.width(6.dp))
                        AccountTag(Color(account.color))
                    }
                    Icon(
                        painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                        if (expanded) "Hide details" else "Show details",
                        tint = c.ink3,
                        modifier = Modifier.padding(start = 2.dp).size(18.dp),
                    )
                }
            }
            Text(Fmt.rowTime(ctx, m.date), style = type.labelSmall, color = c.ink3, modifier = Modifier.align(Alignment.Top).padding(top = 2.dp))
        }
        if (expanded) {
            Column(Modifier.padding(start = 52.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Detail("From", if (m.fromName.isNotBlank()) "${m.fromName} <${m.fromAddress}>" else m.fromAddress)
                if (m.replyTo.isNotBlank()) Detail("Reply to", m.replyTo)
                if (m.toList.isNotBlank()) Detail("To", m.toList)
                if (m.ccList.isNotBlank()) Detail("Cc", m.ccList)
                Detail("Date", Fmt.full(ctx, m.date))
                if (account != null) Detail("Account", account.email)
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    val c = Bolt.colors
    Column {
        Text(label, style = type.labelSmall, color = c.ink3)
        Text(value, style = type.bodySmall, color = c.ink)
    }
}

@Composable
private fun ImagesHidden(onShow: () -> Unit) {
    val c = Bolt.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.ground)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_image), null, tint = c.ink2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text("Images are hidden so the sender can't track you.", style = type.bodySmall, color = c.ink2, modifier = Modifier.weight(1f))
        TextAction("Show", onShow)
    }
}

@Composable
private fun QuoteToggle(onClick: () -> Unit) {
    val c = Bolt.colors
    Box(
        Modifier
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.raised)
            .clickable(onClickLabel = "Show quoted text", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Text("•••", style = type.labelLarge, color = c.ink2)
    }
}

@Composable
private fun BodySkeleton() {
    val c = Bolt.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(0.92f, 0.98f, 0.85f, 0.6f).forEach { f ->
            Box(Modifier.fillMaxWidth(f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(c.raised))
        }
    }
}

@Composable
private fun AttachmentChip(a: Attachment, busy: Boolean, onClick: () -> Unit) {
    val c = Bolt.colors
    val ext = a.name.substringAfterLast('.', "").lowercase()
    val (bg, fg, icon) = when {
        a.mime == "application/pdf" || ext == "pdf" -> Triple(c.dangerSoft, c.onDangerSoft, R.drawable.ic_picture_as_pdf)
        a.mime.startsWith("image/") -> Triple(c.accentSoft, c.onAccentSoft, R.drawable.ic_image)
        else -> Triple(c.surface, c.ink2, R.drawable.ic_description)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.ground)
            .clickable(onClickLabel = "Open ${a.name}", onClick = onClick)
            .padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, style = type.labelLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val kind = ext.uppercase().ifBlank { a.mime.substringAfter('/').uppercase() }
            Text(listOf(kind, Fmt.size(a.size)).filter { it.isNotBlank() }.joinToString(" · "), style = type.labelSmall, color = c.ink2)
        }
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = c.ink3, strokeWidth = 2.dp)
    }
}
