package app.boltmail.mail

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

/**
 * Remembers every socket a mail connection opens so another thread can kill the connection
 * instantly. A read blocked on a dead TCP connection (a NAT that silently dropped it) only
 * unblocks when the socket is closed.
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

class TrackingSSLSocketFactory(
    private val delegate: SSLSocketFactory,
    private val tracker: SocketTracker,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(): Socket = tracker.track(delegate.createSocket())
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        tracker.track(s)
        return tracker.track(delegate.createSocket(s, host, port, autoClose))
    }
    override fun createSocket(host: String, port: Int): Socket = tracker.track(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        tracker.track(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = tracker.track(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        tracker.track(delegate.createSocket(address, port, localAddress, localPort))
}

class TrackingSocketFactory(private val tracker: SocketTracker) : SocketFactory() {
    private val delegate: SocketFactory = getDefault()
    override fun createSocket(): Socket = tracker.track(delegate.createSocket())
    override fun createSocket(host: String, port: Int): Socket = tracker.track(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        tracker.track(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = tracker.track(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        tracker.track(delegate.createSocket(address, port, localAddress, localPort))
}
