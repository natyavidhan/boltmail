package app.boltmail.mail

/** Pure-Kotlin HTML helpers: text extraction for snippets and quoting, light hardening for display. */
object HtmlText {
    private val comments = Regex("(?s)<!--.*?-->")
    private val dropBlocks = Regex("(?is)<(script|style|head|title|noscript|template)\\b[^>]*>.*?</\\1\\s*>")
    private val br = Regex("(?i)<br\\s*/?>")
    private val blockEnd = Regex("(?i)</(p|div|tr|li|h[1-6]|blockquote|table|section|article|header|footer|ul|ol|pre)\\s*>")
    private val blockStart = Regex("(?i)<(p|div|tr|h[1-6]|blockquote|table|section|article|header|footer|ul|ol|pre)\\b[^>]*>")
    private val li = Regex("(?i)<li\\b[^>]*>")
    private val tags = Regex("(?s)<[^>]*>")
    private val entity = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});?")
    private val invisible = Regex("[\\u200B-\\u200D\\u2060\\uFEFF\\u034F\\u00AD\\u180E\\u2007]")
    private val spaces = Regex("[ \\t\\u00A0\\u2002-\\u200A\\u202F\\u205F\\u3000]+")
    private val manyBreaks = Regex("\\n{3,}")
    private val quoteHeader = Regex("^(On|Am|Le|El|Il|Op) .{4,240}(wrote|schrieb|a écrit|escribió|ha scritto|schreef)\\s?:$", RegexOption.IGNORE_CASE)
    private val remote = Regex("(?i)(?:src|background|poster)\\s*=\\s*[\"']?\\s*(?:https?:)?//|url\\(\\s*[\"']?\\s*(?:https?:)?//")

    fun toText(html: String): String {
        var s = comments.replace(html, " ")
        s = dropBlocks.replace(s, " ")
        s = br.replace(s, "\n")
        s = blockEnd.replace(s, "\n")
        s = blockStart.replace(s, "\n")
        s = li.replace(s, "\n• ")
        s = tags.replace(s, "")
        s = decodeEntities(s)
        s = invisible.replace(s, "")
        return s.lineSequence()
            .map { spaces.replace(it, " ").trim() }
            .joinToString("\n")
            .replace(manyBreaks, "\n\n")
            .trim()
    }

    fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        return entity.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codepoint) ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codepoint) ?: m.value
                else -> NAMED[e] ?: NAMED[e.lowercase()] ?: m.value
            }
        }
    }

    private fun codepoint(cp: Int): String = when (cp) {
        0, in 0xD800..0xDFFF -> ""
        in 1..0x10FFFF -> String(Character.toChars(cp))
        else -> ""
    }

    /** First readable sentence-ish text of a message body, skipping quotes and signatures. */
    fun snippet(text: String, max: Int = 200): String {
        val sb = StringBuilder()
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith(">")) continue
            if (l == "--" || l == "-- " || l == "__") break
            if (quoteHeader.matches(l) || l.startsWith("-----Original Message", true) ||
                l.startsWith("---------- Forwarded message", true)
            ) {
                if (sb.isEmpty()) continue else break
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(l)
            if (sb.length > max) break
        }
        return invisible.replace(spaces.replace(sb, " "), "").take(max).trim()
    }

    /** Hardening for a WebView that already has JavaScript and network loads disabled. */
    fun sanitize(html: String): String {
        var s = html
        s = Regex("(?is)<script\\b[^>]*>.*?</script\\s*>").replace(s, "")
        s = Regex("(?is)<(iframe|object|embed|frameset|frame|applet)\\b[^>]*>(.*?</\\1\\s*>)?").replace(s, "")
        s = Regex("(?i)<meta\\b[^>]*http-equiv[^>]*>").replace(s, "")
        s = Regex("(?i)<base\\b[^>]*>").replace(s, "")
        return s
    }

    fun hasRemoteContent(html: String) = remote.containsMatchIn(html)

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) when (c) {
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '&' -> sb.append("&amp;")
            '"' -> sb.append("&quot;")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private val NAMED = mapOf(
        "nbsp" to "\u00A0", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "zwnj" to "", "zwj" to "", "shy" to "", "lrm" to "", "rlm" to "",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "sbquo" to "‚", "bdquo" to "„",
        "mdash" to "—", "ndash" to "–", "minus" to "−", "hellip" to "…", "bull" to "•", "middot" to "·",
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "plusmn" to "±", "times" to "×",
        "divide" to "÷", "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "sup2" to "²", "sup3" to "³",
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "curren" to "¤",
        "laquo" to "«", "raquo" to "»", "lsaquo" to "‹", "rsaquo" to "›", "para" to "¶", "sect" to "§",
        "ensp" to " ", "emsp" to " ", "thinsp" to " ", "rarr" to "→", "larr" to "←", "uarr" to "↑",
        "darr" to "↓", "harr" to "↔", "iexcl" to "¡", "iquest" to "¿", "dagger" to "†", "Dagger" to "‡",
        "permil" to "‰", "prime" to "′", "Prime" to "″", "oline" to "‾", "frasl" to "⁄", "infin" to "∞",
        "ne" to "≠", "le" to "≤", "ge" to "≥", "check" to "✓", "hearts" to "♥", "star" to "☆",
        "agrave" to "à", "aacute" to "á", "acirc" to "â", "atilde" to "ã", "auml" to "ä", "aring" to "å",
        "aelig" to "æ", "ccedil" to "ç", "egrave" to "è", "eacute" to "é", "ecirc" to "ê", "euml" to "ë",
        "igrave" to "ì", "iacute" to "í", "icirc" to "î", "iuml" to "ï", "ntilde" to "ñ", "ograve" to "ò",
        "oacute" to "ó", "ocirc" to "ô", "otilde" to "õ", "ouml" to "ö", "oslash" to "ø", "ugrave" to "ù",
        "uacute" to "ú", "ucirc" to "û", "uuml" to "ü", "yacute" to "ý", "yuml" to "ÿ", "szlig" to "ß",
        "Agrave" to "À", "Aacute" to "Á", "Acirc" to "Â", "Atilde" to "Ã", "Auml" to "Ä", "Aring" to "Å",
        "AElig" to "Æ", "Ccedil" to "Ç", "Egrave" to "È", "Eacute" to "É", "Ecirc" to "Ê", "Euml" to "Ë",
        "Igrave" to "Ì", "Iacute" to "Í", "Icirc" to "Î", "Iuml" to "Ï", "Ntilde" to "Ñ", "Ograve" to "Ò",
        "Oacute" to "Ó", "Ocirc" to "Ô", "Otilde" to "Õ", "Ouml" to "Ö", "Oslash" to "Ø", "Ugrave" to "Ù",
        "Uacute" to "Ú", "Ucirc" to "Û", "Uuml" to "Ü", "Yacute" to "Ý",
    )
}
