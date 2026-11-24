package dev.natya.boltmail.ui.setup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.natya.boltmail.Graph
import dev.natya.boltmail.R
import dev.natya.boltmail.push.PushHealth
import dev.natya.boltmail.ui.LocalNav
import dev.natya.boltmail.ui.Route
import dev.natya.boltmail.ui.components.IconBtn
import dev.natya.boltmail.ui.components.Notice
import dev.natya.boltmail.ui.components.Pill
import dev.natya.boltmail.ui.components.SectionLabel
import dev.natya.boltmail.ui.components.ShadeGroup
import dev.natya.boltmail.ui.components.ShadeRow
import dev.natya.boltmail.ui.components.TextAction
import dev.natya.boltmail.ui.components.Tone
import dev.natya.boltmail.ui.theme.AccountColors
import dev.natya.boltmail.ui.theme.Bolt
import androidx.compose.material3.MaterialTheme.typography as type

@Composable
fun SetupScreen(first: Boolean) {
    val vm: SetupViewModel = viewModel(factory = viewModelFactory { initializer { SetupViewModel(first) } })
    val nav = LocalNav.current
    val c = Bolt.colors
    val done: () -> Unit = { if (first) nav.reset(Route.Inbox) else nav.pop() }

    BackHandler(enabled = vm.step != Step.Welcome || !first) {
        when (vm.step) {
            Step.Manual, Step.Checking -> vm.step = Step.Email
            Step.Email -> if (first) vm.step = Step.Welcome else nav.pop()
            Step.Name -> vm.step = Step.Email
            Step.Push -> done()
            Step.Welcome -> nav.pop()
        }
    }

    Scaffold(containerColor = c.ground, contentWindowInsets = WindowInsets(0)) { _ ->
        AnimatedContent(
            targetState = vm.step,
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)).togetherWith(fadeOut(tween(120))) },
            label = "setup",
        ) { step ->
            when (step) {
                Step.Welcome -> Welcome { vm.step = Step.Email }
                Step.Email -> EmailStep(vm, showBack = !first) { nav.pop() }
                Step.Checking -> CheckingStep(vm)
                Step.Manual -> ManualStep(vm)
                Step.Name -> NameStep(vm) {
                    vm.finish(needsPush = !PushHealth.check(Graph.app).ok, done = done)
                }
                Step.Push -> PushStep(done)
            }
        }
    }
}

@Composable
private fun Page(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Bolt.colors
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        Box(Modifier.height(56.dp).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
            if (onBack != null) IconBtn(R.drawable.ic_arrow_back, "Back", onBack)
        }
        Text(title, style = type.headlineMedium, color = c.ink, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp))
        if (subtitle != null) {
            Text(subtitle, style = type.bodyMedium, color = c.ink2, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp))
        }
        Spacer(Modifier.height(24.dp))
        content()
    }
}

@Composable
private fun Welcome(onStart: () -> Unit) {
    val c = Bolt.colors
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_bolt), null, tint = c.onAccent, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text("BoltMail", style = type.displaySmall, color = c.ink)
        Spacer(Modifier.height(10.dp))
        Text(
            "Every inbox you own in one place, and every new email on your lock screen the moment it lands.",
            style = type.bodyLarge,
            color = c.ink2,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Works with Hostinger, Gmail and any mail service that supports IMAP. Your mail goes straight from your provider to this phone.",
            style = type.bodySmall,
            color = c.ink3,
        )
        Spacer(Modifier.weight(1.4f))
        Pill("Add an account", onStart, Modifier.fillMaxWidth(), primary = true, height = 52.dp)
    }
}

@Composable
private fun EmailStep(vm: SetupViewModel, showBack: Boolean, onBack: () -> Unit) {
    val c = Bolt.colors
    val ctx = LocalContext.current
    var reveal by remember { mutableStateOf(false) }
    val googleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        vm.onGoogleResult(ctx, res.data)
    }
    val p = vm.provider
    Page(
        "Add an account",
        "Enter your email address. BoltMail finds the right server settings for you.",
        onBack = if (showBack) onBack else null,
    ) {
        ShadeGroup {
            item { shape ->
                InputRow(
                    shape, "Email", vm.email, vm::onEmail,
                    placeholder = "you@example.com",
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                )
            }
            if (p?.blocked == null && !vm.googleFlow) item { shape ->
                InputRow(
                    shape, "Password", vm.password, { vm.password = it; vm.error = null },
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    actions = KeyboardActions(onGo = { vm.submit() }),
                    visual = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                ) {
                    IconBtn(
                        if (reveal) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                        if (reveal) "Hide password" else "Show password",
                        { reveal = !reveal },
                    )
                }
            }
        }
        if (p != null) {
            Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_check_circle), null, tint = c.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(p.name, style = type.labelLarge, color = c.ink2)
            }
        }
        if (p?.blocked != null) {
            ShadeGroup(Modifier.padding(top = 12.dp)) {
                item { shape -> Notice(shape, R.drawable.ic_info, "Can't add this account", p.blocked, tone = Tone.Neutral) }
            }
        } else if (!vm.googleFlow && p?.passwordHint != null) {
            Text(p.passwordHint, style = type.bodySmall, color = c.ink2, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp))
            p.passwordUrl?.let { url ->
                TextAction("Create one", { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, modifier = Modifier.padding(start = 14.dp))
            }
        }
        vm.error?.let {
            Text(it, style = type.bodySmall, color = c.danger, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp))
        }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (vm.googleFlow) {
                GoogleButton(busy = vm.busy) {
                    vm.google(ctx as Activity) { googleLauncher.launch(it) }
                }
                TextAction("Use an app password instead", { vm.appPassword = true; vm.error = null }, c.ink2, Modifier.padding(top = 8.dp))
            } else if (p?.blocked == null) {
                Pill("Sign in", { vm.submit() }, Modifier.fillMaxWidth(), primary = true, height = 52.dp, enabled = vm.emailValid && vm.password.isNotEmpty())
                if (p?.google == true) {
                    TextAction("Sign in with Google instead", { vm.appPassword = false; vm.error = null }, c.ink2, Modifier.padding(top = 8.dp))
                }
            }
            TextAction("Enter server settings manually", { vm.openManual() }, c.ink2, Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun GoogleButton(busy: Boolean, onClick: () -> Unit) {
    val c = Bolt.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(26.dp))
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = c.ink3, strokeWidth = 2.dp)
        } else {
            Icon(painterResource(R.drawable.ic_google), null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text("Continue with Google", style = type.labelLarge, color = c.ink)
    }
}

@Composable
private fun CheckingStep(vm: SetupViewModel) {
    Page("Setting up ${vm.email}", "This usually takes a few seconds.") {
        val labels = listOf("Finding your mail server", "Signing in", "Checking outgoing mail")
        ShadeGroup {
            labels.forEachIndexed { i, l ->
                item { shape -> CheckRow(shape, l, vm.checks[i]) }
            }
        }
    }
}

@Composable
private fun CheckRow(shape: Shape, label: String, state: Check) {
    val c = Bolt.colors
    ShadeRow(shape, label, titleColor = if (state == Check.Waiting) c.ink3 else c.ink) {
        when (state) {
            Check.Running -> CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
            Check.Done -> Icon(painterResource(R.drawable.ic_check_circle), "Done", tint = c.accent)
            Check.Failed -> Icon(painterResource(R.drawable.ic_error), "Failed", tint = c.danger)
            Check.Waiting -> Unit
        }
    }
}

@Composable
private fun ManualStep(vm: SetupViewModel) {
    val c = Bolt.colors
    var reveal by remember { mutableStateOf(false) }
    Page("Server settings", "Your email host lists these in its help pages, usually under IMAP and SMTP.", onBack = { vm.step = Step.Email }) {
        vm.error?.let {
            Text(it, style = type.bodySmall, color = c.danger, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp))
        }
        ShadeGroup {
            item { shape -> InputRow(shape, "Email", vm.email, vm::onEmail, keyboard = KeyboardOptions(keyboardType = KeyboardType.Email)) }
            item { shape -> InputRow(shape, "Username", vm.username, { vm.username = it }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Email)) }
            item { shape ->
                InputRow(
                    shape, "Password", vm.password, { vm.password = it },
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visual = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                ) {
                    IconBtn(if (reveal) R.drawable.ic_visibility_off else R.drawable.ic_visibility, "Show password", { reveal = !reveal })
                }
            }
        }
        SectionLabel("Incoming (IMAP)")
        ShadeGroup {
            item { shape -> InputRow(shape, "Server", vm.imapHost, { vm.imapHost = it }, placeholder = "imap.example.com", keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri)) }
            item { shape -> InputRow(shape, "Port", vm.imapPort, { vm.imapPort = it.filter(Char::isDigit) }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number)) }
            item { shape -> SecurityRow(shape, vm.imapSecurity, vm::chooseImapSecurity) }
        }
        SectionLabel("Outgoing (SMTP)")
        ShadeGroup {
            item { shape -> InputRow(shape, "Server", vm.smtpHost, { vm.smtpHost = it }, placeholder = "smtp.example.com", keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri)) }
            item { shape -> InputRow(shape, "Port", vm.smtpPort, { vm.smtpPort = it.filter(Char::isDigit) }, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number)) }
            item { shape -> SecurityRow(shape, vm.smtpSecurity, vm::chooseSmtpSecurity) }
        }
        Spacer(Modifier.height(24.dp))
        Pill("Sign in", { vm.submitManual() }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), primary = true, height = 52.dp)
    }
}

@Composable
private fun SecurityRow(shape: Shape, value: String, onChange: (String) -> Unit) {
    val c = Bolt.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((key, label) in SetupViewModel.SECURITY) {
            val sel = key == value
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (sel) c.accentSoft else c.ground)
                    .clickable(role = Role.RadioButton) { onChange(key) }
                    .semantics { selected = sel },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = type.labelLarge, color = if (sel) c.onAccentSoft else c.ink2)
            }
        }
    }
}

@Composable
private fun NameStep(vm: SetupViewModel, onDone: () -> Unit) {
    Page("You're in", "Two last details. You can change them later in Settings.") {
        ShadeGroup {
            item { shape ->
                InputRow(
                    shape, "Your name", vm.name, { vm.name = it }, placeholder = "Shown to people you email",
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
            }
            item { shape ->
                InputRow(
                    shape, "Label", vm.label, { vm.label = it }, placeholder = "e.g. Work",
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                )
            }
            item { shape -> ColorRow(shape, vm.color) { vm.color = it } }
        }
        Spacer(Modifier.height(24.dp))
        Pill("Done", onDone, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), primary = true, height = 52.dp, enabled = !vm.busy)
    }
}

@Composable
fun ColorRow(shape: Shape, selected: Int, onPick: (Int) -> Unit) {
    val c = Bolt.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
    ) {
        Text("Color", style = type.bodyMedium, color = c.ink3)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (col in AccountColors) {
                val sel = col == selected
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(col))
                        .clickable(role = Role.RadioButton, onClickLabel = "Pick color") { onPick(col) }
                        .semantics { this.selected = sel; contentDescription = "Account color" },
                    contentAlignment = Alignment.Center,
                ) {
                    if (sel) Icon(painterResource(R.drawable.ic_check), null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun PushStep(done: () -> Unit) {
    val ctx = LocalContext.current
    val c = Bolt.colors
    var health by remember { mutableStateOf(PushHealth.check(ctx)) }
    LifecycleResumeEffect(Unit) {
        health = PushHealth.check(ctx)
        onPauseOrDispose {}
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { health = PushHealth.check(ctx) }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { health = PushHealth.check(ctx) }
    Page("Get mail instantly", "BoltMail keeps a quiet connection to your mail server. Android needs two permissions for that.") {
        ShadeGroup {
            item { shape ->
                ShadeRow(
                    shape, "Notifications", subtitle = "So new mail can reach your lock screen",
                    icon = R.drawable.ic_notifications, iconTint = if (health.notifications) c.accent else c.ink2,
                ) {
                    if (health.notifications) {
                        Icon(painterResource(R.drawable.ic_check_circle), "Allowed", tint = c.accent)
                    } else {
                        TextAction("Allow", {
                            if (Build.VERSION.SDK_INT >= 33 && !Graph.prefs.askedNotifications) {
                                Graph.prefs.askedNotifications = true
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                settings.launch(PushHealth.notificationSettingsIntent(ctx))
                            }
                        })
                    }
                }
            }
            item { shape ->
                ShadeRow(
                    shape, "Run in the background", subtitle = "Stops your phone from holding mail back to save battery",
                    icon = R.drawable.ic_bolt, iconTint = if (health.unrestricted) c.accent else c.ink2,
                ) {
                    if (health.unrestricted) {
                        Icon(painterResource(R.drawable.ic_check_circle), "Allowed", tint = c.accent)
                    } else {
                        TextAction("Allow", { runCatching { settings.launch(PushHealth.unrestrictIntent(ctx)) } })
                    }
                }
            }
        }
        Text(
            "On Xiaomi, OnePlus, Realme and similar phones, also allow Autostart for BoltMail in the phone's settings.",
            style = type.bodySmall, color = c.ink3,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
        )
        Spacer(Modifier.height(24.dp))
        Pill(if (health.ok) "Go to inbox" else "Skip for now", done, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), primary = health.ok, height = 52.dp)
    }
}

@Composable
fun InputRow(
    shape: Shape,
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String? = null,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    actions: KeyboardActions = KeyboardActions.Default,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = Bolt.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(c.surface)
            .padding(start = 18.dp, end = if (trailing != null) 4.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = type.bodyMedium, color = c.ink3, modifier = Modifier.width(96.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = type.bodyLarge.copy(color = c.ink),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = keyboard,
            keyboardActions = actions,
            visualTransformation = visual,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 16.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = type.bodyLarge, color = c.ink3, maxLines = 1)
                    inner()
                }
            },
        )
        trailing?.invoke()
    }
}
