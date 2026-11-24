package dev.natya.boltmail.mail

import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.internet.ContentType
import javax.mail.internet.MimePart
import javax.mail.internet.MimeUtility

/** Walks a MIME tree: body text for display, everything else as attachments. */
object MimeParser {
    class Item(
        val path: String,
        val name: String,
        val mime: String,
        val size: Long,
        val cid: String?,
        val inline: Boolean,
    )

    class Result {
        var html: String? = null
        var text: String? = null
        val items = mutableListOf<Item>()
        val hasAttachments get() = items.any { !it.inline }
    }

    /** With [fetchText] false only the structure is read, which costs no network round trips on IMAP. */
    fun parse(root: Part, fetchText: Boolean = true): Result {
        val r = Result()
        walk(root, "", r, fetchText, 0)
        return r
    }

    private fun walk(p: Part, path: String, r: Result, fetchText: Boolean, depth: Int) {
        if (depth > 12) return
        if (p.isMimeType("multipart/*")) {
            val mp = p.content as? Multipart ?: return
            for (i in 0 until mp.count) {
                walk(mp.getBodyPart(i), if (path.isEmpty()) "$i" else "$path.$i", r, fetchText, depth + 1)
            }
            return
        }
        val disposition = runCatching { p.disposition }.getOrNull()?.lowercase()
        val isBodyText = (p.isMimeType("text/plain") || p.isMimeType("text/html")) && disposition != Part.ATTACHMENT
        if (isBodyText) {
            if (!fetchText) return
            val s = readText(p) ?: return
            if (p.isMimeType("text/html")) {
                r.html = r.html?.let { "$it\n$s" } ?: s
            } else {
                r.text = r.text?.let { "$it\n\n$s" } ?: s
            }
            return
        }
        val mime = runCatching { ContentType(p.contentType).baseType.lowercase() }.getOrDefault("application/octet-stream")
        val cid = (p as? MimePart)?.let { runCatching { it.contentID }.getOrNull() }?.trim()?.trim('<', '>')?.ifBlank { null }
        val name = runCatching { p.fileName }.getOrNull()?.let(::decodeName)?.ifBlank { null }
            ?: defaultName(mime, p)
        val inline = cid != null && disposition != Part.ATTACHMENT && mime.startsWith("image/")
        r.items += Item(path, name, mime, decodedSize(p), cid, inline)
    }

    private fun decodedSize(p: Part): Long {
        val raw = runCatching { p.size }.getOrDefault(-1)
        if (raw <= 0) return 0
        val enc = (p as? MimePart)?.let { runCatching { it.encoding }.getOrNull() }?.lowercase()
        return if (enc == "base64") raw * 3L / 4 else raw.toLong()
    }

    private fun decodeName(s: String): String = runCatching { MimeUtility.decodeText(s) }.getOrDefault(s).trim()

    private fun defaultName(mime: String, p: Part): String {
        if (mime == "message/rfc822") {
            val subject = runCatching { (p.content as? javax.mail.Message)?.subject }.getOrNull()
            return (subject?.ifBlank { null } ?: "Attached message") + ".eml"
        }
        val ext = when (mime) {
            "text/calendar" -> "ics"
            "text/csv" -> "csv"
            "application/pdf" -> "pdf"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> mime.substringAfter('/', "bin").substringBefore('+').take(8)
        }
        return "attachment.$ext"
    }

    private fun charsetOf(p: Part): String? =
        runCatching { ContentType(p.contentType).getParameter("charset") }.getOrNull()

    fun readText(p: Part): String? {
        return try {
            when (val c = p.content) {
                is String -> c
                is InputStream -> c.use { decode(it.readBytes(), charsetOf(p)) }
                else -> null
            }
        } catch (e: UnsupportedEncodingException) {
            p.inputStream.use { decode(it.readBytes(), null) }
        }
    }

    /** Decodes with the declared charset when Java knows it, else strict UTF-8, else Windows-1252. */
    fun decode(bytes: ByteArray, charset: String?): String {
        if (charset != null) {
            val cs = runCatching { Charset.forName(MimeUtility.javaCharset(charset)) }.getOrNull()
            if (cs != null) return String(bytes, cs)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    /** Finds a part by the path recorded during [parse]. */
    fun find(root: Part, path: String): Part? {
        if (path.isEmpty()) return root
        var cur: Part = root
        for (seg in path.split('.')) {
            val i = seg.toIntOrNull() ?: return null
            val mp = cur.content as? Multipart ?: return null
            if (i >= mp.count) return null
            cur = mp.getBodyPart(i)
        }
        return cur
    }
}
