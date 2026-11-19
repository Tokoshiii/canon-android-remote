package dev.canonremote.app.ui.connection

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.canonremote.app.relay.RelayLabels
import dev.canonremote.app.relay.RelayLink
import dev.canonremote.app.relay.RelayUiState
import dev.canonremote.app.relay.RuntimePermissions
import dev.canonremote.app.remote.qrBitmap

private const val NOTIFICATIONS_DENIED =
    "Sans l'autorisation des notifications, le relais fonctionne mais sa notification reste masquée."
private const val HOTSPOT_DENIED =
    "Le point d'accès a besoin de l'autorisation « Appareils à proximité » (ou de la localisation)."
private const val BLUETOOTH_DENIED =
    "Le relais Bluetooth a besoin de l'autorisation « Appareils à proximité »."

private fun linkPermissions(link: RelayLink): List<String> = when (link) {
    RelayLink.WIFI -> emptyList()
    RelayLink.HOTSPOT -> RuntimePermissions.wifi(Build.VERSION.SDK_INT)
    RelayLink.BLUETOOTH -> RuntimePermissions.bluetoothRelay(Build.VERSION.SDK_INT)
}

private fun linkDenial(link: RelayLink): String =
    if (link == RelayLink.HOTSPOT) HOTSPOT_DENIED else BLUETOOTH_DENIED

@Composable
fun RelayCard(relay: RelayUiState, actions: ConnectionActions, permissions: PermissionRequester) {
    fun start() {
        // Links switched on earlier but whose permission is now missing are switched off first.
        val missingLinks = relay.links.filterNot { permissions.isGranted(linkPermissions(it)) }
        fun next(index: Int) {
            if (index == missingLinks.size) {
                permissions.request(
                    permissions = RuntimePermissions.notifications(Build.VERSION.SDK_INT),
                    denial = NOTIFICATIONS_DENIED,
                    onResult = { actions.startRelay() },
                )
                return
            }
            val link = missingLinks[index]
            permissions.request(permissions = linkPermissions(link), denial = linkDenial(link)) { granted ->
                if (!granted) actions.setRelayLink(link, false)
                next(index + 1)
            }
        }
        next(0)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Relais", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                label = "Activer le relais",
                checked = relay.running,
                onCheckedChange = { on -> if (on) start() else actions.stopRelay() },
            )
            HorizontalDivider()
            Text(text = "Liaisons", style = MaterialTheme.typography.labelLarge)
            RelayLink.entries.forEach { link ->
                SwitchRow(
                    label = link.label,
                    checked = link in relay.links,
                    onCheckedChange = { on ->
                        if (on) {
                            permissions.request(permissions = linkPermissions(link), denial = linkDenial(link)) { granted ->
                                if (granted) actions.setRelayLink(link, true)
                            }
                        } else {
                            actions.setRelayLink(link, false)
                        }
                    },
                )
                if (link == RelayLink.HOTSPOT && relay.hotspotStarting) {
                    Text(text = "Démarrage du point d'accès…", style = MaterialTheme.typography.bodySmall)
                }
                relay.linkErrors[link]?.let { error ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        if (link == RelayLink.BLUETOOTH && relay.running) {
                            TextButton(onClick = actions::restartBluetoothLink) { Text(text = "Relancer") }
                        }
                    }
                }
            }
            if (relay.running) {
                HorizontalDivider()
                relay.pairingCode?.let { code ->
                    Text(text = "Code d'appairage", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = code.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displayMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                relay.qrPayload?.let { payload ->
                    val uri = payload.toUri()
                    val bitmap = remember(uri) { qrBitmap(uri) }
                    Image(
                        bitmap = bitmap,
                        contentDescription = "QR code du relais",
                        modifier = Modifier
                            .size(240.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                    Text(
                        text = "Scanne ce code avec la télécommande",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                Text(text = RelayLabels.client(relay), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
