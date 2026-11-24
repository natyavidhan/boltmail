package dev.natya.boltmail

import dev.natya.boltmail.mail.Dialer
import dev.natya.boltmail.mail.MailErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

class DialerTest {
    private val v6 = InetAddress.getByName("::1")
    private val v4 = InetAddress.getByName("127.0.0.1")

    @Test
    fun `falls back to the address that answers`() {
        // Only IPv4 is listening, like a network where the server's IPv6 address is unreachable.
        ServerSocket(0, 50, v4).use { server ->
            val picked = Dialer.pick("mail.test", server.localPort, 3000) { listOf(v6, v4) }
            assertEquals(v4, picked)
        }
    }

    @Test
    fun `reports how every address failed`() {
        val closedPort = ServerSocket(0, 50, v4).use { it.localPort }
        val e = assertThrows(Dialer.DialException::class.java) {
            Dialer.pick("mail.test", closedPort, 2000) { listOf(v6, v4) }
        }
        assertEquals(2, e.attempts.size)
        assertTrue(e.message!!, e.message!!.contains("IPv4 127.0.0.1"))
    }

    @Test
    fun `single address is returned without probing`() {
        assertEquals(v4, Dialer.pick("mail.test", 1, 1000) { listOf(v4) })
    }

    @Test
    fun `interleaves address families starting with the first`() {
        val a6 = InetAddress.getByName("2001:db8::1")
        val b6 = InetAddress.getByName("2001:db8::2")
        val a4 = InetAddress.getByName("192.0.2.1")
        val b4 = InetAddress.getByName("192.0.2.2")
        assertEquals(listOf(a6, a4, b6, b4), Dialer.interleave(listOf(a6, b6, a4, b4)))
        assertEquals(listOf(a4, a6, b4), Dialer.interleave(listOf(a4, b4, a6)))
    }

    @Test
    fun `errors say what happened on each address`() {
        val e = Dialer.DialException(
            "imap.example.com", 993,
            listOf(
                InetAddress.getByName("2001:db8::1") to SocketTimeoutException("connect timed out"),
                InetAddress.getByName("192.0.2.1") to ConnectException("failed to connect (ECONNREFUSED)"),
            ),
        )
        val text = MailErrors.describe(e, "imap.example.com")
        assertTrue(text, text.contains("IPv6 timed out"))
        assertTrue(text, text.contains("IPv4 refused"))
    }
}
