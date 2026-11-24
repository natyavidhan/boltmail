package app.boltmail.ui.compose

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.boltmail.R
import app.boltmail.mail.DraftFile
import app.boltmail.ui.Fmt
import app.boltmail.ui.LocalNav
import app.boltmail.ui.LocalToaster
import app.boltmail.ui.Route
import app.boltmail.ui.components.AccountTag
import app.boltmail.ui.components.BoltSnackbarHost
import app.boltmail.ui.components.IconBtn
import app.boltmail.ui.components.Pill
import app.boltmail.ui.components.ShadeGroup
import app.boltmail.ui.components.TextAction
import app.boltmail.ui.components.TopBar
import app.boltmail.ui.theme.Bolt
import androidx.compose.material3.MaterialTheme.typography as type

@Composable
fun ComposeScreen(route: Route.Compose) {
    val vm: ComposeViewModel = viewModel(factory = viewModelFactory { initializer { ComposeViewModel(route) } })
    val nav = LocalNav.current
    val toaster = LocalToaster.current
    val ctx = LocalContext.current
    val c = Bolt.colors
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    var fromMenu by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.addFiles(ctx, uris)
    }
    val toFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }

    fun close() {
        if (vm.hasChanges()) confirm = true else nav.pop()
    }
    BackHandler { close() }
    LaunchedEffect(vm.ready) {
        if (vm.ready) runCatching { if (vm.isReply) bodyFocus.requestFocus() else if (vm.to.text.isEmpty()) toFocus.requestFocus() }
    }

    Scaffold(
        containerColor = c.ground,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopBar(onBack = { close() }, backIcon = R.drawable.ic_close, backLabel = "Close") {
                IconBtn(R.drawable.ic_attach_file, "Attach files", { picker.launch("*/*") })
                Pill(
                    "Send",
                    onClick = {
                        if (vm.send()) {
                            toaster.show("Sending…")
                            nav.pop()
                        }
                    },
                    icon = R.drawable.ic_send,
                    primary = true,
                    height = 40.dp,
                    modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                )
            }
        },
        snackbarHost = { BoltSnackbarHost(Modifier.navigationBarsPadding()) },
    ) { pad ->
        Column(
            Modifier
                .padding(top = pad.calculateTopPadding())
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            val current = accounts.firstOrNull { it.id == vm.accountId }
            ShadeGroup {
                if (accounts.size > 1 && current != null) item { shape ->
                    Box {
                        FieldRow(shape, "From", onClick = { fromMenu = true }) {
                            AccountTag(Color(current.color))
                            Spacer(Modifier.width(8.dp))
                            Text(current.email, style = type.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(painterResource(R.drawable.ic_expand_more), null, tint = c.ink3)
                        }
                        DropdownMenu(fromMenu, onDismissRequest = { fromMenu = false }, containerColor = c.surface) {
                            for (a in accounts) DropdownMenuItem(
                                text = { Text(a.email) },
                                leadingIcon = { AccountTag(Color(a.color), size = 10.dp) },
                                onClick = { vm.accountId = a.id; fromMenu = false },
                            )
                        }
                    }
                }
                item { shape ->
                    RecipientRow(shape, "To", vm.to, { vm.update(Field.To, it) }, Modifier.focusRequester(toFocus), vm, Field.To) {
                        if (!vm.showCc) TextAction("Cc Bcc", { vm.showCc = true }, c.ink2)
                    }
                }
                if (vm.suggestFor == Field.To) suggestionItems(vm, Field.To)
                if (vm.showCc) {
                    item { shape -> RecipientRow(shape, "Cc", vm.cc, { vm.update(Field.Cc, it) }, Modifier, vm, Field.Cc) }
                    if (vm.suggestFor == Field.Cc) suggestionItems(vm, Field.Cc)
                    item { shape -> RecipientRow(shape, "Bcc", vm.bcc, { vm.update(Field.Bcc, it) }, Modifier, vm, Field.Bcc) }
                    if (vm.suggestFor == Field.Bcc) suggestionItems(vm, Field.Bcc)
                }
                item { shape ->
                    FieldRow(shape, "Subject") {
                        Plain(vm.subject, { vm.subject = it }, Modifier.weight(1f), singleLine = true, capitalize = true)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            ShadeGroup {
                item { shape ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 260.dp)
                            .clip(shape)
                            .background(c.surface)
                            .clickable(indication = null, interactionSource = null) { runCatching { bodyFocus.requestFocus() } }
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                    ) {
                        Plain(
                            vm.body, { vm.body = it },
                            Modifier.fillMaxWidth().focusRequester(bodyFocus),
                            placeholder = "Write your message",
                            capitalize = true,
                            style = type.bodyLarge,
                        )
                        val sig = current?.signature.orEmpty()
                        if (sig.isNotBlank()) {
                            Text("-- \n$sig", style = type.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 16.dp))
                        }
                    }
                }
                val label = vm.quoteLabel
                if (label != null) item { shape ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(c.surface)
                            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_reply), null, tint = c.ink3, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(label, style = type.bodySmall, color = c.ink2, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextAction("Remove", { vm.quote = null; vm.quoteLabel = null }, c.ink2)
                    }
                }
                for (f in vm.files) item { shape -> FileRow(shape, f) { vm.removeFile(f) } }
                if (vm.loadingFiles > 0) item { shape ->
                    Row(
                        Modifier.fillMaxWidth().clip(shape).background(c.surface).padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = c.ink3, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Attaching…", style = type.bodySmall, color = c.ink2)
                    }
                }
            }
            val warn = vm.error ?: if (vm.totalSize() > 25L * 1024 * 1024) "These attachments add up to ${Fmt.size(vm.totalSize())}. Most mail servers refuse more than 25 MB." else null
            if (warn != null) {
                Text(warn, style = type.bodySmall, color = if (vm.error != null) c.danger else c.ink2, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            }
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = c.surface,
            title = { Text("Discard this message?", style = type.titleLarge) },
            text = { Text("It hasn't been sent, and BoltMail doesn't keep drafts yet.", style = type.bodyMedium, color = c.ink2) },
            confirmButton = { TextAction("Discard", { confirm = false; nav.pop() }, c.danger) },
            dismissButton = { TextAction("Keep writing", { confirm = false }, c.ink) },
        )
    }
}

private fun app.boltmail.ui.components.ShadeScope.suggestionItems(vm: ComposeViewModel, f: Field) {
    for (s in vm.suggestions) item { shape ->
        val c = Bolt.colors
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.raised)
                .clickable { vm.pick(f, s) }
                .padding(start = 84.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Column {
                if (s.name.isNotBlank()) Text(s.name, style = type.bodyMedium, color = c.ink, maxLines = 1)
                Text(s.address, style = type.bodySmall, color = c.ink2, maxLines = 1)
            }
        }
    }
}

@Composable
private fun FieldRow(
    shape: Shape,
    label: String,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val c = Bolt.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(shape)
            .background(c.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = type.bodyMedium, color = c.ink3, modifier = Modifier.width(64.dp))
        content()
    }
}

@Composable
private fun RecipientRow(
    shape: Shape,
    label: String,
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    modifier: Modifier,
    vm: ComposeViewModel,
    field: Field,
    trailing: @Composable () -> Unit = {},
) {
    val c = Bolt.colors
    FieldRow(shape, label) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = type.bodyLarge.copy(color = c.ink),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = modifier
                .weight(1f)
                .padding(vertical = 14.dp)
                .onFocusChanged { if (it.isFocused) vm.suggestFor = field else if (vm.suggestFor == field) vm.suggestFor = null },
        )
        trailing()
    }
}

@Composable
private fun Plain(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
    singleLine: Boolean = false,
    placeholder: String? = null,
    capitalize: Boolean = false,
    style: androidx.compose.ui.text.TextStyle = type.bodyLarge,
) {
    val c = Bolt.colors
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = style.copy(color = c.ink),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
        modifier = modifier.padding(vertical = if (singleLine) 14.dp else 0.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder != null) Text(placeholder, style = style, color = c.ink3)
                inner()
            }
        },
    )
}

@Composable
private fun FileRow(shape: Shape, f: DraftFile, onRemove: () -> Unit) {
    val c = Bolt.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_attach_file), null, tint = c.ink2, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = type.labelLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Fmt.size(f.size), style = type.labelSmall, color = c.ink2)
        }
        IconBtn(R.drawable.ic_close, "Remove ${f.name}", onRemove)
    }
}
