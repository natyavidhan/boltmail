package app.boltmail.mail

import android.util.Xml
import app.boltmail.data.SEC_NONE
import app.boltmail.data.SEC_SSL
import app.boltmail.data.SEC_STARTTLS
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class ServerConfig(
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: String,
    /** Username template: "%EMAIL%" or "%LOCAL%". */
    val username: String = "%EMAIL%",
)

data class Provider(
    val id: String,
    val name: String,
    val config: ServerConfig?,
    val google: Boolean = false,
    /** Shown instead of a password field when the provider can't work with BoltMail. */
    val blocked: String? = null,
    /** Shown under the password field, e.g. "needs an app password". */
    val passwordHint: String? = null,
    val passwordUrl: String? = null,
)

object Providers {
    private fun ssl(imap: String, smtp: String, smtpPort: Int = 465, smtpSec: String = SEC_SSL) =
        ServerConfig(imap, 993, SEC_SSL, smtp, smtpPort, smtpSec)

    val GMAIL = Provider(
        "gmail", "Gmail", ssl("imap.gmail.com", "smtp.gmail.com"), google = true,
        passwordHint = "Use a 16-letter app password, not your Google password.",
        passwordUrl = "https://myaccount.google.com/apppasswords",
    )
    val GOOGLE_WORKSPACE = GMAIL.copy(id = "google", name = "Google Workspace")
    val HOSTINGER = Provider("hostinger", "Hostinger", ssl("imap.hostinger.com", "smtp.hostinger.com"))
    val TITAN = Provider("titan", "Titan", ssl("imap.titan.email", "smtp.titan.email"))
    val ZOHO = Provider("zoho", "Zoho Mail", ssl("imap.zoho.com", "smtp.zoho.com"))
    val ZOHO_IN = Provider("zoho", "Zoho Mail", ssl("imap.zoho.in", "smtp.zoho.in"))
    val ZOHO_EU = Provider("zoho", "Zoho Mail", ssl("imap.zoho.eu", "smtp.zoho.eu"))
    val YAHOO = Provider(
        "yahoo", "Yahoo Mail", ssl("imap.mail.yahoo.com", "smtp.mail.yahoo.com"),
        passwordHint = "Yahoo needs an app password from your account's security settings.",
        passwordUrl = "https://login.yahoo.com/account/security",
    )
    val AOL = Provider(
        "aol", "AOL Mail", ssl("imap.aol.com", "smtp.aol.com"),
        passwordHint = "AOL needs an app password from your account's security settings.",
        passwordUrl = "https://login.aol.com/account/security",
    )
    val ICLOUD = Provider(
        "icloud", "iCloud Mail", ssl("imap.mail.me.com", "smtp.mail.me.com", 587, SEC_STARTTLS).copy(username = "%LOCAL%"),
        passwordHint = "iCloud needs an app-specific password.",
        passwordUrl = "https://account.apple.com",
    )
    val FASTMAIL = Provider(
        "fastmail", "Fastmail", ssl("imap.fastmail.com", "smtp.fastmail.com"),
        passwordHint = "Fastmail needs an app password.",
        passwordUrl = "https://app.fastmail.com/settings/security/apps",
    )
    val GMX_COM = Provider("gmx", "GMX", ssl("imap.gmx.com", "mail.gmx.com", 587, SEC_STARTTLS))
    val GMX_NET = Provider("gmx", "GMX", ssl("imap.gmx.net", "mail.gmx.net", 587, SEC_STARTTLS))
    val YANDEX = Provider(
        "yandex", "Yandex Mail", ssl("imap.yandex.com", "smtp.yandex.com"),
        passwordHint = "Yandex needs an app password.",
    )
    val MAILRU = Provider("mailru", "Mail.ru", ssl("imap.mail.ru", "smtp.mail.ru"))
    val REDIFF = Provider("rediff", "Rediffmail", ssl("imap.rediffmail.com", "smtp.rediffmail.com"))
    val GODADDY = Provider("godaddy", "GoDaddy", ssl("imap.secureserver.net", "smtpout.secureserver.net"))
    val NAMECHEAP = Provider("namecheap", "Namecheap Private Email", ssl("mail.privateemail.com", "mail.privateemail.com"))
    val IONOS = Provider("ionos", "IONOS", ssl("imap.ionos.com", "smtp.ionos.com"))
    val MIGADU = Provider("migadu", "Migadu", ssl("imap.migadu.com", "smtp.migadu.com"))
    val PROTON = Provider(
        "proton", "Proton Mail", null,
        blocked = "Proton Mail can't be added. Proton only lets other apps read your mail through Proton Bridge on a computer.",
    )
    val MICROSOFT = Provider(
        "microsoft", "Outlook", null,
        blocked = "Outlook and Microsoft 365 no longer accept passwords from other mail apps, and BoltMail can't sign in to Microsoft yet.",
    )

    private val byDomain = mapOf(
        "gmail.com" to GMAIL, "googlemail.com" to GMAIL,
        "yahoo.com" to YAHOO, "yahoo.co.in" to YAHOO, "yahoo.in" to YAHOO, "yahoo.co.uk" to YAHOO,
        "ymail.com" to YAHOO, "rocketmail.com" to YAHOO,
        "aol.com" to AOL,
        "icloud.com" to ICLOUD, "me.com" to ICLOUD, "mac.com" to ICLOUD,
        "fastmail.com" to FASTMAIL, "fastmail.fm" to FASTMAIL,
        "gmx.com" to GMX_COM, "gmx.net" to GMX_NET, "gmx.de" to GMX_NET,
        "yandex.com" to YANDEX, "yandex.ru" to YANDEX,
        "mail.ru" to MAILRU,
        "rediffmail.com" to REDIFF,
        "zoho.com" to ZOHO, "zohomail.com" to ZOHO, "zoho.in" to ZOHO_IN, "zohomail.in" to ZOHO_IN,
        "proton.me" to PROTON, "protonmail.com" to PROTON, "protonmail.ch" to PROTON, "pm.me" to PROTON,
        "outlook.com" to MICROSOFT, "hotmail.com" to MICROSOFT, "live.com" to MICROSOFT, "msn.com" to MICROSOFT,
        "outlook.in" to MICROSOFT, "hotmail.co.uk" to MICROSOFT, "live.in" to MICROSOFT,
    )

    fun domainOf(email: String) = email.substringAfterLast('@', "").trim().lowercase()

    /** Instant answer from the address alone (no network). */
    fun byEmail(email: String): Provider? = byDomain[domainOf(email)]

    fun byMx(mx: String): Provider? = when {
        mx.endsWith("google.com") || mx.endsWith("googlemail.com") -> GOOGLE_WORKSPACE
        mx.endsWith("hostinger.com") || mx.endsWith("hostinger.in") -> HOSTINGER
        mx.endsWith("titan.email") -> TITAN
        mx.endsWith("zoho.in") -> ZOHO_IN
        mx.endsWith("zoho.eu") -> ZOHO_EU
        mx.endsWith("zoho.com") || mx.endsWith("zohomail.com") -> ZOHO
        mx.endsWith("secureserver.net") -> GODADDY
        mx.endsWith("privateemail.com") -> NAMECHEAP
        mx.endsWith("messagingengine.com") -> FASTMAIL
        mx.endsWith("protonmail.ch") || mx.endsWith("proton.ch") -> PROTON
        mx.endsWith("outlook.com") -> MICROSOFT
        mx.endsWith("yandex.net") || mx.endsWith("yandex.ru") -> YANDEX
        mx.endsWith("migadu.com") -> MIGADU
        mx.endsWith("ionos.com") || mx.endsWith("ionos.de") || mx.endsWith("1and1.com") -> IONOS
        mx.endsWith("icloud.com") -> ICLOUD.copy(config = ICLOUD.config!!.copy(username = "%EMAIL%"))
        else -> null
    }

    fun username(config: ServerConfig, email: String) =
        if (config.username == "%LOCAL%") email.substringBefore('@') else email.trim()
}

/** Finds server settings for an address. Every step is blocking; run on a background thread. */
object Discovery {
    fun discover(email: String): Provider? {
        Providers.byEmail(email)?.let { return it }
        val domain = Providers.domainOf(email)
        if (domain.isEmpty()) return null
        val mx = runCatching { Dns.mx(domain) }.getOrDefault(emptyList())
        mx.firstNotNullOfOrNull(Providers::byMx)?.let { return it }
        autoconfig("https://autoconfig.$domain/mail/config-v1.1.xml?emailaddress=${enc(email)}")?.let { return named(it, domain) }
        autoconfig("https://$domain/.well-known/autoconfig/mail/config-v1.1.xml")?.let { return named(it, domain) }
        autoconfig("https://autoconfig.thunderbird.net/v1.1/$domain")?.let { return named(it, domain) }
        mx.firstOrNull()?.let(::baseDomain)?.takeIf { it != domain }?.let { mxDomain ->
            autoconfig("https://autoconfig.thunderbird.net/v1.1/$mxDomain")?.let { return named(it, domain) }
        }
        return guess(domain)
    }

    private fun named(c: ServerConfig, domain: String) = Provider("custom", domain, c)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun baseDomain(host: String): String {
        val parts = host.split('.')
        return if (parts.size <= 2) host else parts.takeLast(2).joinToString(".")
    }

    fun autoconfig(url: String): ServerConfig? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        c.instanceFollowRedirects = true
        if (c.responseCode != 200) return null
        c.inputStream.use { parseAutoconfig(it.readBytes().decodeToString()) }
    }.getOrNull()

    /** Parses Thunderbird's autoconfig format; prefers TLS endpoints. */
    fun parseAutoconfig(xml: String): ServerConfig? {
        data class Srv(val host: String, val port: Int, val sec: String, val user: String)

        val imap = mutableListOf<Srv>()
        val smtp = mutableListOf<Srv>()
        val p = Xml.newPullParser()
        p.setInput(xml.reader())
        var kind: String? = null
        var list: MutableList<Srv>? = null
        var host = ""; var port = 0; var sec = ""; var user = ""
        var tag = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "incomingServer" || tag == "outgoingServer") {
                        kind = p.getAttributeValue(null, "type")
                        list = when {
                            tag == "incomingServer" && kind == "imap" -> imap
                            tag == "outgoingServer" && kind == "smtp" -> smtp
                            else -> null
                        }
                        host = ""; port = 0; sec = ""; user = ""
                    }
                }
                XmlPullParser.TEXT -> if (list != null) {
                    val t = p.text.trim()
                    when (tag) {
                        "hostname" -> host = t
                        "port" -> port = t.toIntOrNull() ?: 0
                        "socketType" -> sec = t
                        "username" -> user = t
                    }
                }
                XmlPullParser.END_TAG -> {
                    if ((p.name == "incomingServer" || p.name == "outgoingServer") && list != null) {
                        val s = when (sec.uppercase()) {
                            "SSL" -> SEC_SSL
                            "STARTTLS" -> SEC_STARTTLS
                            else -> SEC_NONE
                        }
                        if (host.isNotEmpty() && port > 0) list.add(Srv(host, port, s, user))
                        list = null
                    }
                    tag = ""
                }
            }
        }
        val i = imap.firstOrNull { it.sec != SEC_NONE } ?: return null
        val o = smtp.firstOrNull { it.sec != SEC_NONE } ?: return null
        val userTemplate = if (i.user.contains("%EMAILLOCALPART%")) "%LOCAL%" else "%EMAIL%"
        return ServerConfig(i.host, i.port, i.sec, o.host, o.port, o.sec, userTemplate)
    }

    private fun guess(domain: String): Provider? {
        val imap = listOf("imap.$domain", "mail.$domain").firstOrNull { tlsReachable(it, 993) } ?: return null
        val smtp = listOf("smtp.$domain", "mail.$domain").firstNotNullOfOrNull { h ->
            when {
                tlsReachable(h, 465) -> h to (465 to SEC_SSL)
                plainReachable(h, 587) -> h to (587 to SEC_STARTTLS)
                else -> null
            }
        } ?: return null
        return Provider("custom", domain, ServerConfig(imap, 993, SEC_SSL, smtp.first, smtp.second.first, smtp.second.second))
    }

    private fun tlsReachable(host: String, port: Int): Boolean = runCatching {
        (SSLSocketFactory.getDefault().createSocket() as SSLSocket).use { s ->
            s.connect(InetSocketAddress(host, port), 5000)
            s.soTimeout = 5000
            s.startHandshake()
            HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)
        }
    }.getOrDefault(false)

    private fun plainReachable(host: String, port: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(InetSocketAddress(host, port), 5000); true }
    }.getOrDefault(false)
}
