package dev.canonremote.core.remote

import dev.canonremote.core.CameraSetting
import dev.canonremote.core.CameraState
import dev.canonremote.core.ConnectionStatus
import dev.canonremote.core.FocusStep
import dev.canonremote.core.LogBus
import dev.canonremote.core.LogLevel
import dev.canonremote.core.SettingState
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayServerTest {
    private val camState = CameraState(
        status = ConnectionStatus.CONNECTED,
        model = "Canon EOS 90D",
        serial = "123",
        settings = mapOf(CameraSetting.ISO to SettingState(0x48, listOf(0x48, 0x50))),
    )

    /** Relay-side wrapper: records close() and can make sendJpeg slow. */
    private class ProbeChannel(val d: MemoryChannel, val jpegDelayMs: Long) : MessageChannel by d {
        @Volatile var closed = false
        override suspend fun sendJpeg(bytes: ByteArray) {
            if (jpegDelayMs > 0) delay(jpegDelayMs)
            d.sendJpeg(bytes)
        }
        override fun close() { closed = true; d.close() }
    }

    private class Remote(val relaySide: ProbeChannel, val channel: MemoryChannel) {
        val received: MutableList<Incoming> = CopyOnWriteArrayList()
        @Volatile var completed = false
        val messages get() = received.filterIsInstance<Incoming.Msg>().map { it.message }
        val nonPing get() = messages.filter { it != Message.Ping }
        val jpegs get() = received.filterIsInstance<Incoming.Jpeg>().map { it.bytes.toList() }
        suspend fun hello(token: String? = CODE, protocol: Int = 1, name: String = "Pixel") =
            channel.send(Message.Hello(protocol, token, name))
    }

    private inner class Harness(val test: TestScope) {
        val camera = FakeController(camState)
        val log = LogBus()
        val server = RelayServer(camera, log, test.backgroundScope, CODE)

        fun connect(link: LinkKind = LinkKind.WIFI, autoPong: Boolean = true, jpegDelayMs: Long = 0): Remote {
            val (relayEnd, remoteEnd) = MemoryChannel.pair()
            val probe = ProbeChannel(relayEnd, jpegDelayMs)
            val r = Remote(probe, remoteEnd)
            test.backgroundScope.launch {
                runCatching {
                    remoteEnd.incoming.collect {
                        r.received += it
                        if (autoPong && it is Incoming.Msg && it.message == Message.Ping) remoteEnd.send(Message.Pong)
                    }
                }
                r.completed = true
            }
            server.accept(probe, link)
            return r
        }

        suspend fun connected(link: LinkKind = LinkKind.WIFI, autoPong: Boolean = true, jpegDelayMs: Long = 0): Remote {
            val r = connect(link, autoPong, jpegDelayMs)
            r.hello(if (link == LinkKind.WIFI) CODE else null)
            test.runCurrent()
            return r
        }

        fun assertEnded(r: Remote) {
            assertTrue("channel closed", r.relaySide.closed)
            assertTrue("remote incoming completed", r.completed)
        }
    }

    @Test fun handshakeOkSendsWelcomeThenState() = runTest {
        val h = Harness(this)
        val r = h.connected()
        assertEquals(
            listOf(Message.Welcome("Canon EOS 90D", "123"), Message.StateMsg(camState.toRemote())),
            r.messages,
        )
        assertEquals(RelayClient("Pixel", LinkKind.WIFI), h.server.clients.value)
        assertFalse(r.relaySide.closed)
    }

    @Test fun wrongCodeRejected() = runTest {
        val h = Harness(this)
        val r = h.connect()
        r.hello(token = "000000")
        runCurrent()
        // Wrong codes are answered after a delay, to slow down guessing.
        advanceTimeBy(999)
        runCurrent()
        assertEquals(emptyList<Message>(), r.messages)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf<Message>(Message.Rejected("Code d'appairage incorrect")), r.messages)
        assertNull(h.server.clients.value)
        h.assertEnded(r)
    }

    @Test fun missingCodeOnWifiRejected() = runTest {
        val h = Harness(this)
        val r = h.connect()
        r.hello(token = null)
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(listOf<Message>(Message.Rejected("Code d'appairage incorrect")), r.messages)
        h.assertEnded(r)
    }

    @Test fun wrongVersionRejected() = runTest {
        val h = Harness(this)
        val r = h.connect()
        r.hello(protocol = 2)
        runCurrent()
        assertEquals(listOf<Message>(Message.Rejected("Version incompatible")), r.messages)
        assertNull(h.server.clients.value)
        h.assertEnded(r)
    }

    @Test fun bluetoothAcceptedWithoutCode() = runTest {
        val h = Harness(this)
        val r = h.connected(LinkKind.BLUETOOTH)
        assertEquals(Message.Welcome("Canon EOS 90D", "123"), r.messages.first())
        assertEquals(RelayClient("Pixel", LinkKind.BLUETOOTH), h.server.clients.value)
    }

    @Test fun secondClientRejectedAsBusy() = runTest {
        val h = Harness(this)
        val first = h.connected()
        val second = h.connect()
        second.hello(name = "Autre")
        runCurrent()
        assertEquals(listOf<Message>(Message.Rejected("Relais occupé")), second.messages)
        h.assertEnded(second)
        assertEquals(RelayClient("Pixel", LinkKind.WIFI), h.server.clients.value)
        assertFalse(first.relaySide.closed)
    }

    @Test fun firstMessageMustBeHello() = runTest {
        val h = Harness(this)
        val r = h.connect()
        r.channel.send(Message.Command(1, Action.Shoot))
        runCurrent()
        h.assertEnded(r)
        assertTrue(h.camera.calls.isEmpty())
        assertNull(h.server.clients.value)
    }

    @Test fun noHelloWithinTimeoutEndsSession() = runTest {
        val h = Harness(this)
        val r = h.connect()
        advanceTimeBy(6001)
        h.assertEnded(r)
    }

    @Test fun stateChangesArePushed() = runTest {
        val h = Harness(this)
        val r = h.connected()
        h.camera.state.value = camState.copy(busy = true)
        runCurrent()
        assertEquals(Message.StateMsg(camState.copy(busy = true).toRemote()), r.nonPing.last())
    }

    @Test fun commandsForwardedAndResultReturned() = runTest {
        val h = Harness(this)
        val r = h.connected()
        r.channel.send(Message.Command(7, Action.Set(CameraSetting.ISO, 0x50)))
        r.channel.send(Message.Command(8, Action.Focus(FocusStep.NEAR_1)))
        runCurrent()
        h.camera.result = false
        r.channel.send(Message.Command(9, Action.Shoot))
        runCurrent()
        h.camera.failure = IOException("USB débranché")
        r.channel.send(Message.Command(10, Action.Autofocus))
        runCurrent()
        assertEquals(
            listOf("set(ISO, 80)", "focus(NEAR_1)", "shoot", "autofocus"),
            h.camera.calls,
        )
        val results = r.messages.filterIsInstance<Message.Result>()
        assertEquals(
            listOf(
                Message.Result(7, true),
                Message.Result(8, true),
                Message.Result(9, false),
                Message.Result(10, false, "USB débranché"),
            ),
            results,
        )
    }

    @Test fun commandsReachCameraInOrderOneAtATime() = runTest {
        val h = Harness(this)
        val r = h.connected()
        h.camera.commandDelayMs = 1000
        r.channel.send(Message.Command(1, Action.Set(CameraSetting.ISO, 0x48)))
        r.channel.send(Message.Command(2, Action.Set(CameraSetting.ISO, 0x50)))
        runCurrent()
        advanceTimeBy(500)
        assertEquals(listOf("set(ISO, 72)"), h.camera.calls) // second waits for the first
        advanceTimeBy(1000)
        assertEquals(listOf("set(ISO, 72)", "set(ISO, 80)"), h.camera.calls)
        advanceTimeBy(1000)
        assertEquals(
            listOf(Message.Result(1, true), Message.Result(2, true)),
            r.messages.filterIsInstance<Message.Result>(),
        )
    }

    @Test fun falseResultCarriesCameraError() = runTest {
        val h = Harness(this)
        val r = h.connected()
        h.camera.result = false
        h.camera.errorOnFalse = "Déclenchement refusé : 0x2019"
        r.channel.send(Message.Command(3, Action.Shoot))
        runCurrent()
        assertEquals(
            Message.Result(3, false, "Déclenchement refusé : 0x2019"),
            r.messages.filterIsInstance<Message.Result>().single(),
        )
    }

    @Test fun failedResultUsesProvidedErrorSource() = runTest {
        val camera = FakeController(camState).apply { result = false }
        val server = RelayServer(camera, LogBus(), backgroundScope, CODE, currentError = { "Erreur réelle" })
        val (relayEnd, remoteEnd) = MemoryChannel.pair()
        val received = CopyOnWriteArrayList<Message>()
        backgroundScope.launch {
            runCatching { remoteEnd.incoming.collect { (it as? Incoming.Msg)?.let { m -> received += m.message } } }
        }
        server.accept(relayEnd, LinkKind.WIFI)
        remoteEnd.send(Message.Hello(1, CODE, "Pixel"))
        runCurrent()
        remoteEnd.send(Message.Command(4, Action.Shoot))
        runCurrent()
        assertEquals(Message.Result(4, false, "Erreur réelle"), received.filterIsInstance<Message.Result>().single())
    }

    @Test fun liveViewCommandForwardedOnWifi() = runTest {
        val h = Harness(this)
        val r = h.connected()
        r.channel.send(Message.Command(3, Action.LiveView(true)))
        runCurrent()
        assertEquals(listOf("setLiveView(true)"), h.camera.calls)
        assertEquals(Message.Result(3, true), r.messages.filterIsInstance<Message.Result>().single())
    }

    @Test fun slowCommandDoesNotBlockPingsOrOtherMessages() = runTest {
        val h = Harness(this)
        val r = h.connected()
        h.camera.commandDelayMs = 10_000
        r.channel.send(Message.Command(1, Action.Shoot))
        r.channel.send(Message.Ping)
        runCurrent()
        assertTrue(Message.Pong in r.messages)
        advanceTimeBy(8000)
        assertNull(r.messages.filterIsInstance<Message.Result>().firstOrNull())
        assertTrue(r.messages.count { it == Message.Ping } >= 3)
        assertFalse(r.relaySide.closed) // pongs kept the link alive while the camera worked
        advanceTimeBy(2001)
        assertEquals(Message.Result(1, true), r.messages.filterIsInstance<Message.Result>().single())
    }

    @Test fun bluetoothLiveViewRejectedWithoutTouchingCamera() = runTest {
        val h = Harness(this)
        val r = h.connected(LinkKind.BLUETOOTH)
        r.channel.send(Message.Command(3, Action.LiveView(true)))
        runCurrent()
        assertEquals(
            Message.Result(3, false, "Live view indisponible en Bluetooth"),
            r.messages.filterIsInstance<Message.Result>().single(),
        )
        assertTrue(h.camera.calls.isEmpty())
    }

    @Test fun bluetoothNeverSendsFrames() = runTest {
        val h = Harness(this)
        val r = h.connected(LinkKind.BLUETOOTH)
        h.camera.liveViewFrames.value = byteArrayOf(1)
        runCurrent()
        h.camera.liveViewFrames.value = byteArrayOf(2)
        runCurrent()
        assertTrue(r.relaySide.d.sentJpegs.isEmpty())
    }

    @Test fun framesSentOnWifi() = runTest {
        val h = Harness(this)
        val r = h.connected()
        h.camera.liveViewFrames.value = byteArrayOf(1)
        runCurrent()
        h.camera.liveViewFrames.value = null
        runCurrent()
        h.camera.liveViewFrames.value = byteArrayOf(2)
        runCurrent()
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2)), r.jpegs)
    }

    @Test fun framesConflatedForSlowReceiver() = runTest {
        val h = Harness(this)
        val r = h.connected(jpegDelayMs = 100)
        for (i in 1..10) {
            h.camera.liveViewFrames.value = byteArrayOf(i.toByte())
            runCurrent()
            advanceTimeBy(10)
        }
        advanceTimeBy(1000)
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(10)), r.jpegs)
    }

    @Test fun relayAnswersPingAndPingsPeriodically() = runTest {
        val h = Harness(this)
        val r = h.connected()
        r.channel.send(Message.Ping)
        runCurrent()
        assertEquals(1, r.messages.count { it == Message.Pong })
        assertEquals(0, r.messages.count { it == Message.Ping })
        advanceTimeBy(2001)
        assertEquals(1, r.messages.count { it == Message.Ping })
        advanceTimeBy(2000)
        assertEquals(2, r.messages.count { it == Message.Ping })
    }

    @Test fun pongsKeepLinkAlive() = runTest {
        val h = Harness(this)
        val r = h.connected(autoPong = true)
        advanceTimeBy(30_000)
        assertFalse(r.relaySide.closed)
        assertEquals(RelayClient("Pixel", LinkKind.WIFI), h.server.clients.value)
    }

    @Test fun silentCutEndsSessionAfterTimeoutWithoutTouchingCamera() = runTest {
        val h = Harness(this)
        h.camera.state.value = camState.copy(liveView = true)
        val r = h.connected()
        r.channel.cut()
        advanceTimeBy(5999)
        assertFalse(r.relaySide.closed)
        assertEquals(RelayClient("Pixel", LinkKind.WIFI), h.server.clients.value)
        advanceTimeBy(2)
        assertTrue(r.relaySide.closed)
        assertNull(h.server.clients.value)
        assertTrue(h.camera.calls.isEmpty())
        assertTrue(h.log.entries.value.any { it.level == LogLevel.INFO && it.message == "Télécommande déconnectée" })
    }

    @Test fun channelErrorEndsSession() = runTest {
        val h = Harness(this)
        val r = h.connected()
        r.channel.fail(IOException("reset"))
        runCurrent()
        assertTrue(r.relaySide.closed)
        assertNull(h.server.clients.value)
        assertTrue(h.camera.calls.isEmpty())
        assertTrue(h.log.entries.value.any { it.message == "Télécommande déconnectée" })
    }

    @Test fun remoteCloseEndsSession() = runTest {
        val h = Harness(this)
        val r = h.connected()
        r.channel.close()
        runCurrent()
        assertTrue(r.relaySide.closed)
        assertNull(h.server.clients.value)
        assertTrue(h.camera.calls.isEmpty())
    }

    @Test fun commandInFlightAtCutStillCompletesOnCamera() = runTest {
        val h = Harness(this)
        val r = h.connected()
        h.camera.commandDelayMs = 1000
        r.channel.send(Message.Command(1, Action.Shoot))
        runCurrent()
        r.channel.fail(IOException("reset"))
        runCurrent()
        assertTrue(r.relaySide.closed)
        advanceTimeBy(2000)
        assertEquals(listOf("shoot"), h.camera.calls)
    }

    @Test fun reconnectGetsFullCurrentState() = runTest {
        val h = Harness(this)
        val first = h.connected()
        first.channel.cut()
        advanceTimeBy(6001)
        assertNull(h.server.clients.value)
        val newState = camState.copy(liveView = true, lastError = "Carte pleine")
        h.camera.state.value = newState
        runCurrent()
        val second = h.connected()
        assertEquals(
            listOf(Message.Welcome("Canon EOS 90D", "123"), Message.StateMsg(newState.toRemote())),
            second.messages,
        )
        assertEquals(RelayClient("Pixel", LinkKind.WIFI), h.server.clients.value)
    }

    @Test fun stoppingTheRelayLetsTheRunningCommandFinish() = runTest {
        // A shoot cut in the middle could leave the shutter button half pressed on the camera.
        val camera = FakeController(camState)
        val relayScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val server = RelayServer(camera, LogBus(), relayScope, CODE)
        val (relayEnd, remoteEnd) = MemoryChannel.pair()
        backgroundScope.launch { runCatching { remoteEnd.incoming.collect { } } }
        server.accept(relayEnd, LinkKind.WIFI)
        remoteEnd.send(Message.Hello(1, CODE, "Pixel"))
        runCurrent()
        camera.commandDelayMs = 3000
        remoteEnd.send(Message.Command(1, Action.Shoot))
        runCurrent()
        assertEquals(listOf("shoot"), camera.calls)
        relayScope.cancel()
        advanceTimeBy(3001)
        assertEquals(listOf("shoot"), camera.finished)
    }

    private companion object { const val CODE = "123456" }
}
