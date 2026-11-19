package dev.canonremote.core.remote

import dev.canonremote.core.CameraController
import dev.canonremote.core.LogBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LinkKind { WIFI, BLUETOOTH }

data class RelayClient(val name: String, val link: LinkKind)

/**
 * Serves one remote at a time over [MessageChannel]s, forwarding its commands to [camera] and
 * pushing camera state (and, on Wi-Fi, live view frames) back.
 *
 * When a session ends (channel error or EOF, [linkTimeoutMs] without any incoming message,
 * rejected handshake) the channel is closed and the camera is left untouched; commands already
 * received are still executed (in order) even if their result can no longer be delivered.
 */
class RelayServer(
    private val camera: CameraController,
    private val log: LogBus,
    private val scope: CoroutineScope,
    private val pairingCode: String,
    private val pingIntervalMs: Long = 2000,
    private val linkTimeoutMs: Long = 6000,
    private val wrongCodeDelayMs: Long = 1000,
    /** Reads the camera's current error right after a failed command (default: its state). */
    private val currentError: () -> String? = { camera.state.value.lastError },
) {
    private val _clients = MutableStateFlow<RelayClient?>(null)
    val clients: StateFlow<RelayClient?> = _clients.asStateFlow()

    private class QueuedCommand(val channel: MessageChannel, val cmd: Message.Command, val link: LinkKind)

    /**
     * Commands of all sessions, executed one at a time in arrival order by a single worker in
     * [scope]: it outlives sessions, so a command already received still reaches the camera
     * after a link loss (only its Result is lost).
     */
    private val commands = Channel<QueuedCommand>(Channel.UNLIMITED)

    init {
        scope.launch { for (q in commands) execute(q.channel, q.cmd, q.link) }
    }

    /** Starts a session on [channel]; returns immediately. */
    fun accept(channel: MessageChannel, link: LinkKind) {
        scope.launch { runSession(channel, link) }
    }

    private suspend fun runSession(channel: MessageChannel, link: LinkKind) {
        var client: RelayClient? = null
        try {
            coroutineScope {
                val end = CompletableDeferred<Unit>()
                fun stop() { end.complete(Unit) }

                /**
                 * Session child: a failure (other than cancellation) ends the session, and so does
                 * normal completion when [endsSession].
                 */
                fun child(endsSession: Boolean = true, block: suspend () -> Unit) = launch {
                    try {
                        block()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (!end.isCompleted) log.warn("Liaison télécommande interrompue : ${e.message ?: e::class.simpleName}")
                        stop()
                    }
                    if (endsSession) stop()
                }

                // Link watchdog: restarted by every incoming item.
                val activity = MutableStateFlow(0L)
                launch {
                    activity.collectLatest {
                        delay(linkTimeoutMs)
                        if (!end.isCompleted) log.warn("Télécommande : aucune réponse depuis $linkTimeoutMs ms")
                        stop()
                    }
                }

                fun startStreams() {
                    child { camera.state.collect { channel.send(Message.StateMsg(it.toRemote())) } }
                    child {
                        while (true) {
                            delay(pingIntervalMs)
                            channel.send(Message.Ping)
                        }
                    }
                    if (link != LinkKind.BLUETOOTH) {
                        child { camera.liveViewFrames.filterNotNull().conflate().collect { channel.sendJpeg(it) } }
                    }
                }

                child {
                    channel.incoming.collect { item ->
                        if (end.isCompleted) return@collect
                        activity.update { it + 1 }
                        val msg = (item as? Incoming.Msg)?.message ?: return@collect
                        if (client == null) {
                            if (msg !is Message.Hello) {
                                log.warn("Télécommande : message inattendu avant Hello")
                                stop()
                                return@collect
                            }
                            val reason = handshake(msg, link)
                            if (reason != null) {
                                log.warn("Télécommande « ${msg.clientName} » refusée : $reason")
                                // Slows down guessing the pairing code.
                                if (reason == WRONG_CODE) delay(wrongCodeDelayMs)
                                channel.send(Message.Rejected(reason))
                                stop()
                                return@collect
                            }
                            client = RelayClient(msg.clientName, link)
                            log.info("Télécommande connectée : ${msg.clientName} (${link.label})")
                            val s = camera.state.value
                            // Off the reader: Welcome first, then the state stream (immediate full state).
                            child(endsSession = false) {
                                channel.send(Message.Welcome(s.model, s.serial))
                                startStreams()
                            }
                            return@collect
                        }
                        when (msg) {
                            Message.Ping -> child(endsSession = false) { channel.send(Message.Pong) }
                            is Message.Command -> commands.trySend(QueuedCommand(channel, msg, link))
                            else -> Unit // Pong (timer already reset) or unexpected: ignored
                        }
                    }
                }

                end.await()
                coroutineContext.cancelChildren()
            }
        } finally {
            channel.close()
            client?.let {
                _clients.compareAndSet(it, null)
                log.info("Télécommande déconnectée")
            }
        }
    }

    /** Returns the rejection reason, or null after claiming the relay for this client. */
    private fun handshake(hello: Message.Hello, link: LinkKind): String? = when {
        hello.protocol != MessageCodec.PROTOCOL -> "Version incompatible"
        link == LinkKind.WIFI && hello.token != pairingCode -> WRONG_CODE
        !_clients.compareAndSet(null, RelayClient(hello.clientName, link)) -> "Relais occupé"
        else -> null
    }

    private suspend fun execute(channel: MessageChannel, cmd: Message.Command, link: LinkKind) {
        val result = try {
            val action = cmd.action
            if (action is Action.LiveView && action.on && link == LinkKind.BLUETOOTH) {
                Message.Result(cmd.id, false, "Live view indisponible en Bluetooth")
            } else {
                val ok = when (action) {
                    is Action.Set -> camera.set(action.setting, action.value)
                    Action.Shoot -> camera.shoot()
                    Action.Autofocus -> camera.autofocus()
                    is Action.Focus -> camera.focus(action.step)
                    is Action.LiveView -> camera.setLiveView(action.on)
                }
                // The camera's error is captured now: the relay's own UI may clear it at any time.
                Message.Result(cmd.id, ok, if (ok) null else currentError())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error("Commande télécommande ${cmd.id} échouée", e)
            Message.Result(cmd.id, false, e.message ?: e::class.simpleName)
        }
        try {
            channel.send(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // link gone: the remote reports the command as unconfirmed
        }
    }

    private companion object {
        const val WRONG_CODE = "Code d'appairage incorrect"
    }

    private val LinkKind.label get() = if (this == LinkKind.WIFI) "Wi-Fi" else "Bluetooth"
}
