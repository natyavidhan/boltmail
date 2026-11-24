package app.boltmail

import app.boltmail.mail.Addr
import app.boltmail.mail.Composer
import app.boltmail.mail.Dns
import app.boltmail.mail.HtmlText
import app.boltmail.mail.MimeParser
import app.boltmail.mail.Providers
import app.boltmail.mail.Subjects
import app.boltmail.ui.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.MimeMessage

class HtmlTextTest {
    @Test
    fun `strips markup, styles and decodes entities`() {
        val html = """<html><head><style>p{color:red}</style><title>x</title></head>
            <body><p>Hi&nbsp;Riya,</p><p>Price: &#8377;1,499 &amp; tax&hellip;</p><br><div>Bye</div>
            <script>alert(1)</script></body></html>"""
        val text = HtmlText.toText(html)
        assertFalse(text.contains("color:red"))
        assertFalse(text.contains("alert"))
        assertTrue(text.contains("Hi Riya,"))
        assertTrue(text.contains("Price: ₹1,499 & tax…"))
        assertTrue(text.contains("Bye"))
    }

    @Test
    fun `newsletter preheader filler disappears`() {
        val html = "<div>Big sale&zwnj;&nbsp;&zwnj;&nbsp;&#847;&zwnj;</div><p>Today only</p>"
        val text = HtmlText.toText(html)
        assertFalse(text.any { it == '‌' || it == '͏' })
        assertEquals("Big sale Today only", text.split(Regex("\\s+")).joinToString(" "))
        assertEquals("Big sale Today only", HtmlText.snippet(text))
    }

    @Test
    fun `snippet skips quotes and reply headers`() {
        val text = """
            Works for us, see you at 4.

            On Tue, 24 Nov 2026 at 9:41, Riya Sharma <riya@example.com> wrote:
            > Can we move the call?
        """.trimIndent()
        assertEquals("Works for us, see you at 4.", HtmlText.snippet(text))
    }

    @Test
    fun `snippet starts after a leading quote header in forwards`() {
        val text = "---------- Forwarded message ---------\nFrom: a@b.com\nHello there"
        assertEquals("From: a@b.com Hello there", HtmlText.snippet(text))
    }

    @Test
    fun `detects remote images but not inline ones`() {
        assertTrue(HtmlText.hasRemoteContent("""<img src="https://t.example.com/p.gif">"""))
        assertTrue(HtmlText.hasRemoteContent("""<td style="background:url('//cdn.x/y.png')">"""))
        assertFalse(HtmlText.hasRemoteContent("""<img src="cid:logo123">"""))
        assertFalse(HtmlText.hasRemoteContent("""<a href="https://example.com">link</a>"""))
    }

    @Test
    fun `sanitize removes scripts, frames and refresh`() {
        val out = HtmlText.sanitize("""<meta http-equiv="refresh" content="0;url=x"><p>a</p><script>x()</script><iframe src="y"></iframe>""")
        assertEquals("<p>a</p>", out)
    }
}

class AddrTest {
    @Test
    fun `splits typed recipients respecting quotes`() {
        val (good, bad) = Addr.parseInput("\"Sharma, Riya\" <riya@example.com>, ankit@example.in; nope, ")
        assertEquals(listOf("riya@example.com", "ankit@example.in"), good.map { it.address })
        assertEquals("Sharma, Riya", good[0].personal)
        assertEquals(listOf("nope"), bad)
    }

    @Test
    fun `reply goes to sender, reply all adds everyone except me`() {
        val mine = setOf("hello@studio.in")
        val (to, cc) = Addr.replyRecipients(
            "Riya", "riya@x.com", "", "hello@studio.in, ankit@y.com", "boss@z.com, HELLO@studio.in", mine, all = true,
        )
        assertEquals(listOf("riya@x.com"), to.map { it.address })
        assertEquals(listOf("ankit@y.com", "boss@z.com"), cc.map { it.address })
        val (to2, cc2) = Addr.replyRecipients("Riya", "riya@x.com", "list@x.com", "hello@studio.in", "", mine, all = false)
        assertEquals(listOf("list@x.com"), to2.map { it.address })
        assertTrue(cc2.isEmpty())
    }

    @Test
    fun `replying to my own sent mail goes to the original recipients`() {
        val (to, _) = Addr.replyRecipients("Me", "hello@studio.in", "", "client@x.com", "", setOf("hello@studio.in"), all = false)
        assertEquals(listOf("client@x.com"), to.map { it.address })
    }

    @Test
    fun `subject prefixes collapse`() {
        assertEquals("Re: Shoot schedule", Subjects.reply("RE: Re: Fwd: Shoot schedule"))
        assertEquals("Fwd: Invoice", Subjects.forward("Fw: Invoice"))
        assertEquals("Re: ", Subjects.reply(""))
    }

    @Test
    fun `email validation`() {
        assertTrue(Addr.isEmail("hello@studio.co.in"))
        assertFalse(Addr.isEmail("hello@localhost"))
        assertFalse(Addr.isEmail("not an email"))
    }
}

class MimeParserTest {
    private val session = Session.getInstance(Properties())

    private fun msg(raw: String) = MimeMessage(session, raw.replace("\n", "\r\n").byteInputStream())

    @Test
    fun `alternative plus attachment`() {
        val m = msg(
            """
            From: Riya <riya@x.com>
            To: hello@studio.in
            Subject: Call sheet
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="AAA"

            --AAA
            Content-Type: multipart/alternative; boundary="BBB"

            --BBB
            Content-Type: text/plain; charset=utf-8

            Hello in plain ₹
            --BBB
            Content-Type: text/html; charset=utf-8

            <p>Hello in <b>HTML</b></p>
            --BBB--
            --AAA
            Content-Type: application/pdf; name="call sheet.pdf"
            Content-Disposition: attachment; filename="call sheet.pdf"
            Content-Transfer-Encoding: base64

            JVBERi0xLjQK
            --AAA--
            """.trimIndent(),
        )
        val r = MimeParser.parse(m)
        assertTrue(r.text!!.contains("Hello in plain ₹"))
        assertTrue(r.html!!.contains("<b>HTML</b>"))
        assertEquals(1, r.items.size)
        val att = r.items[0]
        assertEquals("call sheet.pdf", att.name)
        assertEquals("application/pdf", att.mime)
        assertEquals("1", att.path)
        assertFalse(att.inline)
        assertTrue(r.hasAttachments)
        val part = MimeParser.find(m, att.path)
        assertNotNull(part)
        val bytes = ByteArrayOutputStream().also { part!!.inputStream.copyTo(it) }.toByteArray()
        assertEquals("%PDF-1.4\n", String(bytes))
    }

    @Test
    fun `inline image with content id is not an attachment`() {
        val m = msg(
            """
            Subject: Logo
            MIME-Version: 1.0
            Content-Type: multipart/related; boundary="R"

            --R
            Content-Type: text/html; charset=us-ascii

            <img src="cid:logo@x">
            --R
            Content-Type: image/png
            Content-ID: <logo@x>
            Content-Transfer-Encoding: base64

            iVBORw0KGgo=
            --R--
            """.trimIndent(),
        )
        val r = MimeParser.parse(m)
        assertEquals(1, r.items.size)
        assertEquals("logo@x", r.items[0].cid)
        assertTrue(r.items[0].inline)
        assertFalse(r.hasAttachments)
    }

    @Test
    fun `unknown charset falls back gracefully`() {
        val m = msg(
            """
            Subject: Odd
            MIME-Version: 1.0
            Content-Type: text/plain; charset=x-made-up-charset

            plain words
            """.trimIndent(),
        )
        assertEquals("plain words", MimeParser.parse(m).text!!.trim())
    }

    @Test
    fun `structure-only parse reads no text`() {
        val m = msg("Subject: x\nContent-Type: text/plain\n\nbody")
        val r = MimeParser.parse(m, fetchText = false)
        assertNull(r.text)
        assertTrue(r.items.isEmpty())
    }
}

class DnsTest {
    @Test
    fun `parses compressed MX answers in preference order`() {
        val q = Dns.buildQuery("example.in", 15)
        val out = ByteArrayOutputStream()
        out.write(q, 0, 2) // id
        out.write(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 1, 0, 2, 0, 0, 0, 0))
        out.write(q, 12, q.size - 12) // question
        fun answer(pref: Int, host: String) {
            out.write(byteArrayOf(0xC0.toByte(), 12)) // name -> question name
            out.write(byteArrayOf(0, 15, 0, 1, 0, 0, 0x0E, 0x10))
            val labels = ByteArrayOutputStream()
            for (l in host.split('.')) {
                labels.write(l.length); labels.write(l.toByteArray())
            }
            labels.write(0)
            val rdata = byteArrayOf((pref shr 8).toByte(), pref.toByte()) + labels.toByteArray()
            out.write(byteArrayOf((rdata.size shr 8).toByte(), rdata.size.toByte()))
            out.write(rdata)
        }
        answer(20, "mx2.hostinger.com")
        answer(10, "mx1.hostinger.com")
        assertEquals(listOf("mx1.hostinger.com", "mx2.hostinger.com"), Dns.parseMx(out.toByteArray()))
        assertEquals("hostinger", Providers.byMx("mx1.hostinger.com")?.id)
    }

    @Test
    fun `garbage does not crash`() {
        assertTrue(Dns.parseMx(byteArrayOf(1, 2, 3)).isEmpty())
    }
}

class ProvidersTest {
    @Test
    fun `known domains resolve instantly`() {
        assertTrue(Providers.byEmail("me@gmail.com")!!.google)
        assertNotNull(Providers.byEmail("me@proton.me")!!.blocked)
        assertNull(Providers.byEmail("hello@studio.example"))
        assertEquals("imap.gmail.com", Providers.byMx("aspmx.l.google.com")!!.config!!.imapHost)
        assertEquals("imap.zoho.in", Providers.byMx("mx.zoho.in")!!.config!!.imapHost)
    }
}

class ComposerTest {
    @Test
    fun `quotes every line and keeps nested quotes tight`() {
        val q = Composer.quote("On Tue, Riya wrote:", "Hi\n> earlier\nBye")
        assertEquals("On Tue, Riya wrote:\n> Hi\n>> earlier\n> Bye\n", q)
    }

    @Test
    fun `references chain grows with the parent id`() {
        assertEquals("<a@x> <b@x> <c@x>", Composer.references("<a@x> <b@x>", null, "<c@x>"))
        assertEquals("<p@x> <c@x>", Composer.references(null, "<p@x>", "<c@x>"))
        assertEquals("<c@x>", Composer.references(null, null, "<c@x>"))
    }
}

class FmtTest {
    @Test
    fun `groups by recency`() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 24, 10, 0) }.timeInMillis
        val hour = 3600_000L
        assertEquals(Fmt.Bucket.TODAY, Fmt.bucket(now - 2 * hour, now))
        assertEquals(Fmt.Bucket.YESTERDAY, Fmt.bucket(now - 20 * hour, now))
        assertEquals(Fmt.Bucket.WEEK, Fmt.bucket(now - 4 * 24 * hour, now))
        assertEquals(Fmt.Bucket.OLDER, Fmt.bucket(now - 40 * 24 * hour, now))
        assertEquals("Last 7 days", Fmt.groupLabel(now - 4 * 24 * hour, now))
    }
}
