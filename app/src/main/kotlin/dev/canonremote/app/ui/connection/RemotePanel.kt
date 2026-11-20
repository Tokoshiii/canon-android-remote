package dev.canonremote.app.ui.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.canonremote.app.RelayDiscovery
import dev.canonremote.app.RemoteTarget
import dev.canonremote.app.relay.RemoteLinkUi
import dev.canonremote.app.relay.RuntimePermissions
import dev.canonremote.app.remote.BondState
import dev.canonremote.app.remote.BluetoothDeviceList
import dev.canonremote.app.remote.BtDevice
import dev.canonremote.app.remote.DiscoveredRelay
import dev.canonremote.app.remote.PairStep
import dev.canonremote.app.remote.QrPayload
import dev.canonremote.app.remote.bondedDevices
import dev.canonremote.app.remote.pairStep
import dev.canonremote.app.remote.toBtDevice

private const val JOIN_DENIED =
    "Rejoindre le point d'accès du relais demande l'autorisation « Appareils à proximité » (ou la localisation)."
private const val BLUETOOTH_DENIED =
    "La connexion Bluetooth au relais demande l'autorisation « Appareils à proximité »."
private const val NOT_A_RELAY_QR = "Ce QR code n'est pas celui d'un relais Canon Remote."

@Composable
fun RemotePanel(link: RemoteLinkUi, actions: ConnectionActions, permissions: PermissionRequester) {
    val context = LocalContext.current
    var codeFor by remember { mutableStateOf<DiscoveredRelay?>(null) }
    var askCodeAgain by remember { mutableStateOf(false) }

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

    LinkStatusCard(
        link = link,
        onReconnect = { if (link.asksCode) askCodeAgain = true else actions.reconnectRemote() },
        onDisconnect = actions::disconnectRemote,
    )
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
            title = relay.name,
            onConnect = { code ->
                codeFor = null
                actions.connectRemote(RemoteTarget.Wifi(host = relay.host, port = relay.port, code = code))
            },
            onDismiss = { codeFor = null },
        )
    }
    if (askCodeAgain) {
        CodeDialog(
            title = "Code d'appairage incorrect",
            onConnect = { code ->
                askCodeAgain = false
                actions.reconnectRemoteWithCode(code)
            },
            onDismiss = { askCodeAgain = false },
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
            if (link.canReconnect || link.asksCode || link.canDisconnect) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (link.canReconnect || link.asksCode) Button(onClick = onReconnect) { Text(text = "Se reconnecter") }
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

private const val SEARCH_HINT = "Sur le relais, touche « Rendre visible (2 min) », puis Rechercher."

@SuppressLint("MissingPermission")
@Composable
private fun BluetoothRelaysCard(actions: ConnectionActions, permissions: PermissionRequester) {
    val context = LocalContext.current
    val needed = remember { RuntimePermissions.bluetoothRemote(Build.VERSION.SDK_INT) }
    var list by remember { mutableStateOf(BluetoothDeviceList()) }
    var loaded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var bonding by remember { mutableStateOf<String?>(null) } // address being paired

    /** The adapter when Bluetooth is usable; otherwise explains why in [status]. */
    fun usableAdapter(): BluetoothAdapter? {
        val adapter = actions.bluetoothAdapter()
        when {
            adapter == null -> status = "Bluetooth indisponible sur cet appareil"
            !adapter.isEnabled -> status = "Active le Bluetooth, puis touche Actualiser"
            else -> return adapter
        }
        return null
    }

    fun loadBonded() {
        loaded = true
        val adapter = usableAdapter()
        if (adapter == null) {
            list = BluetoothDeviceList()
            return
        }
        status = null
        list = list.withBonded(bondedDevices(adapter).map { it.toBtDevice() })
    }

    fun connect(device: BluetoothDevice) {
        actions.connectRemote(RemoteTarget.Bluetooth(device))
    }

    fun withPermissions(action: () -> Unit) {
        permissions.request(permissions = needed, denial = BLUETOOTH_DENIED) { granted -> if (granted) action() }
    }

    fun search() = withPermissions {
        loadBonded()
        val adapter = usableAdapter() ?: return@withPermissions
        list = list.clearNearby()
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        if (adapter.startDiscovery()) {
            searching = true
            searched = true
        } else {
            status = "Impossible de lancer la recherche Bluetooth."
        }
    }

    fun stopSearch() {
        if (permissions.isGranted(needed)) runCatching { actions.bluetoothAdapter()?.cancelDiscovery() }
        searching = false
    }

    fun pair(entry: BtDevice) = withPermissions {
        val adapter = usableAdapter() ?: return@withPermissions
        runCatching { adapter.cancelDiscovery() } // discovery slows pairing down
        val device = adapter.getRemoteDevice(entry.address)
        val bond = when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> BondState.BONDED
            BluetoothDevice.BOND_BONDING -> BondState.BONDING
            else -> BondState.NONE
        }
        when (pairStep(bond) { device.createBond() }) {
            PairStep.CONNECT -> {
                loadBonded()
                connect(device)
            }
            PairStep.WAIT_FOR_BOND -> {
                // Also when touched again during a pairing: its BOND_BONDED then connects.
                bonding = entry.address
                status = "Appairage avec ${entry.label}… Accepte la demande sur les deux téléphones."
            }
            PairStep.FAILED -> status = "Appairage impossible avec ${entry.label}."
        }
    }

    val onBroadcast by rememberUpdatedState<(Intent) -> Unit> { intent ->
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        when (intent.action) {
            BluetoothAdapter.ACTION_DISCOVERY_STARTED -> searching = true
            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> searching = false
            BluetoothDevice.ACTION_FOUND -> if (device != null && permissions.isGranted(needed)) {
                val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
                if (!bonded) list = list.withFound(device.toBtDevice(intent.getStringExtra(BluetoothDevice.EXTRA_NAME)))
            }
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> if (device != null && permissions.isGranted(needed)) {
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val mine = device.address == bonding
                when (state) {
                    BluetoothDevice.BOND_BONDED -> {
                        loadBonded()
                        if (mine) {
                            bonding = null
                            connect(device)
                        }
                    }
                    BluetoothDevice.BOND_NONE -> {
                        loadBonded()
                        if (mine) {
                            bonding = null
                            status = "Appairage annulé ou refusé."
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onBroadcast(intent)
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // Exported: these protected broadcasts come from the Bluetooth stack's own uid, which a
        // not-exported receiver would not get; other apps cannot send them.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            stopSearch() // leaving the screen ends the discovery
        }
    }

    LaunchedEffect(Unit) { if (permissions.isGranted(needed)) loadBonded() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Relais Bluetooth",
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

            SectionLabel(text = "Appareils appairés")
            if (loaded && list.paired.isEmpty() && status == null) {
                SectionHint(text = "Aucun appareil appairé.")
            }
            list.paired.forEach { entry ->
                ListItem(
                    headlineContent = { Text(text = entry.label) },
                    supportingContent = { Text(text = entry.address) },
                    modifier = Modifier.clickable(onClick = {
                        withPermissions {
                            usableAdapter()?.let { connect(it.getRemoteDevice(entry.address)) }
                        }
                    }),
                )
            }
            TextButton(
                onClick = { withPermissions { loadBonded() } },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(text = if (loaded) "Actualiser" else "Afficher les appareils appairés")
            }

            SectionLabel(text = "Appareils à proximité")
            if (searching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            when {
                list.nearby.isNotEmpty() -> list.nearby.forEach { entry ->
                    ListItem(
                        headlineContent = { Text(text = entry.label) },
                        supportingContent = {
                            Text(text = if (entry.address == bonding) "Appairage en cours…" else "Toucher pour appairer")
                        },
                        modifier = Modifier.clickable(enabled = bonding == null, onClick = { pair(entry) }),
                    )
                }
                searching -> SectionHint(text = "Recherche en cours…")
                searched -> SectionHint(text = "Aucun appareil trouvé. $SEARCH_HINT")
                else -> SectionHint(text = SEARCH_HINT)
            }
            Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                if (searching) {
                    TextButton(onClick = ::stopSearch) { Text(text = "Arrêter") }
                } else {
                    TextButton(onClick = ::search) { Text(text = "Rechercher") }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

@Composable
private fun SectionHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun CodeDialog(title: String, onConnect: (String) -> Unit, onDismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
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
