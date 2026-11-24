package dev.natya.boltmail.mail

import java.io.IOException
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Finds an address that actually accepts connections. Mail servers such as imap.hostinger.com publish both
 * IPv6 and IPv4 addresses, and on networks where IPv6 is broken, connecting only to the first resolved
 * address fails outright (JavaMail does exactly that). This tries every address, IPv6 and IPv4 interleaved,
 * starting the next attempt as soon as one fails or after a short head start, and keeps the first that answers.
 */
object Dialer {
    private const val HEAD_START_MS = 300L

    /** Every address failed; the message says how each one failed. */
    class DialException(val host: String, val port: Int, val attempts: List<Pair<InetAddress, IOException>>) :
        ConnectException(
            "Couldn't connect to $host:$port (" +
                attempts.joinToString("; ") { (a, e) -> "${family(a)} ${a.hostAddress}: ${reason(e)}" } + ")",
        )

    fun family(a: InetAddress) = if (a is Inet6Address) "IPv6" else "IPv4"

    fun reason(e: IOException): String {
        val msg = e.message.orEmpty()
        return when {
            e is SocketTimeoutException -> "timed out"
            msg.contains("ENETUNREACH") || msg.contains("Network is unreachable", true) -> "network unreachable"
            msg.contains("EHOSTUNREACH") || e is NoRouteToHostException -> "no route to host"
            msg.contains("ECONNREFUSED") || msg.contains("refused", true) -> "refused"
            msg.contains("EACCES") || msg.contains("Permission denied", true) -> "blocked on this device"
            msg.isNotBlank() -> msg.substringAfterLast(": ").take(60)
            else -> e.javaClass.simpleName
        }
    }

    /** Returns an address of [host] that accepted a TCP connection on [port]. Blocking. */
    fun pick(
        host: String,
        port: Int,
        timeoutMs: Int,
        resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ): InetAddress {
        val all = resolve(host)
        if (all.isEmpty()) throw UnknownHostException(host)
        if (all.size == 1) return all[0]
        val ordered = interleave(all)
        val results = LinkedBlockingQueue<Pair<InetAddress, IOException?>>()
        val probes = CopyOnWriteArrayList<Socket>()
        val failures = mutableListOf<Pair<InetAddress, IOException>>()
        var next = 0
        var running = 0

        fun start(addr: InetAddress) {
            running++
            val s = Socket()
            probes += s
            thread(isDaemon = true, name = "dial-${addr.hostAddress}") {
                val outcome = try {
                    s.connect(InetSocketAddress(addr, port), timeoutMs)
                    null
                } catch (e: IOException) {
                    e
                } finally {
                    runCatching { s.close() }
                }
                results.put(addr to outcome)
            }
        }

        val deadline = System.currentTimeMillis() + timeoutMs + ordered.size * HEAD_START_MS
        start(ordered[next++])
        try {
            while (true) {
                val wait = if (next < ordered.size) HEAD_START_MS else deadline - System.currentTimeMillis()
                val r = if (wait > 0) results.poll(wait, TimeUnit.MILLISECONDS) else null
                if (r == null) {
                    if (next < ordered.size) {
                        start(ordered[next++])
                        continue
                    }
                    break
                }
                running--
                val error = r.second ?: return r.first
                failures += r.first to error
                if (next < ordered.size) start(ordered[next++]) else if (running == 0) break
            }
        } finally {
            probes.forEach { runCatching { it.close() } }
        }
        for (a in ordered) if (failures.none { it.first == a }) failures += a to SocketTimeoutException("timed out")
        throw DialException(host, port, failures)
    }

    /** RFC 8305 style: alternate address families, starting with the resolver's first choice. */
    fun interleave(list: List<InetAddress>): List<InetAddress> {
        val firstIs6 = list.first() is Inet6Address
        val a = list.filter { (it is Inet6Address) == firstIs6 }
        val b = list.filter { (it is Inet6Address) != firstIs6 }
        return buildList {
            for (i in 0 until maxOf(a.size, b.size)) {
                a.getOrNull(i)?.let(::add)
                b.getOrNull(i)?.let(::add)
            }
        }
    }
}

/** A socket whose connect() goes through [Dialer], so it lands on an address that works. */
class DialingSocket : Socket() {
    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val isa = endpoint as? InetSocketAddress ?: return super.connect(endpoint, timeout)
        val addr = Dialer.pick(isa.hostString, isa.port, if (timeout > 0) timeout else 20_000)
        super.connect(InetSocketAddress(addr, isa.port), timeout)
    }
}
