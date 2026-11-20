package dev.canonremote.app.ui.camera

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Sub-tabs of the Appareil screen. */
enum class CameraTab(val label: String) { BASIC("Basique"), ADVANCED("Avancé") }

private val CONNECTED_DOT = Color(0xFF2E9D5B)

/** Basique / Avancé on the left; the camera's mode and the connection status on the right. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraHeader(
    tab: CameraTab,
    onTabChange: (CameraTab) -> Unit,
    modeLabel: String?,
    statusLabel: String,
    connected: Boolean,
    onStatusClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tabs = CameraTab.entries
        SingleChoiceSegmentedButtonRow {
            tabs.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = item == tab,
                    onClick = { onTabChange(item) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                    label = { Text(text = item.label, maxLines = 1) },
                )
            }
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            modeLabel?.let { ModeChip(label = it) }
            StatusChip(
                label = statusLabel,
                connected = connected,
                onClick = onStatusClick,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** Position of the camera's mode dial (M, Av, Tv, P, B, scene…). Read-only: the dial is mechanical. */
@Composable
private fun ModeChip(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .height(32.dp)
            .widthIn(min = 48.dp)
            .semantics { contentDescription = "Mode de la molette du boîtier : $label" },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** Connection status; a touch opens the Connexion tab. */
@Composable
private fun StatusChip(label: String, connected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.height(32.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (connected) CONNECTED_DOT else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    ),
            )
            Text(text = label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
