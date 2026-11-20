package dev.canonremote.core.remote

import dev.canonremote.core.CameraSetting
import dev.canonremote.core.CameraState
import dev.canonremote.core.ConnectionStatus
import dev.canonremote.core.FocusStep
import dev.canonremote.core.SettingState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
sealed interface Message {
    @Serializable @SerialName("hello")
    data class Hello(val protocol: Int, val token: String?, val clientName: String) : Message

    @Serializable @SerialName("welcome")
    data class Welcome(val model: String?, val serial: String?) : Message

    @Serializable @SerialName("rejected")
    data class Rejected(val reason: String) : Message

    @Serializable @SerialName("state")
    data class StateMsg(val state: RemoteState) : Message

    @Serializable @SerialName("command")
    data class Command(val id: Int, val action: Action) : Message

    @Serializable @SerialName("result")
    data class Result(val id: Int, val ok: Boolean, val error: String? = null) : Message

    @Serializable @SerialName("ping")
    data object Ping : Message

    @Serializable @SerialName("pong")
    data object Pong : Message
}

@Serializable
sealed interface Action {
    @Serializable @SerialName("set")
    data class Set(val setting: CameraSetting, val value: Int) : Action

    @Serializable @SerialName("shoot")
    data object Shoot : Action

    @Serializable @SerialName("autofocus")
    data object Autofocus : Action

    @Serializable @SerialName("focus")
    data class Focus(val step: FocusStep) : Action

    @Serializable @SerialName("liveView")
    data class LiveView(val on: Boolean) : Action
}

@Serializable
data class RemoteSetting(val setting: CameraSetting, val current: Int? = null, val available: List<Int> = emptyList())

@Serializable
data class RemoteState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val model: String? = null,
    val serial: String? = null,
    val settings: List<RemoteSetting> = emptyList(),
    val liveView: Boolean = false,
    val busy: Boolean = false,
    val lastError: String? = null,
    val shootingMode: Int? = null,
    val focusMode: Int? = null,
)

fun CameraState.toRemote() = RemoteState(
    status, model, serial,
    settings.map { (k, v) -> RemoteSetting(k, v.current, v.available) },
    liveView, busy, lastError, shootingMode, focusMode,
)

fun RemoteState.toCameraState() = CameraState(
    status, model, serial,
    settings.associate { it.setting to SettingState(it.current, it.available) },
    liveView, busy, lastError, shootingMode, focusMode,
)
