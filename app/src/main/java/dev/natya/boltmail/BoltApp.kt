package dev.natya.boltmail

import android.app.Application
import dev.natya.boltmail.mail.MailSession
import dev.natya.boltmail.mail.OpRunner
import dev.natya.boltmail.push.Notifier
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
