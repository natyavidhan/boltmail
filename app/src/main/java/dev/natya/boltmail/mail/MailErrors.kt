package dev.natya.boltmail.mail

import com.sun.mail.smtp.SMTPSendFailedException
import com.sun.mail.util.MailConnectException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.mail.AuthenticationFailedException
import javax.mail.FolderClosedException
import javax.mail.MessagingException
import javax.mail.SendFailedException
import javax.mail.StoreClosedException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Turns mail exceptions into plain sentences and decides whether retrying makes sense. */
object MailErrors {
    private fun chain(t: Throwable): List<Throwable> = generateSequence(t) { cur ->
        ((cur as? MessagingException)?.nextException ?: cur.cause).takeIf { it !== cur }
    }.take(10).toList()

    private inline fun <reified T : Throwable> List<Throwable>.find(): T? = firstOrNull { it is T } as T?

    fun isAuth(t: Throwable) = chain(t).any { it is AuthenticationFailedException || it is GoogleAuth.NeedsConsent }

    fun isTransient(t: Throwable): Boolean {
        if (isAuth(t) || t is GoogleAuth.NotConfigured) return false
        return chain(t).any {
            it is MailConnectException || it is FolderClosedException || it is StoreClosedException ||
                it is SocketException || it is SocketTimeoutException || it is UnknownHostException ||
                (it is IOException && it !is SSLException && it !is GoogleAuth.NotConfigured) ||
                (it is SMTPSendFailedException && it.returnCode in 400..499)
        }
    }

    /** One sentence for the user. Looks through the whole cause chain for the most specific reason. */
    fun describe(t: Throwable, host: String? = null): String {
        val where = host ?: "the mail server"
        val c = chain(t)
        c.find<GoogleAuth.NeedsConsent>()?.let { return "Google needs you to sign in again." }
        c.find<GoogleAuth.NotConfigured>()?.let { return "Google sign-in isn't set up for this copy of BoltMail yet." }
        c.find<AuthenticationFailedException>()?.let { return "The server didn't accept the password." }
        c.find<UnknownHostException>()?.let { return "Couldn't find $where. Check the server name and your internet connection." }
        c.find<Dialer.DialException>()?.let { d ->
            val tried = d.attempts.joinToString(", ") { (a, e) -> "${Dialer.family(a)} ${Dialer.reason(e)}" }
            return "Couldn't connect to $where ($tried). Check your internet connection or try another network."
        }
        if (c.any { it is CertPathValidatorException || it is CertificateException || it is SSLPeerUnverifiedException }) {
            return "$where's security certificate couldn't be verified."
        }
        c.find<SSLHandshakeException>()?.let { return "The secure connection to $where failed: ${short(it)}" }
        c.find<SocketTimeoutException>()?.let { return "$where took too long to answer." }
        c.find<ConnectException>()?.let { return "$where refused the connection. Check the server name and port." }
        c.find<NoRouteToHostException>()?.let { return "Couldn't reach $where from this network." }
        c.find<SendFailedException>()?.let { s ->
            val bad = s.invalidAddresses?.joinToString(", ").orEmpty()
            if (bad.isNotEmpty()) return "These addresses were rejected: $bad"
        }
        c.find<SSLException>()?.let { return "The secure connection to $where failed: ${short(it)}" }
        c.find<MailConnectException>()?.let { return "Couldn't connect to $where." }
        val msg = t.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        if (msg.contains("identity of server", true)) return "$where's security certificate doesn't match its name."
        return if (msg.isEmpty() || msg.length > 160) "Something went wrong talking to $where." else msg
    }

    /** The innermost technical reason, for a small "details" line people can send in a bug report. */
    fun details(t: Throwable): String {
        val c = chain(t)
        val root = c.find<Dialer.DialException>() ?: c.last()
        return "${root.javaClass.simpleName}: ${root.message.orEmpty()}".take(240)
    }

    private fun short(e: Throwable) = e.message?.substringAfterLast(": ")?.take(80)?.ifBlank { null } ?: e.javaClass.simpleName
}
