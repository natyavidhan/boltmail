package dev.natya.boltmail.mail

import javax.mail.Address
import javax.mail.internet.InternetAddress

/** Address lists are stored as RFC 822 strings ("Name <a@b>, c@d") in the database. */
object Addr {
    private val emailRe = Regex("^[^@\\s<>()\\[\\],;:\"]+@[^@\\s<>()\\[\\],;:\"]+\\.[^@\\s<>()\\[\\],;:\"]{2,}$")

    fun format(addrs: Array<out Address>?): String =
        addrs?.mapNotNull { it as? InternetAddress }?.joinToString(", ") { it.toUnicodeString() } ?: ""

    fun format(list: List<InternetAddress>): String = list.joinToString(", ") { it.toUnicodeString() }

    fun parse(s: String?): List<InternetAddress> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching { InternetAddress.parseHeader(s, false).toList() }.getOrDefault(emptyList())
            .filter { !it.address.isNullOrBlank() }
    }

    /** Splits what the user typed into valid addresses and the tokens that aren't addresses. */
    fun parseInput(s: String): Pair<List<InternetAddress>, List<String>> {
        val good = mutableListOf<InternetAddress>()
        val bad = mutableListOf<String>()
        for (token in splitInput(s)) {
            val a = runCatching { InternetAddress.parseHeader(token, false).singleOrNull() }.getOrNull()
            if (a != null && isEmail(a.address)) good += a else bad += token
        }
        return good to bad
    }

    /** Splits on commas and semicolons that aren't inside quotes or angle brackets. */
    fun splitInput(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var angle = false
        for (c in s) {
            when {
                c == '"' -> { quoted = !quoted; cur.append(c) }
                c == '<' && !quoted -> { angle = true; cur.append(c) }
                c == '>' && !quoted -> { angle = false; cur.append(c) }
                (c == ',' || c == ';' || c == '\n') && !quoted && !angle -> {
                    cur.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add); cur.clear()
                }
                else -> cur.append(c)
            }
        }
        cur.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }

    fun isEmail(s: String?) = s != null && emailRe.matches(s.trim())

    fun name(a: InternetAddress): String = a.personal?.trim()?.ifBlank { null } ?: a.address.orEmpty()

    /** "Riya, Ankit and 2 others" style summary of a stored address list. */
    fun names(list: String, max: Int = 2): String {
        val parsed = parse(list)
        if (parsed.isEmpty()) return ""
        val first = parsed.take(max).map { name(it).substringBefore(' ').ifBlank { name(it) } }
        val rest = parsed.size - first.size
        return when {
            rest <= 0 -> first.joinToString(", ")
            rest == 1 -> first.joinToString(", ") + " and 1 other"
            else -> first.joinToString(", ") + " and $rest others"
        }
    }

    fun same(a: String?, b: String?) = a != null && b != null && a.trim().equals(b.trim(), ignoreCase = true)

    /** Recipients for a reply: (to, cc). [mine] are the account's own addresses. */
    fun replyRecipients(
        fromName: String,
        fromAddress: String,
        replyTo: String,
        toList: String,
        ccList: String,
        mine: Set<String>,
        all: Boolean,
    ): Pair<List<InternetAddress>, List<InternetAddress>> {
        val me = mine.map { it.lowercase() }.toSet()
        val fromMe = fromAddress.lowercase() in me
        val sender = parse(replyTo).ifEmpty {
            listOfNotNull(runCatching { InternetAddress(fromAddress, fromName.ifBlank { null }, "UTF-8") }.getOrNull())
        }
        val seen = mutableSetOf<String>()
        // Replying to something I sent goes back to the people I sent it to.
        val to = (if (fromMe) parse(toList) else sender).filter { seen.add(it.address.lowercase()) }
        if (!all) return to to emptyList()
        val ccSource = (if (fromMe) emptyList() else parse(toList)) + parse(ccList)
        val cc = ccSource.filter { val a = it.address.lowercase(); a !in me && seen.add(a) }
        return to to cc
    }
}

object Subjects {
    private val prefix = Regex("^(\\s*(re|fw|fwd|aw|wg|sv|vs|antw|rif|tr|r)(\\[\\d+])?\\s*:\\s*)+", RegexOption.IGNORE_CASE)

    fun base(s: String) = s.replace(prefix, "").trim()
    fun reply(s: String) = "Re: " + base(s)
    fun forward(s: String) = "Fwd: " + base(s)
}
