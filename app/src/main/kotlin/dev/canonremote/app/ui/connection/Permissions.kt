package dev.canonremote.app.ui.connection

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Requests runtime permissions just before a feature needs them, and shows short explanations
 * (permission refused, invalid QR code, …) in a dialog.
 */
class PermissionRequester(private val context: Context) {
    internal var launcher: ActivityResultLauncher<Array<String>>? = null
    private var pending: ((Boolean) -> Unit)? = null

    /** Message shown in the explanation dialog, if any. */
    var message by mutableStateOf<String?>(null)
        private set

    fun isGranted(permissions: List<String>): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Calls [onResult] with true when all [permissions] are granted (asking for the missing ones);
     * on refusal, shows [denial] first.
     */
    fun request(permissions: List<String>, denial: String, onResult: (granted: Boolean) -> Unit) {
        val missing = permissions.filterNot { isGranted(listOf(it)) }
        val l = launcher
        if (missing.isEmpty() || l == null) {
            onResult(missing.isEmpty())
            return
        }
        pending = { granted ->
            if (!granted) message = denial
            onResult(granted)
        }
        l.launch(missing.toTypedArray())
    }

    fun show(text: String) {
        message = text
    }

    fun dismiss() {
        message = null
    }

    internal fun onResult(result: Map<String, Boolean>) {
        val callback = pending ?: return
        pending = null
        callback(result.isNotEmpty() && result.values.all { it })
    }
}

@Composable
fun rememberPermissionRequester(): PermissionRequester {
    val context = LocalContext.current
    val requester = remember(context) { PermissionRequester(context) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { result -> requester.onResult(result) },
    )
    requester.launcher = launcher
    return requester
}

@Composable
fun PermissionMessageDialog(requester: PermissionRequester) {
    val text = requester.message ?: return
    AlertDialog(
        onDismissRequest = { requester.dismiss() },
        confirmButton = {
            TextButton(onClick = { requester.dismiss() }) { Text(text = "OK") }
        },
        text = { Text(text = text) },
    )
}
