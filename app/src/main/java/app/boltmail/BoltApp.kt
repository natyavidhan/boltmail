package app.boltmail

import android.app.Application
import app.boltmail.mail.MailSession
import app.boltmail.mail.OpRunner
import app.boltmail.push.Notifier
import kotlinx.coroutines.launch

class BoltApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        MailSession.init()
        Notifier.init(this)
        Graph.scope.launch { OpRunner.kick() }
    }
}
