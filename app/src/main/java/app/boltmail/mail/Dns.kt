package app.boltmail.mail

import android.net.DnsResolver
import android.os.Build
import java.io.ByteArrayOutputStream
import java.net.IDN
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** MX lookups through the system resolver (Android 10+), with a tiny DNS wire-format parser. */
object Dns {
    private const val TYPE_MX = 15
    private val executor = Executors.newSingleThreadExecutor()

    /** MX host names ordered by preference; empty when unavailable. Blocking. */
    fun mx(domain: String, timeoutMs: Long = 5000): List<String> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val query = runCatching { buildQuery(domain, TYPE_MX) }.getOrNull() ?: return emptyList()
        val latch = CountDownLatch(1)
        var answer: ByteArray? = null
        DnsResolver.getInstance().rawQuery(
            null, query, DnsResolver.FLAG_EMPTY, executor, null,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(a: ByteArray, rcode: Int) {
                    answer = a; latch.countDown()
                }

                override fun onError(e: DnsResolver.DnsException) {
                    latch.countDown()
                }
            },
        )
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return answer?.let { parseMx(it) } ?: emptyList()
    }

    fun buildQuery(domain: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val id = Random.nextInt(0, 0xFFFF)
        out.write(id shr 8); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // recursion desired
        out.write(0); out.write(1) // one question
        repeat(6) { out.write(0) }
        val ascii = IDN.toASCII(domain.trim().trimEnd('.'))
        for (label in ascii.split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            require(b.size in 1..63)
            out.write(b.size); out.write(b)
        }
        out.write(0)
        out.write(type shr 8); out.write(type and 0xFF)
        out.write(0); out.write(1) // class IN
        return out.toByteArray()
    }

    fun parseMx(msg: ByteArray): List<String> {
        val result = mutableListOf<Pair<Int, String>>()
        try {
            if (msg.size < 12) return emptyList()
            val qd = u16(msg, 4)
            val an = u16(msg, 6)
            var pos = 12
            repeat(qd) { pos = skipName(msg, pos) + 4 }
            repeat(an) {
                pos = skipName(msg, pos)
                val type = u16(msg, pos)
                val rdLen = u16(msg, pos + 8)
                val rdata = pos + 10
                if (type == TYPE_MX) result += u16(msg, rdata) to readName(msg, rdata + 2)
                pos = rdata + rdLen
            }
        } catch (_: IndexOutOfBoundsException) {
        }
        return result.sortedBy { it.first }.map { it.second.lowercase().trimEnd('.') }.filter { it.isNotEmpty() }
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun skipName(b: ByteArray, start: Int): Int {
        var i = start
        while (true) {
            val len = b[i].toInt() and 0xFF
            if (len == 0) return i + 1
            if (len and 0xC0 == 0xC0) return i + 2
            i += len + 1
        }
    }

    private fun readName(b: ByteArray, start: Int): String {
        val sb = StringBuilder()
        var i = start
        var jumps = 0
        while (true) {
            val len = b[i].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 == 0xC0) {
                if (++jumps > 16) break
                i = ((len and 0x3F) shl 8) or (b[i + 1].toInt() and 0xFF)
                continue
            }
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(b, i + 1, len, Charsets.US_ASCII))
            i += len + 1
        }
        return sb.toString()
    }
}
