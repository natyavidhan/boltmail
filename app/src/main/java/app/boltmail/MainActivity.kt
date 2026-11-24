package app.boltmail

import android.content.Intent
import android.graphics.Color
import android.net.MailTo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.boltmail.data.Account
import app.boltmail.data.Prefs
import app.boltmail.push.Notifier
import app.boltmail.push.PushService
import app.boltmail.ui.LocalNav
import app.boltmail.ui.LocalToaster
import app.boltmail.ui.Nav
import app.boltmail.ui.NavHost
import app.boltmail.ui.Route
import app.boltmail.ui.Toaster
import app.boltmail.ui.compose.ComposeScreen
import app.boltmail.ui.inbox.InboxScreen
import app.boltmail.ui.message.MessageScreen
import app.boltmail.ui.settings.AccountScreen
import app.boltmail.ui.settings.SettingsScreen
import app.boltmail.ui.setup.SetupScreen
import app.boltmail.ui.theme.Bolt
import app.boltmail.ui.theme.BoltTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) vm.handleIntent(intent)
        setContent {
            val pref by Graph.prefs.theme.collectAsStateWithLifecycle()
            val dark = when (pref) {
                Prefs.THEME_LIGHT -> false
                Prefs.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            BoltTheme(dark) { App(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Graph.scope.launch { PushService.startIfWanted(applicationContext) }
    }
}

class AppViewModel : ViewModel() {
    val nav = Nav(Route.Inbox)
    val accounts: StateFlow<List<Account>?> =
        Graph.dao.accountsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var pending: Intent? = null

    fun onAccounts(list: List<Account>) {
        if (list.isEmpty()) {
            if (nav.top.route !is Route.Setup) nav.reset(Route.Setup(first = true))
            pending = null
            return
        }
        pending?.let {
            pending = null
            handleIntent(it)
        }
    }

    fun handleIntent(intent: Intent?) {
        intent ?: return
        val list = accounts.value
        if (list == null) {
            pending = intent
            return
        }
        if (list.isEmpty()) return
        val messageId = intent.getLongExtra(Notifier.EXTRA_MESSAGE, 0)
        if (messageId > 0) {
            intent.removeExtra(Notifier.EXTRA_MESSAGE)
            nav.openOnInbox(Route.Message(messageId))
            return
        }
        when (intent.action) {
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme != "mailto") return
                val mt = runCatching { MailTo.parse(data.toString()) }.getOrNull() ?: return
                nav.openOnInbox(
                    Route.Compose(to = mt.to.orEmpty(), cc = mt.cc.orEmpty(), subject = mt.subject.orEmpty(), body = mt.body.orEmpty()),
                )
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val files = mutableListOf<Uri>()
                if (intent.action == Intent.ACTION_SEND) {
                    streamExtra(intent)?.let(files::add)
                } else {
                    files += streamListExtra(intent)
                }
                val to = intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.joinToString(", ").orEmpty()
                nav.openOnInbox(
                    Route.Compose(
                        to = to,
                        subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(),
                        body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
                        files = files,
                    ),
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM))
            .orEmpty()
}

@Composable
private fun App(vm: AppViewModel) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toaster = remember { Toaster(host, scope) }
    LaunchedEffect(Unit) { Graph.events.collect { toaster.show(it) } }
    LaunchedEffect(accounts) { accounts?.let(vm::onAccounts) }
    Box(Modifier.fillMaxSize().background(Bolt.colors.ground)) {
        if (accounts != null) {
            CompositionLocalProvider(LocalNav provides vm.nav, LocalToaster provides toaster) {
                NavHost(vm.nav) { route ->
                    when (route) {
                        Route.Inbox -> InboxScreen()
                        is Route.Message -> MessageScreen(route.id)
                        is Route.Compose -> ComposeScreen(route)
                        Route.Settings -> SettingsScreen()
                        is Route.Account -> AccountScreen(route.id)
                        is Route.Setup -> SetupScreen(route.first)
                    }
                }
            }
        }
    }
}
