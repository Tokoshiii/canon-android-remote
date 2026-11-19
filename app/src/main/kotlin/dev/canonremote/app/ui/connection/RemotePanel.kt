package dev.canonremote.app.ui.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.canonremote.app.RelayDiscovery
import dev.canonremote.app.RemoteTarget
import dev.canonremote.app.relay.RemoteLinkUi
import dev.canonremote.app.relay.RuntimePermissions
import dev.canonremote.app.remote.DiscoveredRelay
import dev.canonremote.app.remote.QrPayload
import dev.canonremote.app.remote.bondedDevices

private const val JOIN_DENIED =
    "Rejoindre le point d'accès du relais demande l'autorisation « Appareils à proximité » (ou la localisation)."
private const val BLUETOOTH_DENIED =
    "La connexion Bluetooth au relais demande l'autorisation « Appareils à proximité »."
private const val NOT_A_RELAY_QR = "Ce QR code n'est pas celui d'un relais Canon Remote."

@Composable
fun RemotePanel(link: RemoteLinkUi, actions: ConnectionActions, permissions: PermissionRequester) {
    val context = LocalContext.current
    var codeFor by remember { mutableStateOf<DiscoveredRelay?>(null) }

    fun connectPayload(payload: QrPayload) {
        val target = RemoteTarget.Wifi(
            host = payload.host,
            port = payload.port,
            code = payload.code,
            ssid = payload.ssid,
            password = payload.password,
        )
        if (payload.ssid == null) {
            actions.connectRemote(target)
        } else {
            permissions.request(permissions = RuntimePermissions.wifi(Build.VERSION.SDK_INT), denial = JOIN_DENIED) { granted ->
                if (granted) actions.connectRemote(target)
            }
        }
    }

    LinkStatusCard(link = link, onReconnect = actions::reconnectRemote, onDisconnect = actions::disconnectRemote)
    WifiRelaysCard(actions = actions, onPick = { codeFor = it })
    Button(
        onClick = {
            scanQr(
                context = context,
                onPayload = ::connectPayload,
                onError = { permissions.show(it) },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = "Scanner un QR code")
    }
    BluetoothRelaysCard(actions = actions, permissions = permissions)

    codeFor?.let { relay ->
        CodeDialog(
            relay = relay,
            onConnect = { code ->
                codeFor = null
                actions.connectRemote(RemoteTarget.Wifi(host = relay.host, port = relay.port, code = code))
            },
            onDismiss = { codeFor = null },
        )
    }
}

private fun scanQr(context: Context, onPayload: (QrPayload) -> Unit, onError: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    GmsBarcodeScanning.getClient(context, options)
        .startScan()
        .addOnSuccessListener { barcode ->
            val payload = barcode.rawValue?.let(QrPayload::parse)
            if (payload == null) onError(NOT_A_RELAY_QR) else onPayload(payload)
        }
        .addOnFailureListener { e ->
            onError("Scanner de QR code indisponible : ${e.message ?: "erreur inconnue"}")
        }
}

@Composable
private fun LinkStatusCard(link: RemoteLinkUi, onReconnect: () -> Unit, onDisconnect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Liaison avec le relais", style = MaterialTheme.typography.titleMedium)
            Text(
                text = link.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (link.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            link.detail?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
            if (link.canReconnect || link.canDisconnect) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (link.canReconnect) Button(onClick = onReconnect) { Text(text = "Se reconnecter") }
                    if (link.canDisconnect) OutlinedButton(onClick = onDisconnect) { Text(text = "Déconnecter") }
                }
            }
        }
    }
}

@Composable
private fun WifiRelaysCard(actions: ConnectionActions, onPick: (DiscoveredRelay) -> Unit) {
    val flow = remember(actions) { actions.discoverRelays() }
    val discovery by flow.collectAsStateWithLifecycle(initialValue = RelayDiscovery())
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Relais sur le Wi-Fi",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            val error = discovery.error
            when {
                error != null -> Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                discovery.relays.isEmpty() -> Text(
                    text = "Recherche des relais sur ce réseau Wi-Fi…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                else -> discovery.relays.forEach { relay ->
                    ListItem(
                        headlineContent = { Text(text = relay.name) },
                        supportingContent = { Text(text = "${relay.host}:${relay.port}") },
                        modifier = Modifier.clickable(onClick = { onPick(relay) }),
                    )
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BluetoothRelaysCard(actions: ConnectionActions, permissions: PermissionRequester) {
    val needed = remember { RuntimePermissions.bluetoothRemote(Build.VERSION.SDK_INT) }
    var devices by remember { mutableStateOf<List<BluetoothDevice>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    fun load() {
        val adapter = actions.bluetoothAdapter()
        when {
            adapter == null -> {
                status = "Bluetooth indisponible sur cet appareil"
                devices = emptyList()
            }
            !adapter.isEnabled -> {
                status = "Active le Bluetooth, puis touche Actualiser"
                devices = emptyList()
            }
            else -> {
                val bonded = bondedDevices(adapter)
                status = if (bonded.isEmpty()) {
                    "Aucun appareil appairé. Appaire d'abord les deux téléphones dans les réglages Bluetooth."
                } else {
                    null
                }
                devices = bonded
            }
        }
    }

    LaunchedEffect(Unit) { if (permissions.isGranted(needed)) load() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Relais Bluetooth appairés",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = "Sans live view (Bluetooth trop lent).",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            devices.orEmpty().forEach { device ->
                ListItem(
                    headlineContent = { Text(text = device.name ?: device.address) },
                    supportingContent = { Text(text = device.address) },
                    modifier = Modifier.clickable(onClick = {
                        permissions.request(permissions = needed, denial = BLUETOOTH_DENIED) { granted ->
                            if (granted) actions.connectRemote(RemoteTarget.Bluetooth(device))
                        }
                    }),
                )
            }
            TextButton(
                onClick = {
                    permissions.request(permissions = needed, denial = BLUETOOTH_DENIED) { granted ->
                        if (granted) load()
                    }
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(text = if (devices == null) "Afficher les appareils appairés" else "Actualiser")
            }
        }
    }
}

@Composable
private fun CodeDialog(relay: DiscoveredRelay, onConnect: (String) -> Unit, onDismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = relay.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Saisis le code d'appairage à 6 chiffres affiché sur le relais.")
                OutlinedTextField(
                    value = code,
                    onValueChange = { value -> code = value.filter { it.isDigit() }.take(6) },
                    label = { Text(text = "Code d'appairage") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(code) }, enabled = code.length == 6) { Text(text = "Connecter") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "Annuler") }
        },
    )
}
