package dev.natya.boltmail.mail

import com.sun.mail.smtp.SMTPSendFailedException
import com.sun.mail.util.MailConnectException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.mail.AuthenticationFailedException
import javax.mail.FolderClosedException
import javax.mail.MessagingException
import javax.mail.SendFailedException
import javax.mail.StoreClosedException
import javax.net.ssl.SSLException

/** Turns mail exceptions into plain sentences and decides whether retrying makes sense. */
object MailErrors {
    private fun chain(t: Throwable): Sequence<Throwable> = generateSequence(t) { cur ->
        (cur as? MessagingException)?.nextException ?: cur.cause.takeIf { it !== cur }
    }.take(8)

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

    fun describe(t: Throwable, host: String? = null): String {
        val where = host ?: "the mail server"
        for (c in chain(t)) {
            when (c) {
                is GoogleAuth.NeedsConsent -> return "Google needs you to sign in again."
                is GoogleAuth.NotConfigured -> return "Google sign-in isn't set up for this copy of BoltMail yet."
                is AuthenticationFailedException -> return "The server didn't accept the password."
                is UnknownHostException -> return "Couldn't find $where. Check the address and your connection."
                is CertificateException, is SSLException -> return "$where's security certificate couldn't be verified."
                is SocketTimeoutException -> return "$where took too long to answer."
                is ConnectException, is NoRouteToHostException, is MailConnectException -> return "Couldn't connect to $where."
                is SendFailedException -> {
                    val bad = c.invalidAddresses?.joinToString(", ").orEmpty()
                    if (bad.isNotEmpty()) return "These addresses were rejected: $bad"
                }
            }
        }
        val msg = t.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        if (msg.contains("identity of server", true)) return "$where's security certificate doesn't match its name."
        return if (msg.isEmpty() || msg.length > 160) "Something went wrong talking to $where." else msg
    }
}
