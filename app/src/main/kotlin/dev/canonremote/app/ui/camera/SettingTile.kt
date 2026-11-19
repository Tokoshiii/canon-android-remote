package dev.canonremote.app.ui.camera

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import dev.canonremote.core.TileModel

@Composable
fun SettingTile(tile: TileModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(
        onClick = onClick,
        enabled = tile.enabled,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(),
        modifier = modifier.alpha(if (tile.enabled) 1f else 0.5f),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(tile.title, style = MaterialTheme.typography.labelMedium)
            Text(tile.valueLabel, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
