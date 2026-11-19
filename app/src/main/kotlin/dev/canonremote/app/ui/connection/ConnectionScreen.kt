package dev.canonremote.app.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.canonremote.app.usb.UsbStatus

@Composable
fun ConnectionScreen(
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
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("USB", style = MaterialTheme.typography.titleMedium)
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (connected) {
                    OutlinedButton(onClick = onDisconnect) { Text("Déconnecter") }
                } else {
                    Button(onClick = onConnect, enabled = status !is UsbStatus.NoDevice) { Text("Connecter") }
                }
            }
        }
        for (name in listOf("Wi-Fi", "Bluetooth", "Relais")) {
            Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text("Bientôt", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
