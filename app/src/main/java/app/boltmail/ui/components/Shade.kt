package app.boltmail.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.boltmail.ui.theme.Bolt
import androidx.compose.material3.MaterialTheme.typography as type

val OuterRadius = 24.dp
val InnerRadius = 6.dp
val Seam = 2.dp

private val shapeCache = HashMap<Long, Shape>()

/** The notification-shade grammar: big corners at the ends of a stack, small ones on the seams. */
fun shadeShape(index: Int, count: Int, outer: Dp = OuterRadius, inner: Dp = InnerRadius): Shape {
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    val key = (top.value.toLong() shl 32) or bottom.value.toLong()
    return shapeCache.getOrPut(key) { RoundedCornerShape(top, top, bottom, bottom) }
}

class ShadeScope {
    internal val items = mutableListOf<@Composable (Shape) -> Unit>()
    fun item(content: @Composable (Shape) -> Unit) {
        items += content
    }
}

/** A stack of rows separated by thin seams, inset from the screen edge. */
@Composable
fun ShadeGroup(modifier: Modifier = Modifier, build: ShadeScope.() -> Unit) {
    val scope = ShadeScope().apply(build)
    Column(modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(Seam)) {
        scope.items.forEachIndexed { i, c -> c(shadeShape(i, scope.items.size)) }
    }
}

/** A single surface in a stack. */
@Composable
fun ShadeCell(
    shape: Shape,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Bolt.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** A settings-style row: optional leading icon or tag, title, optional subtitle, trailing slot. */
@Composable
fun ShadeRow(
    shape: Shape,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    iconTint: Color? = null,
    tag: Color? = null,
    titleColor: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = Bolt.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(shape)
            .background(c.surface)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, tint = iconTint ?: c.ink2, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
        } else if (tag != null) {
            AccountTag(tag, size = 10.dp)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.titleMedium, color = titleColor ?: c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = type.bodySmall, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

/** Small label above a stack ("Today", "Accounts"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = type.labelMedium,
        color = Bolt.colors.ink2,
        modifier = modifier
            .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

@Composable
fun AccountTag(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(color))
}

@Composable
fun IconBtn(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Bolt.colors.ink2,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(painterResource(icon), contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}

/** Rounded pill button used for reply actions and primary calls to action. */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 44.dp,
) {
    val c = Bolt.colors
    val bg = when {
        !enabled -> c.surface
        primary -> c.accent
        else -> c.surface
    }
    val fg = when {
        !enabled -> c.ink3
        primary -> c.onAccent
        else -> c.ink
    }
    Row(
        modifier
            .heightIn(min = height)
            .clip(RoundedCornerShape(height / 2))
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = type.labelLarge, color = fg, maxLines = 1)
    }
}

/** Circle with an initial, tinted from the address so the same sender always gets the same color. */
@Composable
fun Monogram(name: String, key: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val c = Bolt.colors
    val palette = if (c.dark) MonoDark else MonoLight
    val (bg, fg) = palette[(key.lowercase().hashCode() and 0x7fffffff) % palette.size]
    val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(letter, style = type.titleMedium.copy(fontWeight = FontWeight(600)), color = fg)
    }
}

private val MonoLight = listOf(
    Color(0xFFFBE3D3) to Color(0xFF8A3A0B),
    Color(0xFFD6F2EE) to Color(0xFF0B5C53),
    Color(0xFFE7DEFC) to Color(0xFF4A2E9E),
    Color(0xFFDCEBFB) to Color(0xFF14508A),
    Color(0xFFFADCE7) to Color(0xFF8A1F49),
    Color(0xFFE5F0D5) to Color(0xFF3D5A12),
    Color(0xFFF6EBC8) to Color(0xFF6B5208),
)
private val MonoDark = listOf(
    Color(0xFF4A2A17) to Color(0xFFFFD9C2),
    Color(0xFF173F3A) to Color(0xFFC4F0E9),
    Color(0xFF332A54) to Color(0xFFE2D9FF),
    Color(0xFF1B3550) to Color(0xFFD2E6FB),
    Color(0xFF4A1F31) to Color(0xFFFFD6E5),
    Color(0xFF2C3A1A) to Color(0xFFDCEEC3),
    Color(0xFF453A14) to Color(0xFFF6E7B5),
)
