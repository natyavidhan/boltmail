package dev.natya.boltmail.ui.message

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.viewinterop.AndroidView
import dev.natya.boltmail.mail.HtmlText
import dev.natya.boltmail.ui.theme.Bolt
import dev.natya.boltmail.ui.theme.BoltColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.material3.MaterialTheme.typography as type

private fun hex(c: Color) = String.format("#%06X", 0xFFFFFF and c.toArgb())

/** Wraps an email's HTML in a document with BoltMail's type and safe defaults. */
fun emailDocument(html: String, c: BoltColors, inline: Map<String, String>): String {
    var body = HtmlText.sanitize(html)
    for ((cid, uri) in inline) body = body.replace("cid:$cid", uri, ignoreCase = true)
    return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width">
<style>
@font-face{font-family:'BoltSans';src:url('file:///android_res/font/google_sans_flex.ttf');font-weight:300 800}
html{-webkit-text-size-adjust:100%}
body{margin:0;padding:0;font-family:'BoltSans',sans-serif;font-size:16px;line-height:1.6;color:${hex(c.ink)};background:transparent;overflow-wrap:break-word;word-wrap:break-word}
img{max-width:100%;height:auto}
pre,code{white-space:pre-wrap;font-size:14px}
blockquote{margin:10px 0;padding:0 0 0 12px;border-left:2px solid ${hex(c.line)};color:${hex(c.ink2)}}
a{color:${hex(c.accent)}}
</style></head><body>$body</body></html>"""
}

/**
 * Email HTML in a WebView with JavaScript off and, until the reader asks, no network at all
 * (remote images are how senders track opens). Links open in the browser.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlBody(html: String, loadRemote: Boolean, inline: Map<String, String>, modifier: Modifier = Modifier) {
    val c = Bolt.colors
    val scale = LocalConfiguration.current.fontScale
    val doc = remember(html, c, inline) { emailDocument(html, c, inline) }
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    databaseEnabled = false
                    setGeolocationEnabled(false)
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    mediaPlaybackRequiresUserGesture = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    textZoom = (scale * 100).toInt()
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        if (request.hasGesture() && url.scheme in setOf("http", "https", "mailto", "tel")) {
                            runCatching {
                                view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                        return true
                    }
                }
            }
        },
        update = { wv ->
            if (Build.VERSION.SDK_INT >= 33) {
                wv.settings.isAlgorithmicDarkeningAllowed = true
            } else if (Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION")
                wv.settings.forceDark = if (c.dark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
            }
            wv.settings.blockNetworkLoads = !loadRemote
            wv.settings.blockNetworkImage = !loadRemote
            val key = doc.hashCode() * 31 + if (loadRemote) 1 else 0
            if (wv.tag != key) {
                wv.tag = key
                wv.loadDataWithBaseURL("file:///android_res/", doc, "text/html", "utf-8", null)
            }
        },
    )
}

private val linkRe = Regex("""(https?://[^\s<>"']+)|([\w.+-]+@[\w-]+(\.[\w-]+)+)""")

/** Plain-text mail with tappable links and muted quote lines. */
@Composable
fun TextBody(text: String, modifier: Modifier = Modifier) {
    val c = Bolt.colors
    val annotated = remember(text, c) { linkify(text, c) }
    SelectionContainer(modifier) {
        Text(annotated, style = type.bodyLarge, color = c.ink)
    }
}

private fun linkify(text: String, c: BoltColors): AnnotatedString = buildAnnotatedString {
    val linkStyle = TextLinkStyles(SpanStyle(color = c.accent, textDecoration = TextDecoration.None))
    val lines = text.trimEnd().lines()
    lines.forEachIndexed { i, line ->
        val quoted = line.trimStart().startsWith(">")
        val start = length
        var pos = 0
        for (m in linkRe.findAll(line)) {
            append(line.substring(pos, m.range.first))
            var token = m.value
            var trailing = ""
            while (token.isNotEmpty() && token.last() in ".,;:!?)]}") {
                trailing = token.last() + trailing
                token = token.dropLast(1)
            }
            val url = if (m.groups[1] != null) token else "mailto:$token"
            withLink(LinkAnnotation.Url(url, linkStyle)) { append(token) }
            append(trailing)
            pos = m.range.last + 1
        }
        append(line.substring(pos))
        if (quoted) addStyle(SpanStyle(color = c.ink2), start, length)
        if (i < lines.lastIndex) append('\n')
    }
}

/** Splits plain text into the new part and a trailing quoted block, Gmail-style. */
fun splitQuoted(text: String): Pair<String, String?> {
    val lines = text.trimEnd().lines()
    val header = Regex("^(On|Am|Le|El) .{4,240}(wrote|schrieb|a écrit|escribió)\\s?:\\s*$", RegexOption.IGNORE_CASE)
    for (i in lines.indices) {
        val l = lines[i].trim()
        val startsQuote = header.matches(l) ||
            l.startsWith("-----Original Message", true) ||
            (l.startsWith(">") && lines.drop(i).all { it.isBlank() || it.trimStart().startsWith(">") })
        if (startsQuote && i > 0) {
            val main = lines.take(i).joinToString("\n").trimEnd()
            if (main.isNotBlank()) return main to lines.drop(i).joinToString("\n")
        }
    }
    return text to null
}

/**
 * Scrolls [state] from vertical drags anywhere in the content, including over the email's
 * WebView, which would otherwise swallow them. Horizontal drags, pinches and long presses
 * are left to the child so wide emails can pan and text can be selected.
 */
fun Modifier.dragScroll(state: ScrollState): Modifier = composed {
    val fling = ScrollableDefaults.flingBehavior()
    val scope = rememberCoroutineScope()
    val config = LocalViewConfiguration.current
    pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (state.isScrollInProgress) scope.launch { state.scroll { } }
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var dragging = false
            var acc = Offset.Zero
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                if (ev.changes.size > 1 && !dragging) break
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) {
                    if (dragging) {
                        val v = tracker.calculateVelocity().y
                        scope.launch { state.scroll { with(fling) { performFling(-v) } } }
                    }
                    break
                }
                tracker.addPosition(ch.uptimeMillis, ch.position)
                val delta = ch.positionChange()
                if (!dragging) {
                    acc += delta
                    if (ch.uptimeMillis - down.uptimeMillis > config.longPressTimeoutMillis && acc.getDistance() < config.touchSlop) break
                    if (abs(acc.y) > config.touchSlop && abs(acc.y) > abs(acc.x)) {
                        dragging = true
                        ch.consume()
                        state.dispatchRawDelta(-(acc.y - sign(acc.y) * config.touchSlop))
                    } else if (abs(acc.x) > config.touchSlop) {
                        break
                    }
                } else {
                    ch.consume()
                    state.dispatchRawDelta(-delta.y)
                }
            }
        }
    }
}
