package dev.canonremote.core.remote

import dev.canonremote.core.CameraSetting
import dev.canonremote.core.CameraState
import dev.canonremote.core.ConnectionStatus
import dev.canonremote.core.FocusStep
import dev.canonremote.core.LogBus
import dev.canonremote.core.SettingState
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteCameraTest {
    private val camState = CameraState(
        status = ConnectionStatus.CONNECTED,
        model = "Canon EOS 90D",
        serial = "123",
        settings = mapOf(CameraSetting.ISO to SettingState(0x48, listOf(0x48, 0x50))),
    )

    /** RemoteCamera wired to a real RelayServer (FakeController camera) through MemoryChannels. */
    private inner class EndToEnd(
        test: TestScope,
        link: LinkKind = LinkKind.WIFI,
        token: String? = if (link == LinkKind.WIFI) CODE else null,
        clientName: String = "Pixel",
        shared: EndToEnd? = null,
        remoteLinkTimeoutMs: Long = 6000,
    ) {
        val camera: FakeController = shared?.camera ?: FakeController(camState)
        val log = LogBus()
        val relay: RelayServer = shared?.relay ?: RelayServer(camera, log, test.backgroundScope, CODE)
        val channels: MutableList<MemoryChannel> = CopyOnWriteArrayList()
        val attempts = AtomicInteger()
        @Volatile var reachable = true
        private val ends: MutableList<SeverableEnd> = CopyOnWriteArrayList()

        /** Cuts the current link: nothing gets through, not even the remote's close(). */
        fun cutSilently() { ends.last().severed = true; channels.last().cut() }

        val remote = RemoteCamera(
            connect = {
                attempts.incrementAndGet()
                if (!reachable) throw IOException("hôte injoignable")
                val (remoteEnd, relayEnd) = MemoryChannel.pair()
                channels += remoteEnd
                relay.accept(relayEnd, link)
                SeverableEnd(remoteEnd).also { ends += it }
            },
            link = link,
            token = token,
            clientName = clientName,
            log = log,
            scope = test.backgroundScope,
            linkTimeoutMs = remoteLinkTimeoutMs,
        )
    }

    /** Remote end whose close() no longer reaches the relay once [severed]. */
    private class SeverableEnd(val d: MemoryChannel) : MessageChannel by d {
        @Volatile var severed = false
        override fun close() { if (!severed) d.close() }
    }

    private class CloseProbe(val d: MessageChannel) : MessageChannel by d {
        @Volatile var closed = false
        override fun close() { closed = true; d.close() }
    }

    /** RemoteCamera against a hand-scripted relay. */
    private inner class Scripted(test: TestScope, link: LinkKind = LinkKind.WIFI) {
        inner class Session(val channel: MemoryChannel) {
            val received: MutableList<Message> = CopyOnWriteArrayList()
            val nonPing get() = received.filter { it != Message.Ping && it != Message.Pong }
        }

        val sessions: MutableList<Session> = CopyOnWriteArrayList()
        val remoteEnds: MutableList<CloseProbe> = CopyOnWriteArrayList()
        val log = LogBus()
        val attempts = AtomicInteger()

        /** Reply to Hello; default: Welcome + state. */
        var onHello: suspend (Session, Message.Hello) -> Unit = { s, _ ->
            s.channel.send(Message.Welcome("Canon EOS 90D", "123"))
            s.channel.send(Message.StateMsg(camState.toRemote()))
        }
        var onCommand: suspend (Session, Message.Command) -> Unit = { _, _ -> }
        var autoPong = true

        val remote = RemoteCamera(
            connect = {
                attempts.incrementAndGet()
                val (remoteEnd, relayEnd) = MemoryChannel.pair()
                val s = Session(relayEnd)
                sessions += s
                test.backgroundScope.launch {
                    runCatching {
                        relayEnd.incoming.collect { item ->
                            val m = (item as? Incoming.Msg)?.message ?: return@collect
                            s.received += m
                            when (m) {
                                is Message.Hello -> onHello(s, m)
                                is Message.Command -> onCommand(s, m)
                                Message.Ping -> if (autoPong) relayEnd.send(Message.Pong)
                                else -> Unit
                            }
                        }
                    }
                }
                CloseProbe(remoteEnd).also { remoteEnds += it }
            },
            link = link,
            token = CODE,
            clientName = "Pixel",
            log = log,
            scope = test.backgroundScope,
        )
    }

    // ---- end-to-end ----

    @Test fun connectsAndMirrorsRelayState() = runTest {
        val h = EndToEnd(this)
        h.remote.connect()
        runCurrent()
        assertEquals(camState, h.remote.state.value)
        assertEquals(RelayClient("Pixel", LinkKind.WIFI), h.relay.clients.value)
        assertEquals(LinkKind.WIFI, h.remote.link)

        h.camera.state.value = camState.copy(
            settings = mapOf(CameraSetting.ISO to SettingState(0x50, listOf(0x48, 0x50))),
            busy = true,
        )
        runCurrent()
        assertEquals(SettingState(0x50, listOf(0x48, 0x50)), h.remote.state.value.settings[CameraSetting.ISO])
        assertTrue(h.remote.state.value.busy)
    }

    @Test fun setAndShootEndToEnd() = runTest {
        val h = EndToEnd(this)
        h.remote.connect()
        runCurrent()
        assertTrue(h.remote.set(CameraSetting.ISO, 0x50))
        assertTrue(h.remote.shoot())
        assertTrue(h.remote.autofocus())
        assertTrue(h.remote.focus(FocusStep.FAR_2))
        assertTrue(h.remote.setLiveView(true))
        assertEquals(
            listOf("set(ISO, 80)", "shoot", "autofocus", "focus(FAR_2)", "setLiveView(true)"),
            h.camera.calls,
        )
    }

    @Test fun commandErrorBecomesLastError() = runTest {
        val h = EndToEnd(this)
        h.remote.connect()
        runCurrent()
        h.camera.failure = IOException("USB débranché")
        assertFalse(h.remote.shoot())
        assertEquals("USB débranché", h.remote.state.value.lastError)
        h.remote.clearError()
        assertNull(h.remote.state.value.lastError)
    }

    @Test fun liveViewFramesMirrored() = runTest {
        val h = EndToEnd(this)
        h.remote.connect()
        runCurrent()
        h.camera.liveViewFrames.value = byteArrayOf(1, 2, 3)
        runCurrent()
        assertArrayEquals(byteArrayOf(1, 2, 3), h.remote.liveViewFrames.value)
    }

    @Test fun relayCameraDisconnectedShownAsDisconnected() = runTest {
        val h = EndToEnd(this)
        h.remote.connect()
        runCurrent()
        h.camera.state.value = CameraState(lastError = "Appareil déconnecté")
        runCurrent()
        assertEquals(ConnectionStatus.DISCONNECTED, h.remote.state.value.status)
        assertEquals("Appareil déconnecté", h.remote.state.value.lastError)
        // the link itself is still up: the camera coming back is mirrored
        h.camera.state.value = camState
        runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, h.remote.state.value.status)
    }

    @Test fun notConnectedCommandsReturnFalseWithoutSending() = runTest {
        val h = EndToEnd(this)
        assertFalse(h.remote.shoot())
        assertFalse(h.remote.set(CameraSetting.ISO, 0x50))
        assertEquals(0, h.attempts.get())
        assertTrue(h.camera.calls.isEmpty())
        assertEquals(ConnectionStatus.DISCONNECTED, h.remote.state.value.status)
    }

    @Test fun lostLinkDuringShootReportsUnconfirmed() = runTest {
        val h = EndToEnd(this)
        h.remote.connect()
        runCurrent()
        h.camera.commandDelayMs = 3000
        val shot = async { h.remote.shoot() }
        runCurrent()
        h.reachable = false
        h.channels.last().fail(IOException("Wi-Fi coupé"))
        runCurrent()
        assertFalse(shot.await())
        assertEquals("Déclenchement non confirmé — vérifie l'appareil", h.remote.state.value.lastError)
        assertEquals(ConnectionStatus.CONNECTING, h.remote.state.value.status)
        assertNull(h.remote.liveViewFrames.value)
        advanceTimeBy(3001)
        assertEquals("the relay still executes the shot", listOf("shoot"), h.camera.calls)
    }

    @Test fun silentCutDetectedByTimeout() = runTest {
        val h = EndToEnd(this)
        h.remote.connect()
        runCurrent()
        h.reachable = false
        h.cutSilently()
        advanceTimeBy(5000)
        assertEquals(ConnectionStatus.CONNECTED, h.remote.state.value.status)
        advanceTimeBy(2000)
        assertEquals(ConnectionStatus.CONNECTING, h.remote.state.value.status)
        assertEquals("Connexion au relais perdue", h.remote.state.value.lastError)
    }

    @Test fun reconnectWithinWindowRestoresConnectedWithFreshState() = runTest {
        val h = EndToEnd(this)
        h.remote.connect()
        runCurrent()
        h.reachable = false
        h.cutSilently()
        advanceTimeBy(7000)
        assertEquals(ConnectionStatus.CONNECTING, h.remote.state.value.status)
        h.camera.state.value = camState.copy(settings = mapOf(CameraSetting.ISO to SettingState(0x50, listOf(0x50))))
        h.reachable = true
        advanceTimeBy(1500)
        val s = h.remote.state.value
        assertEquals(ConnectionStatus.CONNECTED, s.status)
        assertEquals(SettingState(0x50, listOf(0x50)), s.settings[CameraSetting.ISO])
        assertNull(s.lastError)
        assertTrue(h.remote.shoot())
    }

    @Test fun reconnectRetriesWhileRelayStillBusyWithOldSession() = runTest {
        // The remote notices the silent cut (3 s) before the relay does (6 s): the relay answers
        // "Relais occupé" until its own watchdog has ended the old session.
        val h = EndToEnd(this, remoteLinkTimeoutMs = 3000)
        h.remote.connect()
        runCurrent()
        h.cutSilently()
        advanceTimeBy(5000)
        assertEquals(ConnectionStatus.CONNECTING, h.remote.state.value.status)
        assertTrue(h.attempts.get() >= 3)
        advanceTimeBy(3000)
        assertEquals(ConnectionStatus.CONNECTED, h.remote.state.value.status)
        assertEquals(RelayClient("Pixel", LinkKind.WIFI), h.relay.clients.value)
    }

    @Test fun givesUpAfterWindow() = runTest {
        val h = EndToEnd(this)
        h.remote.connect()
        runCurrent()
        h.reachable = false
        h.channels.last().fail(IOException("Wi-Fi coupé"))
        runCurrent()
        val before = h.attempts.get()
        advanceTimeBy(14_500)
        assertEquals(ConnectionStatus.CONNECTING, h.remote.state.value.status)
        assertEquals("Connexion au relais perdue", h.remote.state.value.lastError)
        assertTrue("retries about every second", h.attempts.get() - before in 14..16)
        advanceTimeBy(1000)
        assertEquals(ConnectionStatus.DISCONNECTED, h.remote.state.value.status)
        assertEquals("Connexion au relais perdue", h.remote.state.value.lastError)
        val after = h.attempts.get()
        advanceTimeBy(10_000)
        assertEquals("no more attempts", after, h.attempts.get())
        // the user can reconnect manually
        h.reachable = true
        h.remote.connect()
        runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, h.remote.state.value.status)
    }

    @Test fun wrongCodeRejectedWithoutRetry() = runTest {
        val h = EndToEnd(this, token = "000000")
        h.remote.connect()
        runCurrent()
        assertEquals(ConnectionStatus.DISCONNECTED, h.remote.state.value.status)
        assertEquals("Code d'appairage incorrect", h.remote.state.value.lastError)
        advanceTimeBy(20_000)
        assertEquals(1, h.attempts.get())
    }

    @Test fun busyRelayRejected() = runTest {
        val first = EndToEnd(this)
        first.remote.connect()
        runCurrent()
        val second = EndToEnd(this, clientName = "Autre", shared = first)
        second.remote.connect()
        runCurrent()
        assertEquals(ConnectionStatus.DISCONNECTED, second.remote.state.value.status)
        assertEquals("Relais occupé", second.remote.state.value.lastError)
        assertEquals(ConnectionStatus.CONNECTED, first.remote.state.value.status)
    }

    @Test fun bluetoothLiveViewRefusedLocally() = runTest {
        val h = EndToEnd(this, link = LinkKind.BLUETOOTH)
        h.remote.connect()
        runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, h.remote.state.value.status)
        assertFalse(h.remote.setLiveView(true))
        assertEquals(
            "Le Bluetooth est trop lent pour le live view. Passe en Wi-Fi pour l'utiliser.",
            h.remote.state.value.lastError,
        )
        assertTrue(h.camera.calls.isEmpty())
        assertTrue(h.remote.setLiveView(false))
        assertEquals(listOf("setLiveView(false)"), h.camera.calls)
    }

    @Test fun unreachableRelayOnFirstConnect() = runTest {
        val h = EndToEnd(this)
        h.reachable = false
        h.remote.connect()
        assertEquals(ConnectionStatus.DISCONNECTED, h.remote.state.value.status)
        assertEquals("Relais injoignable", h.remote.state.value.lastError)
        advanceTimeBy(20_000)
        assertEquals(1, h.attempts.get())
    }

    @Test fun disconnectClosesAndStopsReconnecting() = runTest {
        val h = EndToEnd(this)
        h.remote.connect()
        runCurrent()
        h.remote.disconnect()
        runCurrent()
        assertEquals(ConnectionStatus.DISCONNECTED, h.remote.state.value.status)
        assertNull(h.relay.clients.value)
        advanceTimeBy(30_000)
        assertEquals(1, h.attempts.get())
        assertEquals(ConnectionStatus.DISCONNECTED, h.remote.state.value.status)
        assertFalse(h.remote.shoot())
    }

    @Test fun disconnectDuringReconnectStopsIt() = runTest {
        val h = EndToEnd(this)
        h.remote.connect()
        runCurrent()
        h.reachable = false
        h.channels.last().fail(IOException("Wi-Fi coupé"))
        advanceTimeBy(2500)
        h.remote.disconnect()
        val n = h.attempts.get()
        advanceTimeBy(20_000)
        assertEquals(n, h.attempts.get())
        assertEquals(ConnectionStatus.DISCONNECTED, h.remote.state.value.status)
    }

    // ---- scripted relay ----

    @Test fun noWelcomeOnFirstConnectIsUnreachable() = runTest {
        val h = Scripted(this)
        h.onHello = { _, _ -> }
        val job = async { h.remote.connect() }
        advanceTimeBy(5000)
        assertEquals(ConnectionStatus.CONNECTING, h.remote.state.value.status)
        advanceTimeBy(1500)
        job.await()
        assertEquals(ConnectionStatus.DISCONNECTED, h.remote.state.value.status)
        assertEquals("Relais injoignable", h.remote.state.value.lastError)
        advanceTimeBy(20_000)
        assertEquals(1, h.attempts.get())
    }

    @Test fun helloCarriesProtocolTokenAndName() = runTest {
        val h = Scripted(this)
        h.remote.connect()
        runCurrent()
        assertEquals(Message.Hello(MessageCodec.PROTOCOL, CODE, "Pixel"), h.sessions.single().received.first())
    }

    @Test fun versionRejectionSurfaced() = runTest {
        val h = Scripted(this)
        h.onHello = { s, _ -> s.channel.send(Message.Rejected("Version incompatible")) }
        h.remote.connect()
        runCurrent()
        assertEquals(ConnectionStatus.DISCONNECTED, h.remote.state.value.status)
        assertEquals("Version incompatible", h.remote.state.value.lastError)
    }

    @Test fun answersPingAndSendsOwnPings() = runTest {
        val h = Scripted(this)
        h.remote.connect()
        runCurrent()
        val s = h.sessions.single()
        s.channel.send(Message.Ping)
        runCurrent()
        assertTrue(Message.Pong in s.received)
        advanceTimeBy(4100)
        assertEquals(2, s.received.count { it == Message.Ping })
    }

    @Test fun incomingTrafficKeepsLinkAlive() = runTest {
        val h = Scripted(this)
        h.remote.connect()
        runCurrent()
        val s = h.sessions.single()
        // relay never pongs but sends something every 5 s: link stays up
        h.autoPong = false
        repeat(4) {
            advanceTimeBy(5000)
            s.channel.send(Message.StateMsg(camState.toRemote()))
            runCurrent()
        }
        assertEquals(ConnectionStatus.CONNECTED, h.remote.state.value.status)
        h.onHello = { _, _ -> } // so that the reconnection does not succeed at once
        advanceTimeBy(6100)
        assertEquals(ConnectionStatus.CONNECTING, h.remote.state.value.status)
    }

    @Test fun unansweredCommandTimesOut() = runTest {
        val h = Scripted(this)
        h.remote.connect()
        runCurrent()
        val r = async { h.remote.set(CameraSetting.ISO, 0x50) }
        advanceTimeBy(9_000)
        assertFalse(r.isCompleted)
        advanceTimeBy(1_100)
        assertFalse(r.await())
        assertEquals("Réglage ISO non confirmé — vérifie l'appareil", h.remote.state.value.lastError)
        assertEquals(ConnectionStatus.CONNECTED, h.remote.state.value.status)
    }

    @Test fun pendingCommandLabels() = runTest {
        val h = Scripted(this)
        h.remote.connect()
        runCurrent()
        val focus = async { h.remote.focus(FocusStep.NEAR_1) }
        runCurrent()
        h.sessions.single().channel.close()
        runCurrent()
        assertFalse(focus.await())
        assertEquals("Mise au point non confirmé — vérifie l'appareil", h.remote.state.value.lastError)
        // the scripted relay welcomes again at once: reconnected, warning kept
        assertEquals(ConnectionStatus.CONNECTED, h.remote.state.value.status)
    }

    @Test fun lastPendingCommandWinsForLastError() = runTest {
        val h = Scripted(this)
        h.remote.connect()
        runCurrent()
        val set = async { h.remote.set(CameraSetting.SHUTTER, 1) }
        runCurrent()
        val lv = async { h.remote.setLiveView(true) }
        runCurrent()
        h.sessions.single().channel.close()
        runCurrent()
        assertFalse(set.await())
        assertFalse(lv.await())
        assertEquals("Live view non confirmé — vérifie l'appareil", h.remote.state.value.lastError)
    }

    @Test fun falseResultWithoutErrorKeepsRelayError() = runTest {
        val h = Scripted(this)
        h.onCommand = { s, c ->
            s.channel.send(Message.StateMsg(camState.copy(lastError = "Déclenchement refusé : 0x2019").toRemote()))
            s.channel.send(Message.Result(c.id, false))
        }
        h.remote.connect()
        runCurrent()
        assertFalse(h.remote.shoot())
        assertEquals("Déclenchement refusé : 0x2019", h.remote.state.value.lastError)
        // cleared locally, and not revived by later state pushes carrying the same relay error
        h.remote.clearError()
        h.sessions.single().channel.send(
            Message.StateMsg(camState.copy(busy = true, lastError = "Déclenchement refusé : 0x2019").toRemote()),
        )
        runCurrent()
        assertTrue(h.remote.state.value.busy)
        assertNull(h.remote.state.value.lastError)
    }

    @Test fun resultErrorNotShownAgainByMirroredState() = runTest {
        val h = Scripted(this)
        h.onCommand = { s, c -> s.channel.send(Message.Result(c.id, false, "Déclenchement refusé : 0x2019")) }
        h.remote.connect()
        runCurrent()
        assertFalse(h.remote.shoot())
        assertEquals("Déclenchement refusé : 0x2019", h.remote.state.value.lastError)
        h.remote.clearError()
        // the relay's state push carrying the same error arrives after the Result
        h.sessions.single().channel.send(
            Message.StateMsg(camState.copy(busy = true, lastError = "Déclenchement refusé : 0x2019").toRemote()),
        )
        runCurrent()
        assertTrue(h.remote.state.value.busy)
        assertNull(h.remote.state.value.lastError)
    }

    @Test fun repeatedIdenticalFailureShownAgainThroughResult() = runTest {
        val h = Scripted(this)
        val error = "Déclenchement refusé : 0x2019"
        h.onCommand = { s, c ->
            // The relay's camera keeps the same lastError: only the first failure changes its state.
            if (c.id == 1) s.channel.send(Message.StateMsg(camState.copy(lastError = error).toRemote()))
            s.channel.send(Message.Result(c.id, false, error))
        }
        h.remote.connect()
        runCurrent()
        assertFalse(h.remote.shoot())
        assertEquals(error, h.remote.state.value.lastError)
        h.remote.clearError()
        runCurrent()
        assertNull(h.remote.state.value.lastError) // the Result did not show it a second time
        assertFalse(h.remote.shoot())
        assertEquals(error, h.remote.state.value.lastError)
    }

    @Test fun relayCameraErrorReachesRemoteThroughResult() = runTest {
        val h = EndToEnd(this)
        h.remote.connect()
        runCurrent()
        h.camera.result = false
        h.camera.errorOnFalse = "Déclenchement refusé : 0x2019"
        assertFalse(h.remote.shoot())
        assertEquals("Déclenchement refusé : 0x2019", h.remote.state.value.lastError)
    }

    @Test fun rejectedDuringReconnectStops() = runTest {
        val h = Scripted(this)
        h.remote.connect()
        runCurrent()
        h.onHello = { s, _ -> s.channel.send(Message.Rejected("Code d'appairage incorrect")) }
        h.sessions.single().channel.close()
        runCurrent()
        assertEquals(ConnectionStatus.DISCONNECTED, h.remote.state.value.status)
        assertEquals("Code d'appairage incorrect", h.remote.state.value.lastError)
        val n = h.attempts.get()
        advanceTimeBy(20_000)
        assertEquals(n, h.attempts.get())
    }

    @Test fun ownerClosesChannelOnLossAndDisconnect() = runTest {
        val h = Scripted(this)
        h.remote.connect()
        runCurrent()
        h.sessions[0].channel.fail(IOException("x"))
        runCurrent()
        assertTrue(h.remoteEnds[0].closed)
        runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, h.remote.state.value.status)
        h.remote.disconnect()
        assertTrue(h.remoteEnds[1].closed)
    }

    // ---- review fixes ----

    @Test fun giveUpKeepsUnconfirmedWarning() = runTest {
        val h = EndToEnd(this)
        h.remote.connect()
        runCurrent()
        h.camera.commandDelayMs = 3000
        val shot = async { h.remote.shoot() }
        runCurrent()
        h.reachable = false
        h.channels.last().fail(IOException("Wi-Fi coupé"))
        runCurrent()
        assertFalse(shot.await())
        advanceTimeBy(16_000)
        assertEquals(ConnectionStatus.DISCONNECTED, h.remote.state.value.status)
        assertEquals("Déclenchement non confirmé — vérifie l'appareil", h.remote.state.value.lastError)
    }

    @Test fun clearedRelayErrorNotResurrectedOnReconnect() = runTest {
        val h = Scripted(this)
        val withError = camState.copy(lastError = "Déclenchement refusé : 0x2019")
        h.onHello = { s, _ ->
            s.channel.send(Message.Welcome("Canon EOS 90D", "123"))
            s.channel.send(Message.StateMsg(withError.toRemote()))
        }
        h.remote.connect()
        runCurrent()
        assertEquals("Déclenchement refusé : 0x2019", h.remote.state.value.lastError)
        h.remote.clearError()
        h.sessions.single().channel.close()
        runCurrent()
        assertEquals(2, h.sessions.size)
        assertEquals(ConnectionStatus.CONNECTED, h.remote.state.value.status)
        assertNull(h.remote.state.value.lastError)
        // a different relay error is still mirrored
        h.sessions[1].channel.send(Message.StateMsg(camState.copy(lastError = "Autre").toRemote()))
        runCurrent()
        assertEquals("Autre", h.remote.state.value.lastError)
    }

    @Test fun lateChannelFromNonCancellableFactoryIsClosed() = runTest {
        val late = CloseProbe(MemoryChannel.pair().first)
        val remote = RemoteCamera(
            connect = { withContext(NonCancellable) { delay(7000) }; late },
            link = LinkKind.WIFI,
            token = CODE,
            clientName = "Pixel",
            log = LogBus(),
            scope = backgroundScope,
        )
        remote.connect()
        assertTrue(late.closed)
        assertEquals(ConnectionStatus.DISCONNECTED, remote.state.value.status)
        assertEquals("Relais injoignable", remote.state.value.lastError)
    }

    private companion object { const val CODE = "123456" }
}
