package app.boltmail.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.boltmail.R
import app.boltmail.ui.LocalToaster
import app.boltmail.ui.theme.Bolt
import androidx.compose.material3.MaterialTheme.typography as type

@Composable
fun BoltSnackbarHost(modifier: Modifier = Modifier) {
    val host = LocalToaster.current.host
    SnackbarHost(host, modifier) { data ->
        Snackbar(
            data,
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            actionColor = MaterialTheme.colorScheme.inversePrimary,
        )
    }
}

/** Plain top bar: back button, optional title, actions on the right. Sits under the status bar. */
@Composable
fun TopBar(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    @DrawableRes backIcon: Int = R.drawable.ic_arrow_back,
    backLabel: String = "Back",
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(60.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconBtn(backIcon, backLabel, onBack)
        if (title != null) {
            Text(
                title,
                style = type.titleLarge,
                color = Bolt.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = if (onBack != null) 4.dp else 20.dp)
                    .weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
}

enum class Tone { Accent, Danger, Neutral }

/** An inline notice inside a stack: icon, short title, one sentence, up to two actions. */
@Composable
fun Notice(
    shape: Shape,
    @DrawableRes icon: Int,
    title: String,
    text: String?,
    tone: Tone = Tone.Accent,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val c = Bolt.colors
    val (chipBg, chipFg) = when (tone) {
        Tone.Accent -> c.accentSoft to c.onAccentSoft
        Tone.Danger -> c.dangerSoft to c.onDangerSoft
        Tone.Neutral -> c.raised to c.ink2
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(chipBg), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = chipFg, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = type.titleSmall, color = c.ink)
            if (!text.isNullOrEmpty()) {
                Text(text, style = type.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 2.dp))
            }
            if (action != null || secondary != null) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (action != null && onAction != null) TextAction(action, onAction, c.accent)
                    if (secondary != null && onSecondary != null) TextAction(secondary, onSecondary, c.ink2)
                }
            }
        }
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, color: Color = Bolt.colors.accent, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = type.labelLarge, color = color)
    }
}
