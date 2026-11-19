package dev.canonremote.app.remote

import dev.canonremote.core.remote.Incoming
import dev.canonremote.core.remote.Message
import dev.canonremote.core.remote.MessageChannel
import dev.canonremote.core.remote.MessageCodec
import dev.canonremote.core.remote.StreamFraming
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/** WebSocket path served by the relay. */
const val RELAY_WS_PATH = "/ws"

/** Default TCP port of the relay's WebSocket server. */
const val RELAY_WS_PORT = 8765

private const val CLOSE_TIMEOUT_MS = 2000L

/** Scope for close handshakes: must outlive the (possibly already cancelled) session. */
private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * [MessageChannel] over a Ktor WebSocket session: a text frame carries a [MessageCodec] JSON
 * message, a binary frame a JPEG. [incoming] completes when the session closes (remote close
 * frame, or local [close]) and throws on transport/decoding errors.
 *
 * [close] sends a normal close frame after everything already sent (a [send] that returned is
 * delivered before the close), then calls [onClose].
 */
class WebSocketChannel(
    private val session: DefaultWebSocketSession,
    private val onClose: () -> Unit = {},
) : MessageChannel {
    private val closed = AtomicBoolean(false)
    private val collected = AtomicBoolean(false)
    private val done = CompletableDeferred<Unit>()

    override val incoming: Flow<Incoming> = flow {
        check(collected.compareAndSet(false, true)) { "incoming can only be collected once" }
        try {
            while (true) {
                // receiveCatching throws only if our collector is cancelled; a session closed or
                // cancelled underneath us comes back as a closed result (emit stays outside, so
                // downstream aborts such as first() propagate untouched).
                val result = session.incoming.receiveCatching()
                val frame = result.getOrNull()
                if (frame == null) {
                    val cause = result.exceptionOrNull()
                    if (cause == null || cause is ClosedReceiveChannelException || closed.get()) break
                    throw cause as? IOException ?: IOException("Liaison WebSocket interrompue", cause)
                }
                when (frame) {
                    is Frame.Text -> emit(Incoming.Msg(MessageCodec.decode(frame.readText())))
                    is Frame.Binary -> emit(Incoming.Jpeg(frame.readBytes()))
                    else -> Unit // control frames are handled by the session
                }
            }
        } finally {
            done.complete(Unit)
        }
    }

    override suspend fun send(message: Message) {
        session.send(Frame.Text(MessageCodec.encode(message)))
    }

    override suspend fun sendJpeg(bytes: ByteArray) {
        session.send(Frame.Binary(true, bytes))
    }

    /** Test hook: sends an arbitrary text frame. */
    internal suspend fun sendRawText(text: String) {
        session.send(Frame.Text(text))
    }

    /** Suspends until [incoming] has ended or [close] has finished. */
    suspend fun awaitClosed() = done.await()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeScope.launch {
            try {
                withContext(NonCancellable) {
                    withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
                        // Queued after any completed send, and flushed before returning.
                        session.close(CloseReason(CloseReason.Codes.NORMAL, ""))
                    }
                }
            } catch (_: Throwable) {
                // already closed / broken: nothing to flush
            } finally {
                runCatching { onClose() }
                done.complete(Unit)
            }
        }
    }
}

/**
 * Relay-side WebSocket server: Ktor CIO on 0.0.0.0:[port], route [RELAY_WS_PATH]. Each client
 * session is wrapped in a [WebSocketChannel] and passed to [onClient]; the route handler then stays
 * suspended until the channel ends, so the session remains open while the relay uses it.
 *
 * [port] 0 picks a free port (see [boundPort]).
 */
class RelayWebSocketServer(
    private val port: Int = RELAY_WS_PORT,
    private val onClient: suspend (MessageChannel) -> Unit,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Synchronized
    fun start() {
        if (server != null) return
        server = embeddedServer(factory = ServerCIO, port = port, host = "0.0.0.0") {
            install(ServerWebSockets) {
                maxFrameSize = StreamFraming.MAX_PAYLOAD.toLong()
            }
            routing {
                webSocket(RELAY_WS_PATH) {
                    val channel = WebSocketChannel(this)
                    try {
                        onClient(channel)
                        channel.awaitClosed()
                    } catch (e: CancellationException) {
                        channel.close()
                        throw e
                    }
                }
            }
        }.start(wait = false)
    }

    /** Actual listening port (useful when constructed with port 0). Requires [start]. */
    suspend fun boundPort(): Int {
        val s = synchronized(this) { checkNotNull(server) { "Serveur WebSocket non démarré" } }
        return s.engine.resolvedConnectors().first().port
    }

    @Synchronized
    fun stop() {
        val s = server ?: return
        server = null
        s.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
    }
}

/**
 * Connects to a relay's WebSocket server at `ws://host:port/ws`. Cancellable while connecting.
 *
 * [network]: Ktor CIO cannot open its socket through `network.socketFactory`; when the relay is
 * reached through a specific network (hotspot joined with a `WifiNetworkSpecifier`), the caller
 * must bind the process to it first with `ConnectivityManager.bindProcessToNetwork(network)`
 * (done by [joinHotspot]). The parameter is only documentation of that requirement.
 */
@Suppress("UNUSED_PARAMETER")
suspend fun connectWebSocket(
    host: String,
    port: Int,
    network: android.net.Network? = null,
): MessageChannel {
    val client = HttpClient(ClientCIO) {
        install(ClientWebSockets) {
            maxFrameSize = StreamFraming.MAX_PAYLOAD.toLong()
        }
    }
    try {
        val session = client.webSocketSession(
            method = HttpMethod.Get,
            host = host,
            port = port,
            path = RELAY_WS_PATH,
        )
        return WebSocketChannel(session = session, onClose = { client.close() })
    } catch (e: Throwable) {
        client.close()
        throw e
    }
}
