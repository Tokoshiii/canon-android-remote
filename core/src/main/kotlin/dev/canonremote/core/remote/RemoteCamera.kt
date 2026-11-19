package dev.canonremote.core.remote

import dev.canonremote.core.CameraController
import dev.canonremote.core.CameraSetting
import dev.canonremote.core.CameraState
import dev.canonremote.core.ConnectionStatus
import dev.canonremote.core.FocusStep
import dev.canonremote.core.LogBus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Télécommande side of the relay protocol: a [CameraController] whose commands travel as
 * [Message.Command]s to a [RelayServer] and whose state mirrors the relay's [Message.StateMsg]s.
 *
 * [state]`.status` reflects the link first: CONNECTING while (re)connecting, DISCONNECTED when
 * not linked; once linked, the relay camera's own status (so a relay without camera shows
 * DISCONNECTED and the UI greys out).
 *
 * On link loss (channel error/EOF, or [linkTimeoutMs] without any incoming item) the channel is
 * closed, pending commands complete false with « <action> non confirmé — vérifie l'appareil », and
 * new connections are attempted every second for [reconnectWindowMs]. A rejection stops everything.
 *
 * [connect] should be cancellable: each call is bounded by [linkTimeoutMs] and cancelled after it;
 * a channel still returned after the timeout (non-cancellable factory) is closed and discarded.
 */
class RemoteCamera(
    private val connect: suspend () -> MessageChannel,
    val link: LinkKind,
    private val token: String?,
    private val clientName: String,
    private val log: LogBus,
    private val scope: CoroutineScope,
    private val pingIntervalMs: Long = 2000,
    private val linkTimeoutMs: Long = 6000,
    private val reconnectWindowMs: Long = 15000,
    private val commandTimeoutMs: Long = 10000,
) : CameraController {
    private val _state = MutableStateFlow(CameraState())
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    private val _frames = MutableStateFlow<ByteArray?>(null)
    override val liveViewFrames: StateFlow<ByteArray?> = _frames.asStateFlow()

    private val lock = Any()

    /** Bumped by [connect] and [disconnect]: work of an older generation must not touch state. */
    private var generation = 0
    private var session: Session? = null
    private var reconnectJob: Job? = null
    /** Relay lastError last seen by the previous session, so a reconnection mirrors only changes. */
    private var carriedRelayError: String? = null
    private val nextId = AtomicInteger()

    private class Pending(val label: String, val result: CompletableDeferred<Message.Result?>)

    private sealed interface End {
        data object Local : End
        data object Lost : End
        data class Rejected(val reason: String) : End
    }

    private sealed interface Attempt {
        data object Ok : Attempt
        data object Failed : Attempt
        data class Rejected(val reason: String) : Attempt
    }

    private inner class Session(val channel: MessageChannel, val gen: Int) {
        /** Welcome or Rejected; null when the session ended before either. */
        val handshake = CompletableDeferred<Message?>()
        val end = CompletableDeferred<End>()
        val pending = ConcurrentHashMap<Int, Pending>()
        @Volatile var welcomed = false
        /** Last lastError pushed by the relay, to mirror only its changes. */
        var relayError: String? = null
        /** Error last reported by a Result: the relay's state push of the same error is not shown again. */
        var resultError: String? = null
        /** Error just shown from a state push: the Result of the same failure does not show it again. */
        var mirroredError: String? = null

        @Volatile var endReason: End = End.Local

        fun stop(reason: End) {
            synchronized(this) { if (!end.isCompleted) { endReason = reason; end.complete(reason) } }
        }
    }

    /**
     * Connects to the relay (replacing any current link) and returns once welcomed or failed.
     * Failure → DISCONNECTED with the rejection reason or « Relais injoignable » (no retry).
     */
    suspend fun connect() {
        val (gen, old) = synchronized(lock) {
            val old = stopLocked()
            _state.value = CameraState(status = ConnectionStatus.CONNECTING)
            generation to old
        }
        old?.close()
        when (val r = attempt(gen)) {
            Attempt.Ok -> Unit
            is Attempt.Rejected -> fail(gen, r.reason)
            Attempt.Failed -> fail(gen, UNREACHABLE)
        }
    }

    override suspend fun disconnect() {
        val old = synchronized(lock) {
            val old = stopLocked()
            _state.value = CameraState()
            old
        }
        old?.close()
    }

    /**
     * Ends the current session and reconnection without reporting; must hold [lock]. Returns the
     * session's channel, which the caller closes once the lock is released.
     */
    private fun stopLocked(): MessageChannel? {
        generation++
        carriedRelayError = null
        reconnectJob?.cancel()
        reconnectJob = null
        _frames.value = null
        val s = session ?: return null
        session = null
        s.stop(End.Local)
        s.pending.keys.toList().forEach { id -> s.pending.remove(id)?.result?.complete(null) }
        return s.channel
    }

    /** DISCONNECTED with [error]; [keepUnconfirmed] keeps a shown « … non confirmé » warning instead. */
    private fun fail(gen: Int, error: String, keepUnconfirmed: Boolean = false) = synchronized(lock) {
        if (gen != generation) return@synchronized
        session = null
        _state.update {
            val shown = it.lastError?.takeIf { e -> keepUnconfirmed && e.endsWith(UNCONFIRMED_SUFFIX) }
            it.copy(status = ConnectionStatus.DISCONNECTED, busy = false, lastError = shown ?: error)
        }
    }

    private suspend fun attempt(gen: Int): Attempt {
        var produced: MessageChannel? = null
        val channel = try {
            withTimeoutOrNull(linkTimeoutMs) { connect.invoke().also { produced = it } } ?: run {
                produced?.close() // returned after the timeout by a non-cancellable factory
                return Attempt.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn("Relais injoignable : ${e.message ?: e::class.simpleName}")
            return Attempt.Failed
        }
        val s = Session(channel, gen)
        val current = synchronized(lock) {
            if (gen == generation) session = s
            gen == generation
        }
        if (!current) {
            channel.close()
            return Attempt.Failed
        }
        var ok = false
        try {
            scope.launch { runSession(s) }
            channel.send(Message.Hello(MessageCodec.PROTOCOL, token, clientName))
            val reply = withTimeoutOrNull(linkTimeoutMs) { s.handshake.await() }
            return when (reply) {
                is Message.Welcome -> { ok = true; Attempt.Ok }
                is Message.Rejected -> {
                    log.warn("Relais : connexion refusée : ${reply.reason}")
                    Attempt.Rejected(reply.reason)
                }
                else -> {
                    log.warn("Relais : pas de réponse à la connexion")
                    Attempt.Failed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn("Relais : échec de la connexion : ${e.message ?: e::class.simpleName}")
            return Attempt.Failed
        } finally {
            if (!ok) {
                s.stop(End.Local)
                channel.close()
                synchronized(lock) { if (session === s) session = null }
            }
        }
    }

    private suspend fun runSession(s: Session) {
        try {
            coroutineScope {
                fun child(block: suspend () -> Unit) = launch {
                    try {
                        block()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (!s.end.isCompleted) log.warn("Liaison relais interrompue : ${e.message ?: e::class.simpleName}")
                        s.stop(End.Lost)
                    }
                }

                // Link watchdog, restarted by every incoming item (also bounds the handshake).
                val activity = MutableStateFlow(0L)
                launch {
                    activity.collectLatest {
                        delay(linkTimeoutMs)
                        if (!s.end.isCompleted) log.warn("Relais : aucune nouvelle depuis $linkTimeoutMs ms")
                        s.stop(End.Lost)
                    }
                }

                child {
                    s.channel.incoming.collect { item ->
                        if (s.end.isCompleted) return@collect
                        activity.update { it + 1 }
                        when (item) {
                            is Incoming.Jpeg -> if (s.welcomed) publish(s) { _frames.value = item.bytes }
                            is Incoming.Msg -> when (val m = item.message) {
                                is Message.Welcome -> if (!s.welcomed) {
                                    onWelcome(s, m)
                                    child {
                                        while (true) {
                                            delay(pingIntervalMs)
                                            s.channel.send(Message.Ping)
                                        }
                                    }
                                }
                                is Message.Rejected -> {
                                    s.handshake.complete(m)
                                    s.stop(End.Rejected(m.reason))
                                }
                                is Message.StateMsg -> if (s.welcomed) onRelayState(s, m.state)
                                is Message.Result -> s.pending.remove(m.id)?.result?.complete(m)
                                Message.Ping -> child { s.channel.send(Message.Pong) }
                                else -> Unit // Pong (timer already reset) or unexpected: ignored
                            }
                        }
                    }
                    if (!s.end.isCompleted) log.warn("Relais : liaison fermée")
                    s.stop(End.Lost)
                }

                s.end.await()
                coroutineContext.cancelChildren()
            }
        } finally {
            s.channel.close()
            s.handshake.complete(null)
            s.stop(End.Local) // no-op unless cancelled from outside
            onSessionEnd(s, s.endReason)
        }
    }

    /** Runs [block] under [lock] only if [s] is still the current session. */
    private inline fun publish(s: Session, block: () -> Unit) = synchronized(lock) {
        if (session === s && s.gen == generation) block()
    }

    private fun onWelcome(s: Session, w: Message.Welcome) {
        publish(s) {
            s.welcomed = true
            s.relayError = carriedRelayError
            // Fresh state, but an unconfirmed command from before a reconnection stays reported.
            val kept = _state.value.lastError?.takeIf { it != LINK_LOST }
            _state.value = CameraState(ConnectionStatus.CONNECTED, model = w.model, serial = w.serial, lastError = kept)
            log.info("Connecté au relais (${w.model ?: "appareil inconnu"})")
        }
        s.handshake.complete(w)
    }

    private fun onRelayState(s: Session, remote: RemoteState) = publish(s) {
        val relay = remote.toCameraState()
        val previous = s.relayError
        s.relayError = relay.lastError
        val alreadyReported = relay.lastError != null && relay.lastError == s.resultError
        if (relay.lastError != previous) s.resultError = null
        _state.update { cur ->
            val error = when {
                alreadyReported -> cur.lastError
                relay.lastError != previous && relay.lastError != null -> relay.lastError.also { s.mirroredError = it }
                relay.lastError == null && previous != null && cur.lastError == previous -> null
                else -> cur.lastError
            }
            relay.copy(lastError = error)
        }
    }

    private fun onSessionEnd(s: Session, end: End) {
        if (end == End.Local || !s.welcomed) {
            // Unwelcomed sessions are reported by their attempt; still release any pending command.
            failPending(s)
            return
        }
        synchronized(lock) {
            if (session !== s || s.gen != generation) {
                failPending(s)
                return
            }
            session = null
            carriedRelayError = s.relayError
            _frames.value = null
            val unconfirmed = failPending(s)
            when (end) {
                is End.Rejected -> {
                    log.warn("Relais : connexion refusée : ${end.reason}")
                    _state.update { it.copy(status = ConnectionStatus.DISCONNECTED, busy = false, lastError = end.reason) }
                }
                else -> {
                    log.warn("Connexion au relais perdue, reconnexion…")
                    _state.update {
                        it.copy(status = ConnectionStatus.CONNECTING, busy = false, lastError = unconfirmed ?: LINK_LOST)
                    }
                    val gen = generation
                    reconnectJob = scope.launch { reconnect(gen) }
                }
            }
        }
    }

    /** Completes every pending command as unconfirmed; returns the last one's message. */
    private fun failPending(s: Session): String? {
        var last: String? = null
        for (id in s.pending.keys.sorted()) {
            val p = s.pending.remove(id) ?: continue
            last = unconfirmed(p.label)
            p.result.complete(null)
        }
        return last
    }

    private suspend fun reconnect(gen: Int) {
        val outcome = withTimeoutOrNull(reconnectWindowMs) {
            var r = attempt(gen)
            // "Relais occupé": the relay may not have noticed the loss of our previous session yet.
            while (r == Attempt.Failed || (r as? Attempt.Rejected)?.reason == BUSY) {
                delay(RETRY_DELAY_MS)
                r = attempt(gen)
            }
            r
        }
        when (outcome) {
            Attempt.Ok -> log.info("Reconnecté au relais")
            is Attempt.Rejected -> fail(gen, outcome.reason)
            else -> {
                log.warn("Relais : abandon de la reconnexion")
                fail(gen, LINK_LOST, keepUnconfirmed = true)
            }
        }
        val self = currentCoroutineContext()[Job]
        synchronized(lock) { if (reconnectJob === self) reconnectJob = null }
    }

    private suspend fun command(label: String, action: Action): Boolean {
        val s = synchronized(lock) { session?.takeIf { it.welcomed } } ?: return false
        val id = nextId.incrementAndGet()
        val p = Pending(label, CompletableDeferred())
        s.pending[id] = p
        if (s.end.isCompleted) {
            // Raced with the end of the session: nobody will complete it.
            s.pending.remove(id)
            return false
        }
        try {
            try {
                s.channel.send(Message.Command(id, action))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn("Relais : envoi impossible : ${e.message ?: e::class.simpleName}")
                s.stop(End.Lost) // the session end reports this command as unconfirmed
            }
            val result = withTimeoutOrNull(commandTimeoutMs) { p.result.await() }
                ?: if (s.pending.remove(id) != null) {
                    log.warn("Relais : pas de réponse à « $label »")
                    publish(s) { _state.update { it.copy(lastError = unconfirmed(label)) } }
                    return false
                } else {
                    p.result.await()
                }
            if (result == null) return false
            if (!result.ok && result.error != null) {
                publish(s) {
                    if (result.error == s.mirroredError) {
                        s.mirroredError = null // just shown by the relay's state push
                    } else {
                        s.resultError = result.error
                        _state.update { it.copy(lastError = result.error) }
                    }
                }
            }
            return result.ok
        } finally {
            s.pending.remove(id)
        }
    }

    override suspend fun set(setting: CameraSetting, value: Int) =
        command("Réglage ${setting.title}", Action.Set(setting, value))

    override suspend fun shoot() = command("Déclenchement", Action.Shoot)

    override suspend fun autofocus() = command("Mise au point", Action.Autofocus)

    override suspend fun focus(step: FocusStep) = command("Mise au point", Action.Focus(step))

    override suspend fun setLiveView(on: Boolean): Boolean {
        if (on && link == LinkKind.BLUETOOTH) {
            _state.update { it.copy(lastError = BLUETOOTH_LIVE_VIEW) }
            return false
        }
        return command("Live view", Action.LiveView(on))
    }

    override fun clearError() {
        _state.update { it.copy(lastError = null) }
    }

    private fun unconfirmed(label: String) = "$label$UNCONFIRMED_SUFFIX"

    companion object {
        private const val RETRY_DELAY_MS = 1000L
        /** Suffix of the warning for a command whose result was lost with the link. */
        const val UNCONFIRMED_SUFFIX = " non confirmé — vérifie l'appareil"
        const val LINK_LOST = "Connexion au relais perdue"
        private const val UNREACHABLE = "Relais injoignable"
        private const val BUSY = "Relais occupé"
        private const val BLUETOOTH_LIVE_VIEW =
            "Le Bluetooth est trop lent pour le live view. Passe en Wi-Fi pour l'utiliser."
    }
}
