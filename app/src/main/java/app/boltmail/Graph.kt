package app.boltmail

import android.app.Application
import app.boltmail.data.BoltDb
import app.boltmail.data.MailDao
import app.boltmail.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow

/** App-wide singletons. Small enough that a DI framework would only add weight. */
object Graph {
    lateinit var app: Application
        private set
    lateinit var db: BoltDb
        private set
    lateinit var prefs: Prefs
        private set

    val dao: MailDao get() = db.dao()

    /** Work that should outlive a screen, e.g. queued server operations. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One-line messages from background work, shown as snackbars when the app is open. */
    val events = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** True while the inbox list is on screen; new-mail notifications are skipped then. */
    @Volatile
    var inboxVisible = false

    fun init(app: Application) {
        this.app = app
        db = BoltDb.create(app)
        prefs = Prefs(app)
    }
}
