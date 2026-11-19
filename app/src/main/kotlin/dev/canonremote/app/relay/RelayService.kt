package dev.canonremote.app.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.canonremote.app.CanonRemoteApp
import dev.canonremote.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service keeping the relay alive with the screen off or the app in the background.
 * Started by [dev.canonremote.app.CameraHub.startRelay], stopped by its stopRelay (also reached
 * from the notification's « Arrêter » action). Owns the [RelayRuntime] of the current run, and
 * while it runs a partial wake lock (USB polling, pings and watchdogs keep running with the screen
 * off) and a low-latency Wi-Fi lock (no Wi-Fi power save delaying frames and pings).
 */
class RelayService : Service() {
    private val hub get() = (application as CanonRemoteApp).hub
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runtime: RelayRuntime? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Relais", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Relais actif (pilotage par une télécommande)"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        scope.launch {
            hub.relayState.map { RelayLabels.notification(it) }.distinctUntilChanged().drop(1).collect { text ->
                if (runtime != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            hub.stopRelay()
            stopSelf()
            return START_NOT_STICKY
        }
        val state = hub.relayState.value
        // startForegroundService requires startForeground, even when the relay was stopped meanwhile.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(RelayLabels.notification(state)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            hub.log.error("Service du relais : passage au premier plan impossible", e)
            hub.stopRelay()
            stopSelf()
            return START_NOT_STICKY
        }
        val code = state.pairingCode
        if (!state.running || code == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (runtime?.pairingCode != code) {
            runtime?.release()
            runtime = RelayRuntime(context = this, hub = hub, pairingCode = code, parent = scope)
        }
        acquireLocks()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val run = runtime?.pairingCode
        runtime?.release()
        runtime = null
        releaseLocks()
        scope.cancel()
        // Stopped by the system: end this run, but never a newer one started meanwhile.
        if (run != null && hub.relayState.value.pairingCode == run) hub.stopRelay()
        super.onDestroy()
    }

    /** Held for the whole run (released in [onDestroy]); idempotent. */
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = runCatching {
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CanonRemote:relay")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.onFailure { hub.log.warn("Relais : verrou de veille indisponible (${it.message})") }.getOrNull()
        }
        if (wifiLock == null) {
            wifiLock = runCatching {
                applicationContext.getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "CanonRemote:relay")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.onFailure { hub.log.warn("Relais : verrou Wi-Fi indisponible (${it.message})") }.getOrNull()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
        wifiLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wifiLock = null
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Relais actif")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "relay"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "dev.canonremote.relay.STOP"
    }
}
