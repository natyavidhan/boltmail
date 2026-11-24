package dev.natya.boltmail.mail

import android.content.Context
import dev.natya.boltmail.data.Account
import dev.natya.boltmail.data.SEC_NONE
import dev.natya.boltmail.data.SEC_SSL
import dev.natya.boltmail.data.SEC_STARTTLS
import dev.natya.boltmail.data.Secrets
import dev.natya.boltmail.data.isGoogle
import dev.natya.boltmail.push.Keepalive
import com.sun.mail.imap.IMAPStore
import java.util.Properties
import javax.activation.CommandMap
import javax.activation.MailcapCommandMap
import javax.mail.AuthenticationFailedException
import javax.mail.Session
import javax.mail.Transport
import javax.net.ssl.SSLSocketFactory

object MailSession {
    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 60_000

    /** Read timeout for push connections; longer than the keep-alive interval so IDLE isn't cut off. */
    val IDLE_READ_TIMEOUT = (Keepalive.INTERVAL_MS + 4 * 60_000).toInt()

    fun init() {
        val mc = CommandMap.getDefaultCommandMap() as MailcapCommandMap
        mc.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html")
        mc.addMailcap("text/xml;; x-java-content-handler=com.sun.mail.handlers.text_xml")
        mc.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain")
        mc.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed; x-java-fallback-entry=true")
        mc.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822")
        CommandMap.setDefaultCommandMap(mc)
    }

    fun baseProps() = Properties().apply {
        put("mail.mime.address.strict", "false")
        put("mail.mime.decodetext.strict", "false")
        put("mail.mime.decodefilename", "true")
        put("mail.mime.decodeparameters", "true")
        put("mail.mime.encodefilename", "true")
        put("mail.mime.encodeparameters", "true")
        put("mail.mime.base64.ignoreerrors", "true")
        put("mail.mime.parameters.strict", "false")
        put("mail.mime.multipart.ignoremissingboundaryparameter", "true")
        put("mail.mime.multipart.allowempty", "true")
    }

    private fun fixClassLoader() {
        Thread.currentThread().contextClassLoader = MailSession::class.java.classLoader
    }

    fun imapProtocol(a: Account) = if (a.imapSecurity == SEC_SSL) "imaps" else "imap"
    private fun smtpProtocol(a: Account) = if (a.smtpSecurity == SEC_SSL) "smtps" else "smtp"

    fun imapSession(a: Account, tracker: SocketTracker, readTimeout: Int = READ_TIMEOUT): Session {
        fixClassLoader()
        val p = imapProtocol(a)
        val props = baseProps()
        props["mail.store.protocol"] = p
        props["mail.$p.connectiontimeout"] = "$CONNECT_TIMEOUT"
        props["mail.$p.timeout"] = "$readTimeout"
        props["mail.$p.peek"] = "true"
        props["mail.$p.fetchsize"] = "262144"
        props["mail.$p.ignorebodystructuresize"] = "true"
        props["mail.$p.ssl.checkserveridentity"] = "true"
        props["mail.$p.auth.mechanisms"] = if (a.isGoogle()) "XOAUTH2" else "PLAIN LOGIN"
        props["mail.$p.ssl.socketFactory"] =
            TrackingSSLSocketFactory(SSLSocketFactory.getDefault() as SSLSocketFactory, tracker)
        when (a.imapSecurity) {
            SEC_STARTTLS -> {
                props["mail.$p.starttls.enable"] = "true"
                props["mail.$p.starttls.required"] = "true"
                props["mail.$p.socketFactory"] = TrackingSocketFactory(tracker)
            }
            SEC_NONE -> props["mail.$p.socketFactory"] = TrackingSocketFactory(tracker)
        }
        return Session.getInstance(props)
    }

    fun smtpSession(a: Account): Session {
        fixClassLoader()
        val p = smtpProtocol(a)
        val props = baseProps()
        props["mail.transport.protocol"] = p
        props["mail.$p.host"] = a.smtpHost
        props["mail.$p.port"] = "${a.smtpPort}"
        props["mail.$p.auth"] = "true"
        props["mail.$p.connectiontimeout"] = "$CONNECT_TIMEOUT"
        props["mail.$p.timeout"] = "$READ_TIMEOUT"
        props["mail.$p.ssl.checkserveridentity"] = "true"
        // Same EHLO argument Thunderbird uses; avoids leaking the device's host name.
        props["mail.$p.localhost"] = "[127.0.0.1]"
        props["mail.$p.auth.mechanisms"] = if (a.isGoogle()) "XOAUTH2" else "PLAIN LOGIN"
        if (a.smtpSecurity == SEC_STARTTLS) {
            props["mail.$p.starttls.enable"] = "true"
            props["mail.$p.starttls.required"] = "true"
        }
        return Session.getInstance(props)
    }

    /** The password, or a fresh OAuth access token for Google accounts. Blocking. */
    fun secret(context: Context, a: Account): String =
        if (a.isGoogle()) GoogleAuth.token(context, a.email) else Secrets.decrypt(a.secret)

    /** Opens an authenticated IMAP connection. Blocking. */
    fun connectStore(
        context: Context,
        a: Account,
        tracker: SocketTracker,
        readTimeout: Int = READ_TIMEOUT,
    ): IMAPStore {
        var retried = false
        while (true) {
            val store = imapSession(a, tracker, readTimeout).getStore(imapProtocol(a)) as IMAPStore
            val secret = secret(context, a)
            try {
                store.connect(a.imapHost, a.imapPort, a.username, secret)
                return store
            } catch (e: AuthenticationFailedException) {
                tracker.closeAll()
                if (a.isGoogle() && !retried) {
                    // The cached token may have expired early; drop it and ask Google for a new one.
                    GoogleAuth.invalidate(context, secret)
                    retried = true
                    continue
                }
                throw e
            }
        }
    }

    /** Opens an authenticated SMTP connection. Blocking. */
    fun connectTransport(context: Context, a: Account, session: Session = smtpSession(a)): Transport {
        var retried = false
        while (true) {
            val t = session.getTransport(smtpProtocol(a))
            val secret = secret(context, a)
            try {
                t.connect(a.smtpHost, a.smtpPort, a.username, secret)
                return t
            } catch (e: AuthenticationFailedException) {
                if (a.isGoogle() && !retried) {
                    GoogleAuth.invalidate(context, secret)
                    retried = true
                    continue
                }
                throw e
            }
        }
    }
}
