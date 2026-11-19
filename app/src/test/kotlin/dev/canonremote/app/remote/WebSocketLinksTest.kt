package dev.canonremote.app.remote

import dev.canonremote.core.remote.Incoming
import dev.canonremote.core.remote.Message
import dev.canonremote.core.remote.MessageChannel
import dev.canonremote.core.remote.ProtocolException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end over a real localhost socket: Ktor CIO server + Ktor CIO client. */
class WebSocketLinksTest {
    private val jpeg = ByteArray(200_000) { (it * 31).toByte() }

    @Test
    fun exchangesMessagesAndJpegThenClosesCleanly() = runBlocking {
        val serverReceived = CompletableDeferred<List<Incoming>>()
        val server = RelayWebSocketServer(port = 0) { ch ->
            // Reply to whatever arrives, then send a final Rejected and close (it must be delivered).
            val got = mutableListOf<Incoming>()
            ch.incoming.collect { item ->
                got += item
                if (item is Incoming.Msg && item.message is Message.Hello) {
                    ch.send(Message.Welcome(model = "EOS 90D", serial = "123"))
                    ch.sendJpeg(jpeg)
                }
                if (item is Incoming.Jpeg) {
                    ch.send(Message.Rejected("Relais occupé"))
                    ch.close()
                }
            }
            serverReceived.complete(got)
        }
        server.start()
        try {
            withTimeout(10_000) {
                val client = connectWebSocket(host = "127.0.0.1", port = server.boundPort())
                client.send(Message.Hello(protocol = 1, token = "123456", clientName = "test"))
                val items = mutableListOf<Incoming>()
                client.incoming.collect { item ->
                    items += item
                    if (item is Incoming.Jpeg) client.sendJpeg(jpeg)
                }
                client.close()

                assertEquals(3, items.size)
                assertEquals(Message.Welcome("EOS 90D", "123"), (items[0] as Incoming.Msg).message)
                assertArrayEquals(jpeg, (items[1] as Incoming.Jpeg).bytes)
                assertEquals(Message.Rejected("Relais occupé"), (items[2] as Incoming.Msg).message)

                val got = serverReceived.await()
                assertEquals(2, got.size)
                assertEquals(Message.Hello(1, "123456", "test"), (got[0] as Incoming.Msg).message)
                assertArrayEquals(jpeg, (got[1] as Incoming.Jpeg).bytes)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun clientCloseCompletesServerIncomingAndHandler() = runBlocking {
        val handlerDone = CompletableDeferred<List<Incoming>>()
        val server = RelayWebSocketServer(port = 0) { ch: MessageChannel ->
            handlerDone.complete(ch.incoming.toList())
        }
        server.start()
        try {
            withTimeout(10_000) {
                val client = connectWebSocket(host = "127.0.0.1", port = server.boundPort())
                client.send(Message.Ping)
                client.close()
                val got = handlerDone.await()
                assertEquals(listOf<Message>(Message.Ping), got.map { (it as Incoming.Msg).message })
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun rejectedSentAfterPartialCollectIsDeliveredBeforeClose() = runBlocking {
        val server = RelayWebSocketServer(port = 0) { ch ->
            ch.incoming.first() // aborts the flow downstream: must not break the channel
            ch.send(Message.Rejected("Code incorrect"))
            ch.close()
        }
        server.start()
        try {
            withTimeout(10_000) {
                val client = connectWebSocket(host = "127.0.0.1", port = server.boundPort())
                client.send(Message.Hello(protocol = 1, token = "000000", clientName = "test"))
                val items = client.incoming.toList()
                assertEquals(listOf<Message>(Message.Rejected("Code incorrect")), items.map { (it as Incoming.Msg).message })
                client.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun connectIsCancellable() = runBlocking {
        // Accepts TCP but never answers the HTTP upgrade.
        ServerSocket(0).use { silent ->
            thread(isDaemon = true) { runCatching { silent.accept() } }
            val result = withTimeoutOrNull(500) { connectWebSocket(host = "127.0.0.1", port = silent.localPort) }
            assertNull(result)
        }
    }

    @Test
    fun handlerReturningImmediatelyKeepsSessionOpenForRelayLikeUse() = runBlocking {
        // Production shape: onClient (RelayServer.accept) returns at once; another coroutine reads
        // Hello, then sends Rejected and closes.
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = RelayWebSocketServer(port = 0) { ch ->
            worker.launch {
                val first = ch.incoming.first()
                assertTrue(first is Incoming.Msg && first.message is Message.Hello)
                ch.send(Message.Rejected("Relais occupé"))
                ch.close()
            }
        }
        server.start()
        try {
            withTimeout(10_000) {
                val client = connectWebSocket(host = "127.0.0.1", port = server.boundPort())
                client.send(Message.Hello(protocol = 1, token = "123456", clientName = "test"))
                val items = client.incoming.toList()
                assertEquals(listOf<Message>(Message.Rejected("Relais occupé")), items.map { (it as Incoming.Msg).message })
                client.close()
            }
        } finally {
            server.stop()
            worker.cancel()
        }
    }

    @Test
    fun invalidTextFrameFailsIncoming() = runBlocking {
        val failure = CompletableDeferred<Throwable?>()
        val server = RelayWebSocketServer(port = 0) { ch ->
            failure.complete(runCatching { ch.incoming.collect {} }.exceptionOrNull())
            ch.close()
        }
        server.start()
        val raw = HttpClient(CIO) { install(WebSockets) }
        try {
            withTimeout(10_000) {
                val session = raw.webSocketSession(host = "127.0.0.1", port = server.boundPort(), path = RELAY_WS_PATH)
                session.send(Frame.Text("not json"))
                val e = failure.await()
                assertTrue("expected ProtocolException, got $e", e is ProtocolException)
                session.cancel()
            }
        } finally {
            raw.close()
            server.stop()
        }
    }
}
