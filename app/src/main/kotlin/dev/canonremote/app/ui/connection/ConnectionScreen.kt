package dev.canonremote.app.ui.connection

import android.bluetooth.BluetoothAdapter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.canonremote.app.RelayDiscovery
import dev.canonremote.app.RemoteTarget
import dev.canonremote.app.relay.RelayLink
import dev.canonremote.app.relay.RelayUiState
import dev.canonremote.app.relay.RemoteLinkUi
import dev.canonremote.app.settings.AppMode
import dev.canonremote.app.usb.UsbStatus
import kotlinx.coroutines.flow.Flow

/** Everything the Connexion tab shows. */
data class ConnectionUi(
    val mode: AppMode,
    val usbStatus: UsbStatus,
    val cameraModel: String?,
    val serial: String?,
    val relay: RelayUiState,
    val remoteLink: RemoteLinkUi,
)

interface ConnectionActions {
    fun setMode(mode: AppMode)
    fun connect()
    fun disconnect()
    fun startRelay()
    fun stopRelay()
    fun setRelayLink(link: RelayLink, enabled: Boolean)
    fun restartBluetoothLink()
    fun connectRemote(target: RemoteTarget)
    fun reconnectRemote()
    fun disconnectRemote()
    fun discoverRelays(): Flow<RelayDiscovery>
    fun bluetoothAdapter(): BluetoothAdapter?
}

@Composable
fun ConnectionScreen(ui: ConnectionUi, actions: ConnectionActions) {
    val permissions = rememberPermissionRequester()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModeSelector(selected = ui.mode, onSelect = actions::setMode)
        when (ui.mode) {
            AppMode.DIRECT -> UsbCard(
                status = ui.usbStatus,
                cameraModel = ui.cameraModel,
                serial = ui.serial,
                onConnect = actions::connect,
                onDisconnect = actions::disconnect,
            )
            AppMode.RELAY -> {
                UsbCard(
                    status = ui.usbStatus,
                    cameraModel = ui.cameraModel,
                    serial = ui.serial,
                    onConnect = actions::connect,
                    onDisconnect = actions::disconnect,
                )
                RelayCard(relay = ui.relay, actions = actions, permissions = permissions)
            }
            AppMode.REMOTE -> RemotePanel(link = ui.remoteLink, actions = actions, permissions = permissions)
        }
    }
    PermissionMessageDialog(requester = permissions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(selected: AppMode, onSelect: (AppMode) -> Unit) {
    val modes = AppMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(text = mode.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun UsbCard(
    status: UsbStatus,
    cameraModel: String?,
    serial: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = status is UsbStatus.Connected
    val label = when (status) {
        UsbStatus.NoDevice -> "Aucun appareil détecté. Branche le 90D en USB-C."
        is UsbStatus.Detected -> "Canon détecté : ${status.name}"
        UsbStatus.PermissionDenied -> "Autorisation USB refusée"
        is UsbStatus.Connected -> "Connecté : ${cameraModel ?: status.name}" +
            (serial?.let { " (n° $it)" } ?: "")
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "USB", style = MaterialTheme.typography.titleMedium)
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (connected) {
                OutlinedButton(onClick = onDisconnect) { Text(text = "Déconnecter") }
            } else {
                Button(onClick = onConnect, enabled = status !is UsbStatus.NoDevice) { Text(text = "Connecter") }
            }
        }
    }
}
