package dev.natya.boltmail.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.natya.boltmail.Graph
import dev.natya.boltmail.data.Account
import dev.natya.boltmail.data.Folder
import dev.natya.boltmail.data.MessageRow
import dev.natya.boltmail.data.Op
import dev.natya.boltmail.data.Role
import dev.natya.boltmail.mail.Mail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which list is showing: all inboxes (folderId 0) or one folder. */
data class Mailbox(val accountId: Long = 0L, val folderId: Long = 0L) {
    val all get() = folderId == 0L
}

class InboxViewModel : ViewModel() {
    private val dao = Graph.dao
    private val page = 60

    val mailbox = MutableStateFlow(Mailbox())
    val query = MutableStateFlow<String?>(null)
    private val limit = MutableStateFlow(page)

    val accounts: StateFlow<List<Account>> = dao.accountsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val folders: StateFlow<List<Folder>> = dao.foldersFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val unread: StateFlow<Map<Long, Int>> = dao.unreadCounts()
        .map { list -> list.associate { it.folderId to it.unread } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val outbox: StateFlow<List<Op>> = dao.sendOpsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val refreshing = MutableStateFlow(false)
    val loadingMore = MutableStateFlow(false)
    private var lastRefresh = 0L

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val rows: StateFlow<List<MessageRow>?> =
        combine(mailbox, limit, query.debounce { if (it.isNullOrEmpty()) 0L else 180L }) { mb, lim, q -> Triple(mb, lim, q) }
            .flatMapLatest { (mb, lim, q) ->
                when {
                    !q.isNullOrBlank() -> dao.search("%${q.trim()}%", mb.accountId)
                    mb.folderId != 0L -> dao.folderRows(mb.folderId, lim)
                    else -> dao.inboxRows(lim)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val limitValue get() = limit.value

    fun select(mb: Mailbox) {
        if (mb == mailbox.value) return
        mailbox.value = mb
        limit.value = page
        refresh()
    }

    fun refresh(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < 60_000) return
        if (refreshing.value) return
        lastRefresh = now
        viewModelScope.launch {
            refreshing.value = true
            try {
                val errors = Mail.refresh(mailbox.value.folderId.takeIf { it != 0L })
                errors.firstOrNull()?.let { Graph.events.tryEmit(it) }
            } finally {
                refreshing.value = false
            }
        }
    }

    /** Folders whose older mail can still be fetched from the server for the current list. */
    fun remoteTargets(): List<Folder> {
        val mb = mailbox.value
        val fs = folders.value
        val targets = if (mb.folderId != 0L) fs.filter { it.id == mb.folderId } else fs.filter { it.role == Role.INBOX }
        return targets.filter { it.synced && it.hasMore }
    }

    fun loadMore() {
        if (loadingMore.value || !query.value.isNullOrEmpty()) return
        val current = rows.value ?: return
        if (current.size >= limit.value) {
            limit.value += page
            return
        }
        val targets = remoteTargets()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            loadingMore.value = true
            try {
                Mail.loadOlder(targets.map { it.id })
                limit.value += page
            } finally {
                loadingMore.value = false
            }
        }
    }

    fun setSeen(id: Long, seen: Boolean) {
        viewModelScope.launch { Mail.setSeen(listOf(id), seen) }
    }

    fun syncing(): Boolean = folders.value.any { it.role == Role.INBOX && !it.synced }
}
