package dev.natya.boltmail.mail

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.SocketFactory

/**
 * Remembers every socket a mail connection opens so another thread can kill the connection
 * instantly. A read blocked on a dead TCP connection (a NAT that silently dropped it) only
 * unblocks when the socket is closed; closing the plain socket also kills TLS layered on it.
 */
class SocketTracker {
    private val sockets = CopyOnWriteArrayList<Socket>()

    fun <T : Socket> track(s: T): T {
        sockets.add(s)
        return s
    }

    fun closeAll() {
        for (s in sockets) runCatching { s.close() }
        sockets.clear()
    }
}

/**
 * The plain-socket factory behind every IMAP and SMTP connection, whatever the security mode:
 * sockets try every address of the server ([DialingSocket]) and are tracked so they can be closed
 * from another thread. JavaMail layers TLS on top for SSL/TLS and STARTTLS.
 */
class TrackingSocketFactory(private val tracker: SocketTracker) : SocketFactory() {
    override fun createSocket(): Socket = tracker.track(DialingSocket())

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port), CONNECT_TIMEOUT)
        }

    private companion object {
        const val CONNECT_TIMEOUT = 20_000
    }
}
