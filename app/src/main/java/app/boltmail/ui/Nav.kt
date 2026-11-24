package app.boltmail.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.boltmail.mail.Mail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

enum class ComposeMode { New, Reply, ReplyAll, Forward }

sealed interface Route {
    data object Inbox : Route
    data class Message(val id: Long) : Route
    data class Compose(
        val mode: ComposeMode = ComposeMode.New,
        val refId: Long = 0,
        val to: String = "",
        val cc: String = "",
        val subject: String = "",
        val body: String = "",
        val files: List<Uri> = emptyList(),
    ) : Route
    data object Settings : Route
    data class Account(val id: Long) : Route
    data class Setup(val first: Boolean) : Route
}

/** A back-stack entry owns the ViewModels of its screen; they are cleared after it leaves. */
class Entry(val route: Route) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val key: String = UUID.randomUUID().toString()
}

/** A deliberately small navigator: a stack of routes, slide transitions, system Back. */
class Nav(start: Route) {
    val stack = mutableStateListOf(Entry(start))
    var forward by mutableStateOf(true)
        private set

    val top: Entry get() = stack.last()

    fun push(r: Route) {
        forward = true
        stack.add(Entry(r))
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        forward = false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Replace the whole stack, e.g. after the first account is added. */
    fun reset(r: Route) {
        forward = true
        stack.clear()
        stack.add(Entry(r))
    }

    /** Pops back to the inbox (if present) and pushes [r] on top of it. */
    fun openOnInbox(r: Route) {
        forward = true
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        if (stack.first().route != Route.Inbox) {
            stack.clear(); stack.add(Entry(Route.Inbox))
        }
        stack.add(Entry(r))
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }

@Composable
fun NavHost(nav: Nav, content: @Composable (Route) -> Unit) {
    val holder = rememberSaveableStateHolder()
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    AnimatedContent(
        targetState = nav.top,
        contentKey = { it.key },
        transitionSpec = {
            val dir = if (nav.forward) 1 else -1
            (slideInHorizontally(tween(260)) { w -> dir * w / 8 } + fadeIn(tween(220, delayMillis = 40)))
                .togetherWith(slideOutHorizontally(tween(260)) { w -> -dir * w / 8 } + fadeOut(tween(120)))
        },
        label = "nav",
    ) { entry ->
        DisposableEffect(entry) {
            onDispose {
                if (entry !in nav.stack) {
                    entry.viewModelStore.clear()
                    holder.removeState(entry.key)
                }
            }
        }
        holder.SaveableStateProvider(entry.key) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides entry) {
                content(entry.route)
            }
        }
    }
}

/** Snackbars that outlive the screen that raised them (e.g. "Archived · Undo" after closing a message). */
class Toaster(val host: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(message: String) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(message, withDismissAction = false, duration = SnackbarDuration.Short)
        }
    }

    fun undoable(message: String, opId: Long?) {
        if (opId == null) return
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val r = host.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) Mail.undo(opId)
        }
    }

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }
}

val LocalToaster = staticCompositionLocalOf<Toaster> { error("no toaster") }
