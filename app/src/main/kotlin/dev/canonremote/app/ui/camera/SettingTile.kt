package dev.canonremote.app.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.canonremote.core.ChoiceModel

private val TILE_HEIGHT = 56.dp
private val TILE_SHAPE = RoundedCornerShape(20.dp)
private const val DISABLED_ALPHA = 0.38f

/** Compact tile opening a choice sheet (Balance des blancs). */
@Composable
fun SettingTile(tile: ChoiceModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        enabled = tile.enabled,
        shape = TILE_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.height(TILE_HEIGHT),
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (tile.enabled) 1f else DISABLED_ALPHA)
                .padding(horizontal = 14.dp),
        ) {
            Text(
                text = tile.title,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tile.valueLabel,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Compact tile with a switch (Live view); the whole tile toggles. */
@Composable
fun SwitchTile(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        shape = TILE_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.height(TILE_HEIGHT),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .padding(horizontal = 14.dp),
        ) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}
