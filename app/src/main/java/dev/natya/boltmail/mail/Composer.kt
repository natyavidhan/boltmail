package dev.natya.boltmail.mail

import android.content.Context
import dev.natya.boltmail.data.Account
import java.io.File
import java.util.Date
import java.util.UUID
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Message
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

data class DraftFile(val name: String, val mime: String, val path: String, val size: Long)

data class Draft(
    val accountId: Long,
    val to: List<InternetAddress>,
    val cc: List<InternetAddress>,
    val bcc: List<InternetAddress>,
    val subject: String,
    /** Final plain-text body, including signature and quoted text. */
    val body: String,
    val files: List<DraftFile> = emptyList(),
    val inReplyTo: String? = null,
    val references: String? = null,
    val answeredMessageId: Long? = null,
)

/** Builds outgoing messages and parks them in the outbox as .eml files. */
object Composer {
    private class BoltMimeMessage(session: Session, private val id: String) : MimeMessage(session) {
        override fun updateMessageID() = setHeader("Message-ID", id)
    }

    fun write(context: Context, a: Account, d: Draft): File {
        val session = Session.getInstance(MailSession.baseProps())
        val domain = a.email.substringAfter('@', "boltmail.app")
        val msg = BoltMimeMessage(session, "<${UUID.randomUUID()}@$domain>")
        msg.setFrom(InternetAddress(a.email, a.name.ifBlank { null }, "UTF-8"))
        msg.setRecipients(Message.RecipientType.TO, d.to.toTypedArray())
        if (d.cc.isNotEmpty()) msg.setRecipients(Message.RecipientType.CC, d.cc.toTypedArray())
        if (d.bcc.isNotEmpty()) msg.setRecipients(Message.RecipientType.BCC, d.bcc.toTypedArray())
        msg.setSubject(d.subject, "UTF-8")
        msg.sentDate = Date()
        d.inReplyTo?.let { msg.setHeader("In-Reply-To", it) }
        d.references?.takeIf { it.isNotBlank() }?.let { msg.setHeader("References", it) }
        if (d.files.isEmpty()) {
            msg.setText(d.body, "UTF-8")
        } else {
            val mp = MimeMultipart("mixed")
            mp.addBodyPart(MimeBodyPart().apply { setText(d.body, "UTF-8") })
            for (f in d.files) {
                val file = File(f.path)
                mp.addBodyPart(
                    MimeBodyPart().apply {
                        dataHandler = DataHandler(object : FileDataSource(file) {
                            override fun getContentType() = f.mime
                        })
                        fileName = f.name
                        disposition = Part.ATTACHMENT
                    },
                )
            }
            msg.setContent(mp)
        }
        msg.saveChanges()
        val dir = File(context.filesDir, "outbox").apply { mkdirs() }
        val out = File(dir, "${UUID.randomUUID()}.eml")
        out.outputStream().use { msg.writeTo(it) }
        return out
    }

    /** "> " quoted text with an attribution line, for replies. */
    fun quote(attribution: String, text: String): String = buildString {
        append(attribution).append('\n')
        for (line in text.trimEnd().lineSequence()) {
            append(if (line.startsWith(">")) ">" else "> ").append(line).append('\n')
        }
    }

    fun references(refs: String?, inReplyTo: String?, messageId: String?): String? {
        val ids = (refs ?: inReplyTo ?: "").split(Regex("\\s+")).filter { it.startsWith("<") }.toMutableList()
        if (messageId != null && messageId !in ids) ids += messageId
        return ids.takeLast(20).joinToString(" ").ifBlank { null }
    }
}
