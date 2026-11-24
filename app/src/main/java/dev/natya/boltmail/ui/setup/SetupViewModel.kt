package dev.natya.boltmail.ui.setup

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.natya.boltmail.Graph
import dev.natya.boltmail.data.AUTH_GOOGLE
import dev.natya.boltmail.data.AUTH_PASSWORD
import dev.natya.boltmail.data.Account
import dev.natya.boltmail.data.SEC_NONE
import dev.natya.boltmail.data.SEC_SSL
import dev.natya.boltmail.data.SEC_STARTTLS
import dev.natya.boltmail.data.Secrets
import dev.natya.boltmail.mail.Addr
import dev.natya.boltmail.mail.Discovery
import dev.natya.boltmail.mail.GoogleAuth
import dev.natya.boltmail.mail.Mail
import dev.natya.boltmail.mail.MailErrors
import dev.natya.boltmail.mail.Provider
import dev.natya.boltmail.mail.Providers
import dev.natya.boltmail.mail.ServerConfig
import dev.natya.boltmail.push.PushService
import dev.natya.boltmail.ui.theme.AccountColors
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Step { Welcome, Email, Checking, Manual, Name, Push }
enum class Check { Waiting, Running, Done, Failed }

class SetupViewModel(first: Boolean) : ViewModel() {
    var step by mutableStateOf(if (first) Step.Welcome else Step.Email)
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var provider by mutableStateOf<Provider?>(null)
    var appPassword by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    val checks = mutableStateListOf(Check.Waiting, Check.Waiting, Check.Waiting)

    var imapHost by mutableStateOf("")
    var imapPort by mutableStateOf("993")
    var imapSecurity by mutableStateOf(SEC_SSL)
    var smtpHost by mutableStateOf("")
    var smtpPort by mutableStateOf("465")
    var smtpSecurity by mutableStateOf(SEC_SSL)
    var username by mutableStateOf("")

    var name by mutableStateOf("")
    var label by mutableStateOf("")
    var color by mutableIntStateOf(AccountColors.first())

    private var pending: Account? = null

    val emailValid get() = Addr.isEmail(email)
    val googleFlow get() = provider?.google == true && !appPassword

    fun onEmail(v: String) {
        email = v.trim()
        provider = Providers.byEmail(email)
        error = null
    }

    private fun duplicate(addr: String) = Graph.dao.accountsWithEmail(addr) > 0

    fun submit() {
        if (!emailValid) {
            error = "That doesn't look like an email address."
            return
        }
        if (password.isEmpty()) {
            error = "Enter the password for this account."
            return
        }
        viewModelScope.launch {
            if (withContext(Dispatchers.IO) { duplicate(email) }) {
                error = "This account is already in BoltMail."
                return@launch
            }
            start(null)
        }
    }

    fun submitManual() {
        val ip = imapPort.toIntOrNull()
        val sp = smtpPort.toIntOrNull()
        if (imapHost.isBlank() || smtpHost.isBlank() || ip == null || sp == null) {
            error = "Fill in both servers and their ports."
            return
        }
        viewModelScope.launch {
            start(ServerConfig(imapHost.trim(), ip, imapSecurity, smtpHost.trim(), sp, smtpSecurity))
        }
    }

    private suspend fun start(manual: ServerConfig?) {
        error = null
        step = Step.Checking
        checks[0] = Check.Running; checks[1] = Check.Waiting; checks[2] = Check.Waiting
        val p = manual?.let { Provider("custom", Providers.domainOf(email), it) }
            ?: withContext(Dispatchers.IO) { Discovery.discover(email) }
        if (p?.blocked != null) {
            provider = p
            error = p.blocked
            step = Step.Email
            return
        }
        val cfg = p?.config
        if (cfg == null) {
            checks[0] = Check.Failed
            prefillManual(null)
            error = "Couldn't find the mail server for ${Providers.domainOf(email)}. Your email host's help pages list these settings."
            step = Step.Manual
            return
        }
        provider = p
        checks[0] = Check.Done
        val user = if (manual != null) username.trim().ifBlank { email } else Providers.username(cfg, email)
        val acct = Account(
            email = email, name = "", label = "", color = 0, auth = AUTH_PASSWORD, username = user,
            secret = Secrets.encrypt(password),
            imapHost = cfg.imapHost, imapPort = cfg.imapPort, imapSecurity = cfg.imapSecurity,
            smtpHost = cfg.smtpHost, smtpPort = cfg.smtpPort, smtpSecurity = cfg.smtpSecurity,
            provider = p.id,
        )
        verify(acct, manual != null)
    }

    private suspend fun verify(acct: Account, manual: Boolean) {
        checks[1] = Check.Running
        try {
            withContext(Dispatchers.IO) { Mail.verifyImap(acct) }
        } catch (t: Throwable) {
            checks[1] = Check.Failed
            error = signInError(t, acct)
            if (MailErrors.isAuth(t) || acct.auth == AUTH_GOOGLE) {
                step = if (manual) Step.Manual else Step.Email
            } else {
                prefillManual(acct)
                step = Step.Manual
            }
            return
        }
        checks[1] = Check.Done
        checks[2] = Check.Running
        try {
            withContext(Dispatchers.IO) { Mail.verifySmtp(acct) }
        } catch (t: Throwable) {
            checks[2] = Check.Failed
            prefillManual(acct)
            error = "Signed in, but sending mail failed: ${MailErrors.describe(t, acct.smtpHost)} Check the outgoing server below."
            step = Step.Manual
            return
        }
        checks[2] = Check.Done
        pending = acct
        prefillName(acct)
        step = Step.Name
    }

    private fun signInError(t: Throwable, a: Account): String {
        if (MailErrors.isAuth(t) && a.auth == AUTH_PASSWORD) {
            val hint = provider?.passwordHint
            return if (hint != null) "That password didn't work. $hint" else "The server didn't accept that email and password."
        }
        return MailErrors.describe(t, a.imapHost)
    }

    private fun prefillManual(a: Account?) {
        val domain = Providers.domainOf(email)
        val cfg = provider?.config
        imapHost = a?.imapHost ?: cfg?.imapHost ?: "imap.$domain"
        imapPort = (a?.imapPort ?: cfg?.imapPort ?: 993).toString()
        imapSecurity = a?.imapSecurity ?: cfg?.imapSecurity ?: SEC_SSL
        smtpHost = a?.smtpHost ?: cfg?.smtpHost ?: "smtp.$domain"
        smtpPort = (a?.smtpPort ?: cfg?.smtpPort ?: 465).toString()
        smtpSecurity = a?.smtpSecurity ?: cfg?.smtpSecurity ?: SEC_SSL
        if (username.isBlank()) username = a?.username ?: email
    }

    fun openManual() {
        error = null
        prefillManual(null)
        step = Step.Manual
    }

    fun chooseImapSecurity(s: String) {
        if (imapPort == defaultPort(imapSecurity, true)) imapPort = defaultPort(s, true)
        imapSecurity = s
    }

    fun chooseSmtpSecurity(s: String) {
        if (smtpPort == defaultPort(smtpSecurity, false)) smtpPort = defaultPort(s, false)
        smtpSecurity = s
    }

    private fun defaultPort(sec: String, imap: Boolean) = when (sec) {
        SEC_SSL -> if (imap) "993" else "465"
        SEC_STARTTLS -> if (imap) "143" else "587"
        else -> if (imap) "143" else "25"
    }

    private suspend fun prefillName(a: Account) {
        val existing = withContext(Dispatchers.IO) { Graph.dao.accounts() }
        if (name.isBlank()) name = existing.firstOrNull { it.name.isNotBlank() }?.name.orEmpty()
        label = when (provider?.id) {
            "gmail" -> if (existing.none { it.label == "Gmail" }) "Gmail" else a.email.substringBefore('@').replaceFirstChar { it.uppercase() }
            else -> a.email.substringBefore('@').replaceFirstChar { it.uppercase() }
        }
        val used = existing.map { it.color }.toSet()
        color = AccountColors.firstOrNull { it !in used } ?: AccountColors.first()
    }

    // ---------------------------------------------------------------- Google

    fun google(activity: Activity, launch: (IntentSenderRequest) -> Unit) {
        error = null
        busy = true
        Identity.getAuthorizationClient(activity)
            .authorize(GoogleAuth.request(email.takeIf { emailValid }))
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    onGoogle(r)
                }
            }
            .addOnFailureListener { e ->
                busy = false
                error = googleError(e)
            }
    }

    fun onGoogleResult(context: Context, data: Intent?) {
        try {
            onGoogle(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))
        } catch (e: ApiException) {
            busy = false
            error = if (e.statusCode == CommonStatusCodes.CANCELED) null else googleError(e)
        }
    }

    private fun googleError(e: Throwable): String {
        val mapped = GoogleAuth.map(e)
        return if (mapped is GoogleAuth.NotConfigured) {
            "Google sign-in isn't set up for this copy of BoltMail yet (see GOOGLE_SIGN_IN.md). You can use an app password for now."
        } else {
            MailErrors.describe(mapped)
        }
    }

    private fun onGoogle(r: AuthorizationResult) {
        viewModelScope.launch {
            val (addr, display) = withContext(Dispatchers.IO) { GoogleAuth.profile(r) }
            val mail = addr ?: email
            busy = false
            if (!Addr.isEmail(mail)) {
                error = "Google didn't share this account's email address. Try again, or use an app password."
                return@launch
            }
            email = mail
            if (withContext(Dispatchers.IO) { duplicate(mail) }) {
                error = "This account is already in BoltMail."
                return@launch
            }
            if (!display.isNullOrBlank()) name = display
            provider = provider?.takeIf { it.google } ?: Providers.GMAIL
            val cfg = Providers.GMAIL.config!!
            val acct = Account(
                email = mail, name = "", label = "", color = 0, auth = AUTH_GOOGLE, username = mail, secret = "",
                imapHost = cfg.imapHost, imapPort = cfg.imapPort, imapSecurity = cfg.imapSecurity,
                smtpHost = cfg.smtpHost, smtpPort = cfg.smtpPort, smtpSecurity = cfg.smtpSecurity,
                provider = provider?.id ?: "gmail",
            )
            step = Step.Checking
            checks[0] = Check.Done
            verify(acct, manual = false)
        }
    }

    // ---------------------------------------------------------------- finish

    fun finish(needsPush: Boolean, done: () -> Unit) {
        val a = pending ?: return
        busy = true
        viewModelScope.launch {
            Mail.addAccount(
                a.copy(
                    name = name.trim(),
                    label = label.trim().ifBlank { a.email.substringBefore('@') },
                    color = color,
                ),
            )
            PushService.start(Graph.app)
            busy = false
            if (needsPush) step = Step.Push else done()
        }
    }

    companion object {
        val SECURITY = listOf(SEC_SSL to "SSL/TLS", SEC_STARTTLS to "STARTTLS", SEC_NONE to "None")
    }
}
