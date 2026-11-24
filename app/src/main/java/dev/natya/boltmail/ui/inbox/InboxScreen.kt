package dev.natya.boltmail.ui.inbox

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.natya.boltmail.Graph
import dev.natya.boltmail.R
import dev.natya.boltmail.data.Account
import dev.natya.boltmail.data.ERR_AUTH
import dev.natya.boltmail.data.ERR_SERVER
import dev.natya.boltmail.data.Folder
import dev.natya.boltmail.data.MessageRow
import dev.natya.boltmail.data.Op
import dev.natya.boltmail.data.Role
import dev.natya.boltmail.mail.Mail
import dev.natya.boltmail.push.PushHealth
import dev.natya.boltmail.ui.Fmt
import dev.natya.boltmail.ui.LocalNav
import dev.natya.boltmail.ui.LocalToaster
import dev.natya.boltmail.ui.Route
import dev.natya.boltmail.ui.components.AccountTag
import dev.natya.boltmail.ui.components.BoltSnackbarHost
import dev.natya.boltmail.ui.components.IconBtn
import dev.natya.boltmail.ui.components.Notice
import dev.natya.boltmail.ui.components.Seam
import dev.natya.boltmail.ui.components.SectionLabel
import dev.natya.boltmail.ui.components.ShadeGroup
import dev.natya.boltmail.ui.components.TextAction
import dev.natya.boltmail.ui.components.Tone
import dev.natya.boltmail.ui.components.shadeShape
import dev.natya.boltmail.ui.theme.Bolt
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme.typography as type

private class Group(val label: String, val rows: List<MessageRow>)

private fun group(rows: List<MessageRow>): List<Group> {
    val now = System.currentTimeMillis()
    val out = mutableListOf<Group>()
    var label: String? = null
    var bucket = mutableListOf<MessageRow>()
    for (r in rows) {
        val l = Fmt.groupLabel(r.date, now)
        if (l != label) {
            if (label != null) out += Group(label, bucket)
            label = l
            bucket = mutableListOf()
        }
        bucket += r
    }
    if (label != null) out += Group(label, bucket)
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(vm: InboxViewModel = viewModel()) {
    val nav = LocalNav.current
    val toaster = LocalToaster.current
    val ctx = LocalContext.current
    val c = Bolt.colors
    val rows by vm.rows.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val unread by vm.unread.collectAsStateWithLifecycle()
    val outbox by vm.outbox.collectAsStateWithLifecycle()
    val mailbox by vm.mailbox.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val snoozedUntil by Graph.prefs.pushHintSnoozedUntil.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(false) }
    var health by remember { mutableStateOf(PushHealth.check(ctx)) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LifecycleResumeEffect(Unit) {
        Graph.inboxVisible = true
        health = PushHealth.check(ctx)
        vm.refresh(force = false)
        onPauseOrDispose { Graph.inboxVisible = false }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        health = PushHealth.check(ctx)
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        health = PushHealth.check(ctx)
    }
    BackHandler(enabled = query != null) { vm.query.value = null }

    val byId = remember(accounts) { accounts.associateBy { it.id } }
    val folder = folders.firstOrNull { it.id == mailbox.folderId }
    val inboxes = folders.filter { it.role == Role.INBOX }
    val unreadCount = if (mailbox.all) inboxes.sumOf { unread[it.id] ?: 0 } else unread[mailbox.folderId] ?: 0
    val acct = byId[folder?.accountId ?: mailbox.accountId]
    val scopeLabel = when {
        mailbox.all && accounts.size > 1 -> "All accounts"
        acct != null -> if (accounts.size > 1) acct.label else acct.email
        accounts.size == 1 -> accounts[0].email
        else -> ""
    }
    val subtitle = scopeLabel + if (unreadCount > 0) " · $unreadCount unread" else ""
    val showTags = accounts.size > 1 && mailbox.all
    val groups = remember(rows) { group(rows.orEmpty()) }
    val canArchive = folder == null || Mail.canArchive(folder.role)
    val searching = query != null

    // Fetch older mail as the end of the list comes into view.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && total > 0 && last >= total - 4) vm.loadMore() }
    }

    Scaffold(
        containerColor = c.ground,
        topBar = {
            if (searching) {
                SearchHeader(query.orEmpty(), onChange = { vm.query.value = it }, onClose = { vm.query.value = null })
            } else {
                InboxHeader(
                    title = folder?.name ?: "Inbox",
                    subtitle = subtitle,
                    onSwitch = { sheet = true },
                    onSearch = { vm.query.value = "" },
                    onSettings = { nav.push(Route.Settings) },
                )
            }
        },
        floatingActionButton = {
            if (!searching) {
                FloatingActionButton(
                    onClick = { nav.push(Route.Compose()) },
                    shape = RoundedCornerShape(19.dp),
                    containerColor = c.accentSoft,
                    contentColor = c.onAccentSoft,
                    elevation = FloatingActionButtonDefaults.elevation(3.dp, 4.dp, 3.dp, 3.dp),
                    modifier = Modifier.semantics { contentDescription = "Write a message" },
                ) {
                    Icon(painterResource(R.drawable.ic_edit), null)
                }
            }
        },
        snackbarHost = { BoltSnackbarHost() },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier
                .padding(top = pad.calculateTopPadding())
                .fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = pad.calculateBottomPadding() + 96.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item("notices") {
                    Notices(
                        accounts = accounts,
                        health = health,
                        showHealth = !searching && accounts.any { it.notify } && !health.ok && System.currentTimeMillis() > snoozedUntil,
                        outbox = outbox,
                        onAllowNotifications = {
                            if (PushHealth.needsRuntimePermission() && !Graph.prefs.askedNotifications) {
                                Graph.prefs.askedNotifications = true
                                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                settingsLauncher.launch(PushHealth.notificationSettingsIntent(ctx))
                            }
                        },
                        onAllowBackground = { runCatching { settingsLauncher.launch(PushHealth.unrestrictIntent(ctx)) } },
                        onSnooze = { Graph.prefs.snoozePushHint(System.currentTimeMillis() + 3 * 24 * 3600_000L) },
                        onFixAccount = { nav.push(Route.Account(it)) },
                        onRetry = { vm.refresh() },
                        onRetrySend = { id -> scope.launch { Mail.retrySend(id) } },
                        onDiscardSend = { id -> scope.launch { Mail.discardSend(id) } },
                    )
                }
                val list = rows
                when {
                    list == null -> Unit
                    list.isEmpty() -> item("empty") {
                        EmptyState(
                            searching = searching,
                            syncing = !searching && (mailbox.all && vm.syncing() || folder?.synced == false || refreshing),
                            inbox = folder == null || folder.role == Role.INBOX,
                            folderName = folder?.name,
                        )
                    }
                    else -> {
                        for (g in groups) {
                            item(key = "h:${g.label}", contentType = "header") {
                                SectionLabel(g.label, Modifier.animateItem())
                            }
                            itemsIndexed(g.rows, key = { _, r -> r.id }, contentType = { _, _ -> "row" }) { i, r ->
                                SwipeMailRow(
                                    row = r,
                                    shape = shadeShape(i, g.rows.size),
                                    tag = if (showTags) byId[r.accountId]?.let { Color(it.color) } else null,
                                    canArchive = canArchive,
                                    onOpen = { nav.push(Route.Message(r.id)) },
                                    onArchive = { scope.launch { toaster.undoable("Archived", Mail.archive(r.id)) } },
                                    onDelete = { scope.launch { toaster.undoable("Deleted", Mail.delete(r.id)) } },
                                    modifier = Modifier
                                        .animateItem()
                                        .padding(horizontal = 10.dp)
                                        .padding(bottom = if (i < g.rows.lastIndex) Seam else 0.dp),
                                )
                            }
                        }
                        item("footer") {
                            Footer(
                                loading = loadingMore,
                                end = !searching && list.size < vm.limitValue && vm.remoteTargets().isEmpty() && list.size > 12,
                            )
                        }
                    }
                }
            }
        }
    }

    if (sheet) {
        MailboxSheet(
            accounts = accounts,
            folders = folders,
            unread = unread,
            current = mailbox,
            onPick = {
                sheet = false
                vm.select(it)
                scope.launch { listState.scrollToItem(0) }
            },
            onManage = {
                sheet = false
                nav.push(Route.Settings)
            },
            onDismiss = { sheet = false },
        )
    }
}

@Composable
private fun InboxHeader(title: String, subtitle: String, onSwitch: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    val c = Bolt.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.ground)
            .statusBarsPadding()
            .padding(bottom = 2.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClickLabel = "Switch mailbox", onClick = onSwitch)
                        .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = type.headlineMedium,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        painterResource(R.drawable.ic_keyboard_arrow_down),
                        null,
                        tint = c.ink2,
                        modifier = Modifier.padding(top = 4.dp).size(26.dp),
                    )
                }
            }
            IconBtn(R.drawable.ic_search, "Search", onSearch)
            IconBtn(R.drawable.ic_settings, "Settings", onSettings)
        }
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = type.bodySmall,
                color = c.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun SearchHeader(query: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val c = Bolt.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.ground)
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(c.surface)
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(R.drawable.ic_arrow_back, "Close search", onClose)
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = type.bodyLarge.copy(color = c.ink),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search mail on this phone", style = type.bodyLarge, color = c.ink3)
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) IconBtn(R.drawable.ic_close, "Clear search", { onChange("") })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeMailRow(
    row: MessageRow,
    shape: Shape,
    tag: Color?,
    canArchive: Boolean,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var handled by remember(row.id) { mutableStateOf(false) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (handled) return@rememberSwipeToDismissBoxState false
            when (v) {
                SwipeToDismissBoxValue.StartToEnd -> if (canArchive) {
                    handled = true; onArchive(); true
                } else {
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    handled = true; onDelete(); true
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThreshold = { it * 0.3f },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = canArchive,
        backgroundContent = { SwipeBackground(state.dismissDirection, shape) },
    ) {
        MailRow(row, shape, tag, onOpen)
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, shape: Shape) {
    val c = Bolt.colors
    val start = direction == SwipeToDismissBoxValue.StartToEnd
    val end = direction == SwipeToDismissBoxValue.EndToStart
    val bg = when {
        start -> c.accentSoft
        end -> c.dangerSoft
        else -> Color.Transparent
    }
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(bg)
            .padding(horizontal = 26.dp),
        contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        if (start) Icon(painterResource(R.drawable.ic_archive), "Archive", tint = c.onAccentSoft)
        if (end) Icon(painterResource(R.drawable.ic_delete), "Delete", tint = c.onDangerSoft)
    }
}

@Composable
fun MailRow(row: MessageRow, shape: Shape, tag: Color?, onClick: () -> Unit) {
    val c = Bolt.colors
    val ctx = LocalContext.current
    val unread = !row.seen
    val sender = row.fromName.ifBlank { row.fromAddress.ifBlank { "(no sender)" } }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .clickable(onClick = onClick)
            .padding(start = 30.dp, end = 16.dp, top = 12.dp, bottom = 13.dp),
    ) {
        if (unread) {
            Box(
                Modifier
                    .offset(x = (-17).dp, y = 7.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(c.accent),
            )
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sender,
                    style = type.titleMedium.copy(fontWeight = FontWeight(if (unread) 680 else 450)),
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    Fmt.rowTime(ctx, row.date),
                    style = type.labelSmall.copy(fontWeight = FontWeight(if (unread) 600 else 400)),
                    color = if (unread) c.accent else c.ink3,
                )
                if (tag != null) {
                    Spacer(Modifier.width(8.dp))
                    AccountTag(tag)
                }
            }
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.subject.ifBlank { "(no subject)" },
                    style = type.bodyMedium.copy(fontWeight = FontWeight(if (unread) 560 else 400)),
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (row.hasAttachments) {
                    Icon(
                        painterResource(R.drawable.ic_attach_file),
                        "Has attachments",
                        tint = c.ink3,
                        modifier = Modifier.padding(start = 6.dp).size(16.dp),
                    )
                }
            }
            if (row.snippet.isNotBlank()) {
                Text(
                    row.snippet,
                    style = type.bodySmall,
                    color = c.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Notices(
    accounts: List<Account>,
    health: PushHealth.State,
    showHealth: Boolean,
    outbox: List<Op>,
    onAllowNotifications: () -> Unit,
    onAllowBackground: () -> Unit,
    onSnooze: () -> Unit,
    onFixAccount: (Long) -> Unit,
    onRetry: () -> Unit,
    onRetrySend: (Long) -> Unit,
    onDiscardSend: (Long) -> Unit,
) {
    val broken = accounts.filter { it.errorKind == ERR_AUTH }
    val unreachable = accounts.filter { it.errorKind == ERR_SERVER }
    val failed = outbox.filter { it.error != null }
    val waiting = outbox.filter { it.error == null }
    if (!showHealth && broken.isEmpty() && unreachable.isEmpty() && outbox.isEmpty()) return
    ShadeGroup(Modifier.padding(top = 10.dp)) {
        if (showHealth) item { shape ->
            if (!health.notifications) {
                Notice(
                    shape, R.drawable.ic_notifications, "Turn on notifications",
                    "BoltMail can't tell you about new mail until notifications are allowed.",
                    action = "Allow", onAction = onAllowNotifications, secondary = "Not now", onSecondary = onSnooze,
                )
            } else {
                Notice(
                    shape, R.drawable.ic_bolt, "Let mail arrive instantly",
                    "Allow BoltMail to run in the background, or your phone may hold new mail back for minutes.",
                    action = "Allow", onAction = onAllowBackground, secondary = "Not now", onSecondary = onSnooze,
                )
            }
        }
        for (a in broken) item { shape ->
            Notice(
                shape, R.drawable.ic_error, "${a.label} needs you to sign in again", a.error,
                tone = Tone.Danger, action = "Sign in", onAction = { onFixAccount(a.id) },
            )
        }
        for (a in unreachable) item { shape ->
            Notice(
                shape, R.drawable.ic_cloud_off, "Can't reach ${a.label}", a.error,
                tone = Tone.Neutral, action = "Try again", onAction = onRetry,
            )
        }
        for (op in failed) item { shape ->
            Notice(
                shape, R.drawable.ic_error, "Couldn't send “${op.title}”", op.error,
                tone = Tone.Danger, action = "Try again", onAction = { onRetrySend(op.id) },
                secondary = "Discard", onSecondary = { onDiscardSend(op.id) },
            )
        }
        if (waiting.isNotEmpty()) item { shape ->
            val n = waiting.size
            Notice(
                shape, R.drawable.ic_outbox,
                if (n == 1) "Sending “${waiting[0].title}”" else "Sending $n messages",
                if (waiting.any { it.attempts > 0 }) "It'll go out as soon as there's a connection." else null,
                tone = Tone.Neutral,
            )
        }
    }
}

@Composable
private fun EmptyState(searching: Boolean, syncing: Boolean, inbox: Boolean, folderName: String?) {
    val c = Bolt.colors
    if (syncing) {
        SkeletonStack()
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!searching) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                Icon(painterResource(if (inbox) R.drawable.ic_bolt else R.drawable.ic_folder), null, tint = c.onAccentSoft)
            }
            Spacer(Modifier.height(16.dp))
        }
        val (title, body) = when {
            searching -> "No matches" to "Search looks through the mail stored on this phone."
            inbox -> "All clear" to "New mail shows up here, and on your lock screen, the moment it arrives."
            else -> "Nothing in ${folderName ?: "this folder"}" to null
        }
        Text(title, style = type.titleLarge, color = c.ink, textAlign = TextAlign.Center)
        if (body != null) {
            Text(body, style = type.bodyMedium, color = c.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Placeholder stack while the first sync runs. */
@Composable
private fun SkeletonStack() {
    val c = Bolt.colors
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha",
    )
    Column {
        SectionLabel("Fetching your mail…")
        ShadeGroup(Modifier.alpha(pulse)) {
            repeat(6) { i ->
                item { shape ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(c.surface)
                            .padding(start = 30.dp, end = 16.dp, top = 15.dp, bottom = 15.dp),
                    ) {
                        Bar(if (i % 2 == 0) 0.42f else 0.3f, 12.dp)
                        Spacer(Modifier.height(9.dp))
                        Bar(if (i % 3 == 0) 0.7f else 0.56f, 10.dp)
                        Spacer(Modifier.height(8.dp))
                        Bar(0.86f, 10.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, height: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(Bolt.colors.raised),
    )
}

@Composable
private fun Footer(loading: Boolean, end: Boolean) {
    val c = Bolt.colors
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 12.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(22.dp), color = c.ink3, strokeWidth = 2.dp)
            end -> Text("That's everything", style = type.labelSmall, color = c.ink3)
        }
    }
}

// ---------------------------------------------------------------- mailbox switcher

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailboxSheet(
    accounts: List<Account>,
    folders: List<Folder>,
    unread: Map<Long, Int>,
    current: Mailbox,
    onPick: (Mailbox) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Bolt.colors
    val inboxes = folders.filter { it.role == Role.INBOX }
    var focus by remember {
        mutableLongStateOf(
            folders.firstOrNull { it.id == current.folderId }?.accountId ?: accounts.firstOrNull()?.id ?: 0L,
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = c.ground,
        contentColor = c.ink,
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.ink3.copy(alpha = 0.5f)) },
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Text("Mailboxes", style = type.titleLarge, color = c.ink, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp))
            ShadeGroup {
                if (accounts.size > 1) item { shape ->
                    MailboxRow(shape, "All inboxes", null, inboxes.sumOf { unread[it.id] ?: 0 }, current.all, icon = R.drawable.ic_inbox) {
                        onPick(Mailbox())
                    }
                }
                for (a in accounts) {
                    val inbox = inboxes.firstOrNull { it.accountId == a.id }
                    item { shape ->
                        MailboxRow(
                            shape, a.label, a.email, inbox?.let { unread[it.id] } ?: 0,
                            selected = inbox != null && current.folderId == inbox.id && (accounts.size > 1 || !current.all) ||
                                accounts.size == 1 && current.all,
                            tag = Color(a.color),
                        ) {
                            if (inbox != null) onPick(if (accounts.size == 1) Mailbox() else Mailbox(a.id, inbox.id))
                        }
                    }
                }
            }
            if (accounts.isNotEmpty()) {
                SectionLabel("Folders")
                if (accounts.size > 1) {
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (a in accounts) {
                            FilterChip(
                                selected = focus == a.id,
                                onClick = { focus = a.id },
                                label = { Text(a.label) },
                                leadingIcon = { AccountTag(Color(a.color)) },
                                shape = RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = c.surface,
                                    labelColor = c.ink2,
                                    selectedContainerColor = c.accentSoft,
                                    selectedLabelColor = c.onAccentSoft,
                                ),
                                border = null,
                            )
                        }
                    }
                }
                val others = folders.filter { it.accountId == focus && it.role != Role.INBOX }
                if (others.isEmpty()) {
                    Text(
                        "Folders show up after the first sync.",
                        style = type.bodySmall, color = c.ink3,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                } else {
                    ShadeGroup {
                        for (f in others) item { shape ->
                            val count = if (f.role == null || f.role == Role.SPAM) unread[f.id] ?: 0 else 0
                            MailboxRow(shape, f.name, null, count, current.folderId == f.id, icon = folderIcon(f.role)) {
                                onPick(Mailbox(f.accountId, f.id))
                            }
                        }
                    }
                }
            }
            TextAction("Manage accounts", onManage, c.accent, Modifier.padding(start = 14.dp, top = 12.dp))
        }
    }
}

private fun folderIcon(role: String?) = when (role) {
    Role.INBOX -> R.drawable.ic_inbox
    Role.SENT -> R.drawable.ic_send
    Role.DRAFTS -> R.drawable.ic_drafts
    Role.ARCHIVE, Role.ALL -> R.drawable.ic_archive
    Role.SPAM -> R.drawable.ic_report
    Role.TRASH -> R.drawable.ic_delete
    else -> R.drawable.ic_folder
}

@Composable
private fun MailboxRow(
    shape: Shape,
    title: String,
    subtitle: String?,
    count: Int,
    selected: Boolean,
    icon: Int? = null,
    tag: Color? = null,
    onClick: () -> Unit,
) {
    val c = Bolt.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (selected) c.accentSoft else c.surface)
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, tint = if (selected) c.onAccentSoft else c.ink2, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
        } else if (tag != null) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { AccountTag(tag, size = 10.dp) }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.titleMedium, color = if (selected) c.onAccentSoft else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = type.bodySmall, color = if (selected) c.onAccentSoft else c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (count > 0) {
            Text("$count", style = type.labelMedium, color = if (selected) c.onAccentSoft else c.ink2)
        }
    }
}
