package app.boltmail.ui.settings

import android.Manifest
import android.app.Activity
import android.app.UiModeManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.boltmail.BuildConfig
import app.boltmail.Graph
import app.boltmail.R
import app.boltmail.data.Account
import app.boltmail.data.ERR_AUTH
import app.boltmail.data.Prefs
import app.boltmail.data.SEC_NONE
import app.boltmail.data.SEC_SSL
import app.boltmail.data.Secrets
import app.boltmail.data.isGoogle
import app.boltmail.mail.GoogleAuth
import app.boltmail.mail.Mail
import app.boltmail.mail.MailErrors
import app.boltmail.push.Notifier
import app.boltmail.push.PushHealth
import app.boltmail.ui.LocalNav
import app.boltmail.ui.LocalToaster
import app.boltmail.ui.Route
import app.boltmail.ui.components.BoltSnackbarHost
import app.boltmail.ui.components.Notice
import app.boltmail.ui.components.SectionLabel
import app.boltmail.ui.components.ShadeGroup
import app.boltmail.ui.components.ShadeRow
import app.boltmail.ui.components.TextAction
import app.boltmail.ui.components.Tone
import app.boltmail.ui.components.TopBar
import app.boltmail.ui.setup.ColorRow
import app.boltmail.ui.setup.InputRow
import app.boltmail.ui.theme.Bolt
import com.google.android.gms.auth.api.identity.Identity
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.MaterialTheme.typography as type

@Composable
fun SettingsScreen() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val c = Bolt.colors
    val accounts by Graph.dao.accountsFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val theme by Graph.prefs.theme.collectAsStateWithLifecycle()
    val images by Graph.prefs.loadImages.collectAsStateWithLifecycle()
    var health by remember { mutableStateOf(PushHealth.check(ctx)) }
    LifecycleResumeEffect(Unit) {
        health = PushHealth.check(ctx)
        onPauseOrDispose {}
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { health = PushHealth.check(ctx) }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { health = PushHealth.check(ctx) }

    Scaffold(
        containerColor = c.ground,
        contentWindowInsets = WindowInsets(0),
        topBar = { TopBar("Settings", onBack = { nav.pop() }) },
        snackbarHost = { BoltSnackbarHost(Modifier.navigationBarsPadding()) },
    ) { pad ->
        Column(
            Modifier
                .padding(top = pad.calculateTopPadding())
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            SectionLabel("Accounts")
            ShadeGroup {
                for (a in accounts) item { shape ->
                    ShadeRow(
                        shape, a.label,
                        subtitle = if (a.errorKind == ERR_AUTH) "Needs you to sign in again" else a.email,
                        tag = Color(a.color),
                        onClick = { nav.push(Route.Account(a.id)) },
                    ) {
                        Icon(
                            painterResource(if (a.errorKind == ERR_AUTH) R.drawable.ic_error else R.drawable.ic_chevron_right),
                            null,
                            tint = if (a.errorKind == ERR_AUTH) c.danger else c.ink3,
                        )
                    }
                }
                item { shape ->
                    ShadeRow(shape, "Add account", icon = R.drawable.ic_add, iconTint = c.accent, titleColor = c.accent, onClick = {
                        nav.push(Route.Setup(first = false))
                    })
                }
            }

            SectionLabel("Instant notifications")
            ShadeGroup {
                item { shape ->
                    ShadeRow(shape, "Notifications", subtitle = if (health.notifications) "Allowed" else "Off: new mail can't reach you", icon = R.drawable.ic_notifications) {
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
                        shape, "Run in the background",
                        subtitle = if (health.unrestricted) "Allowed: mail arrives in seconds" else "Restricted: your phone may delay mail",
                        icon = R.drawable.ic_bolt,
                    ) {
                        if (health.unrestricted) {
                            Icon(painterResource(R.drawable.ic_check_circle), "Allowed", tint = c.accent)
                        } else {
                            TextAction("Allow", { runCatching { settings.launch(PushHealth.unrestrictIntent(ctx)) } })
                        }
                    }
                }
                item { shape ->
                    ShadeRow(
                        shape, "Sounds and vibration", subtitle = "Each account has its own setting in Android",
                        icon = R.drawable.ic_open_in_new,
                        onClick = { runCatching { ctx.startActivity(PushHealth.notificationSettingsIntent(ctx)) } },
                    )
                }
            }

            SectionLabel("Appearance")
            ShadeGroup {
                item { shape -> ThemeRow(shape, theme) { setTheme(ctx as Activity, it) } }
            }

            SectionLabel("Reading")
            ShadeGroup {
                item { shape ->
                    ShadeRow(
                        shape, "Show images automatically",
                        subtitle = "Images can tell senders when you open their email",
                        onClick = { Graph.prefs.setLoadImages(!images) },
                    ) {
                        BoltSwitch(images) { Graph.prefs.setLoadImages(it) }
                    }
                }
            }

            SectionLabel("About")
            ShadeGroup {
                item { shape ->
                    ShadeRow(
                        shape, "BoltMail ${BuildConfig.VERSION_NAME}",
                        subtitle = "Mail goes straight from your provider to this phone. No BoltMail servers, no tracking.",
                    )
                }
            }
        }
    }
}

private fun setTheme(activity: Activity, value: Int) {
    Graph.prefs.setTheme(value)
    if (Build.VERSION.SDK_INT >= 31) {
        val mode = when (value) {
            Prefs.THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO
            Prefs.THEME_DARK -> UiModeManager.MODE_NIGHT_YES
            else -> UiModeManager.MODE_NIGHT_AUTO
        }
        activity.getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
    }
}

@Composable
private fun ThemeRow(shape: Shape, value: Int, onChange: (Int) -> Unit) {
    val c = Bolt.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
    ) {
        Text("Theme", style = type.titleMedium, color = c.ink)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((v, label) in listOf(Prefs.THEME_SYSTEM to "System", Prefs.THEME_LIGHT to "Light", Prefs.THEME_DARK to "Dark")) {
                val sel = v == value
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) c.accentSoft else c.ground)
                        .clickable(role = Role.RadioButton) { onChange(v) }
                        .semantics { selected = sel },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = type.labelLarge, color = if (sel) c.onAccentSoft else c.ink2)
                }
            }
        }
    }
}

@Composable
fun BoltSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Bolt.colors
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onAccent,
            checkedTrackColor = c.accent,
            uncheckedThumbColor = c.ink3,
            uncheckedTrackColor = c.ground,
            uncheckedBorderColor = c.line,
        ),
    )
}

@Composable
fun AccountScreen(id: Long) {
    val nav = LocalNav.current
    val toaster = LocalToaster.current
    val ctx = LocalContext.current
    val c = Bolt.colors
    val account by Graph.dao.accountFlow(id).collectAsStateWithLifecycle(initialValue = null)
    val a = account
    if (a == null) {
        Box(Modifier.fillMaxSize().background(c.ground))
        return
    }
    var name by rememberSaveable(a.id) { mutableStateOf(a.name) }
    var label by rememberSaveable(a.id) { mutableStateOf(a.label) }
    var color by rememberSaveable(a.id) { mutableIntStateOf(a.color) }
    var signature by rememberSaveable(a.id) { mutableStateOf(a.signature) }
    var password by remember { mutableStateOf("") }
    var pwError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Details save themselves shortly after you stop typing.
    LaunchedEffect(name, label, color, signature) {
        delay(500)
        val fresh = withContext(Dispatchers.IO) { Graph.dao.account(id) } ?: return@LaunchedEffect
        val next = fresh.copy(name = name.trim(), label = label.trim().ifBlank { fresh.label }, color = color, signature = signature)
        if (next != fresh) Mail.updateAccount(next)
    }

    val googleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            busy = true
            try {
                Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(res.data)
                withContext(Dispatchers.IO) { Mail.verifyImap(a) }
                withContext(Dispatchers.IO) { Mail.clearError(a.id) }
                toaster.show("Signed in")
            } catch (t: Throwable) {
                pwError = MailErrors.describe(GoogleAuth.map(t))
            } finally {
                busy = false
            }
        }
    }

    fun reauthGoogle() {
        busy = true
        pwError = null
        Identity.getAuthorizationClient(ctx).authorize(GoogleAuth.request(a.email))
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    busy = false
                    googleLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Mail.verifyImap(a); Mail.clearError(a.id) }
                            toaster.show("Signed in")
                        } catch (t: Throwable) {
                            pwError = MailErrors.describe(t, a.imapHost)
                        } finally {
                            busy = false
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                busy = false
                pwError = MailErrors.describe(GoogleAuth.map(e))
            }
    }

    fun savePassword() {
        if (password.isEmpty()) return
        busy = true
        pwError = null
        scope.launch {
            try {
                val updated = a.copy(secret = Secrets.encrypt(password))
                withContext(Dispatchers.IO) { Mail.verifyImap(updated) }
                Mail.updateAccount(updated)
                withContext(Dispatchers.IO) { Mail.clearError(a.id) }
                password = ""
                toaster.show("Password updated")
            } catch (t: Throwable) {
                pwError = if (MailErrors.isAuth(t)) "That password didn't work." else MailErrors.describe(t, a.imapHost)
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        containerColor = c.ground,
        contentWindowInsets = WindowInsets(0),
        topBar = { TopBar(a.label, onBack = { nav.pop() }) },
        snackbarHost = { BoltSnackbarHost(Modifier.navigationBarsPadding()) },
    ) { pad ->
        Column(
            Modifier
                .padding(top = pad.calculateTopPadding())
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text(a.email, style = type.bodyMedium, color = c.ink2, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp))
            if (a.error != null) {
                ShadeGroup(Modifier.padding(top = 12.dp)) {
                    item { shape ->
                        Notice(
                            shape, R.drawable.ic_error,
                            if (a.errorKind == ERR_AUTH) "Sign in again" else "Connection problem",
                            a.error, tone = if (a.errorKind == ERR_AUTH) Tone.Danger else Tone.Neutral,
                            action = if (a.isGoogle() && a.errorKind == ERR_AUTH) "Sign in with Google" else null,
                            onAction = { reauthGoogle() },
                        )
                    }
                }
            }

            SectionLabel("Details")
            ShadeGroup {
                item { shape ->
                    InputRow(shape, "Your name", name, { name = it }, placeholder = "Shown to people you email", keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
                }
                item { shape -> InputRow(shape, "Label", label, { label = it }, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words)) }
                item { shape -> ColorRow(shape, color) { color = it } }
                item { shape -> SignatureRow(shape, signature) { signature = it } }
            }

            SectionLabel("Notifications")
            ShadeGroup {
                item { shape ->
                    ShadeRow(
                        shape, "New mail notifications",
                        subtitle = if (a.notify) "Live connection to ${a.imapHost}" else "Off: mail is checked when you open BoltMail",
                        onClick = { scope.launch { Mail.updateAccount(a.copy(notify = !a.notify)) } },
                    ) {
                        BoltSwitch(a.notify) { v -> scope.launch { Mail.updateAccount(a.copy(notify = v)) } }
                    }
                }
                item { shape ->
                    ShadeRow(
                        shape, "Sound and vibration", subtitle = "Android's settings for ${a.label}",
                        icon = R.drawable.ic_open_in_new,
                        onClick = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                        .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.channelId(a.id)),
                                )
                            }
                        },
                    )
                }
            }

            SectionLabel("Sign-in")
            ShadeGroup {
                if (a.isGoogle()) {
                    item { shape ->
                        ShadeRow(shape, "Signed in with Google", subtitle = a.username, icon = R.drawable.ic_google, iconTint = Color.Unspecified) {
                            TextAction("Sign in again", { reauthGoogle() }, c.ink2)
                        }
                    }
                } else {
                    item { shape ->
                        InputRow(
                            shape, "Password", password, { password = it; pwError = null },
                            placeholder = "New password",
                            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visual = PasswordVisualTransformation(),
                        ) {
                            if (password.isNotEmpty()) TextAction(if (busy) "Checking…" else "Save", { savePassword() })
                        }
                    }
                }
                item { shape -> ShadeRow(shape, "Incoming", subtitle = "${a.imapHost}:${a.imapPort} · ${sec(a.imapSecurity)}") }
                item { shape -> ShadeRow(shape, "Outgoing", subtitle = "${a.smtpHost}:${a.smtpPort} · ${sec(a.smtpSecurity)}") }
            }
            pwError?.let { Text(it, style = type.bodySmall, color = c.danger, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) }

            Spacer(Modifier.height(24.dp))
            ShadeGroup {
                item { shape ->
                    ShadeRow(shape, "Remove account", icon = R.drawable.ic_delete, iconTint = c.danger, titleColor = c.danger, onClick = { confirmRemove = true })
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = c.surface,
            title = { Text("Remove ${a.email}?", style = type.titleLarge) },
            text = { Text("Its mail is removed from this phone. Nothing is deleted from the server.", style = type.bodyMedium, color = c.ink2) },
            confirmButton = {
                TextAction("Remove", {
                    confirmRemove = false
                    scope.launch {
                        Mail.removeAccount(a.id)
                        nav.pop()
                    }
                }, c.danger)
            },
            dismissButton = { TextAction("Cancel", { confirmRemove = false }, c.ink) },
        )
    }
}

private fun sec(s: String) = when (s) {
    SEC_SSL -> "SSL/TLS"
    SEC_NONE -> "Not encrypted"
    else -> "STARTTLS"
}

@Composable
private fun SignatureRow(shape: Shape, value: String, onChange: (String) -> Unit) {
    val c = Bolt.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp),
    ) {
        Text("Signature", style = type.bodyMedium, color = c.ink3)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = type.bodyLarge.copy(color = c.ink),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("Added below every message you send", style = type.bodyLarge, color = c.ink3)
                    inner()
                }
            },
        )
    }
}
