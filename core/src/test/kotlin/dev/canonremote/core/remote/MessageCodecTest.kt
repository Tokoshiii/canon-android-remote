package dev.canonremote.core.remote

import dev.canonremote.core.CameraSetting
import dev.canonremote.core.CameraState
import dev.canonremote.core.ConnectionStatus
import dev.canonremote.core.FocusStep
import dev.canonremote.core.SettingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MessageCodecTest {
    private fun roundTrip(m: Message) = assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))

    private val state = CameraState(
        status = ConnectionStatus.CONNECTED, model = "EOS 90D", serial = "123",
        settings = mapOf(
            CameraSetting.ISO to SettingState(0x48, listOf(0x48, 0x50)),
            CameraSetting.SHUTTER to SettingState(null, emptyList()),
        ),
        liveView = true, busy = true, lastError = "oops",
    )

    @Test fun roundTripsAllMessages() {
        roundTrip(Message.Hello(MessageCodec.PROTOCOL, "tok", "phone"))
        roundTrip(Message.Hello(MessageCodec.PROTOCOL, null, "phone"))
        roundTrip(Message.Welcome("EOS 90D", "123"))
        roundTrip(Message.Welcome(null, null))
        roundTrip(Message.Rejected("bad token"))
        roundTrip(Message.StateMsg(state.toRemote()))
        roundTrip(Message.Command(1, Action.Set(CameraSetting.ISO, 0x48)))
        roundTrip(Message.Command(2, Action.Shoot))
        roundTrip(Message.Command(3, Action.Autofocus))
        roundTrip(Message.Command(4, Action.Focus(FocusStep.FAR_2)))
        roundTrip(Message.Command(5, Action.LiveView(true)))
        roundTrip(Message.Result(1, true))
        roundTrip(Message.Result(2, false, "err"))
        roundTrip(Message.Ping)
        roundTrip(Message.Pong)
    }

    @Test fun commandJsonShape() {
        val json = MessageCodec.encode(Message.Command(1, Action.Set(CameraSetting.ISO, 0x48)))
        assertTrue(json, json.contains("\"type\":\"command\""))
        assertTrue(json, json.contains("\"type\":\"set\""))
    }

    @Test fun garbageThrowsProtocolException() {
        for (s in listOf("not json", "{\"type\":\"nope\"}", "{}", "")) {
            try { MessageCodec.decode(s); fail("expected for $s") } catch (_: ProtocolException) {}
        }
    }

    @Test fun cameraStateRoundTrip() {
        assertEquals(state, state.toRemote().toCameraState())
    }
}
