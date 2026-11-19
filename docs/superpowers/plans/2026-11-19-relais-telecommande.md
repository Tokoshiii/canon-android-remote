# Relais & Télécommande Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. TDD for every :core task.

**Goal:** Modes Relais et Télécommande (Wi-Fi local, point d'accès + QR, Bluetooth) per the spec.
**Architecture:** Pure-JVM protocol + relay server + remote client in `:core` (package `dev.canonremote.core.remote`), tested relay↔remote over an in-memory channel; Android transports/UI in `:app`, verified by CI.
**Tech Stack:** Kotlin 2.1.0, kotlinx.serialization-json 1.7.3 (plugin `org.jetbrains.kotlin.plugin.serialization` 2.1.0), coroutines 1.9.0, Ktor 3.0.3 (server-cio, server-websockets, client-cio, client-websockets) in `:app`, zxing core 3.5.3, play-services-code-scanner 16.1.0.
**Spec:** `docs/superpowers/specs/2026-11-19-relais-telecommande-design.md`

## Global Constraints
- `:core` stays pure JVM (no Android imports). Local tests: `./gradlew -PskipApp=true test`. `:app` verified by CI only.
- Protocol version `1`. Ping every 2000 ms; link lost after 6000 ms without any incoming message. Remote auto-reconnect window 15 000 ms.
- One remote at a time; a second gets `Rejected("Relais occupé")`.
- Wi-Fi links require the 6-digit pairing code (`token`); Bluetooth links pass `token = null` and are accepted without code (system pairing authenticates).
- On link loss the relay does NOT touch the camera (no setLiveView(false), no settings change).
- Live view over Bluetooth: relay rejects `LiveView(true)` with error "Live view indisponible en Bluetooth"; remote UI shows the warning dialog instead of sending.
- UI texts in French. Existing messages/behaviour of Direct mode unchanged.
- Commit trailer lines as in previous tasks.

## Review Focus
1. Link drops mid-command → remote reports "<action> non confirmé — vérifie l'appareil", camera untouched on relay. (Task 4/5)
2. Remote reconnects → receives full current state immediately. (Task 4)
3. Slow link → live view frames latest-wins, no unbounded queue. (Task 4)
4. Wrong code / wrong protocol version / relay busy → clear French rejection message on the remote. (Tasks 4/5)
5. Silent link (out of range, no socket error) → detected by ping timeout. (Tasks 4/5)

---

### Task 1: `CameraController` interface
Files: Create `core/.../CameraController.kt`; Modify `CanonCamera.kt` (implements it); app `CameraViewModel` uses `CameraController` type.
Interface: `interface CameraController { val state: StateFlow<CameraState>; val liveViewFrames: StateFlow<ByteArray?>; suspend fun set(setting: CameraSetting, value: Int): Boolean; suspend fun shoot(): Boolean; suspend fun autofocus(): Boolean; suspend fun focus(step: FocusStep): Boolean; suspend fun setLiveView(on: Boolean): Boolean; fun clearError(); suspend fun disconnect() }`.
Test: a compile-level test `CanonCameraIsCameraController` assigning `CanonCamera` to `CameraController` and running one existing scenario through the interface.

### Task 2: Protocol messages + codec
Files: `core/.../remote/Messages.kt`, `MessageCodec.kt`; tests `MessageCodecTest`.
- `@Serializable sealed interface Message` with `@SerialName`: `Hello(protocol: Int, token: String?, clientName: String)`, `Welcome(model: String?, serial: String?)`, `Rejected(reason: String)`, `StateMsg(state: RemoteState)`, `Command(id: Int, action: Action)`, `Result(id: Int, ok: Boolean, error: String? = null)`, `Ping`, `Pong`.
- `@Serializable sealed interface Action`: `Set(setting: CameraSetting, value: Int)`, `Shoot`, `Autofocus`, `Focus(step: FocusStep)`, `LiveView(on: Boolean)`.
- `RemoteState` = serializable mirror of `CameraState` (status, model, serial, settings as list of `(setting, current, available)`, liveView, busy, lastError) with `fun CameraState.toRemote()` / `fun RemoteState.toCameraState()`.
- `object MessageCodec { const val PROTOCOL = 1; fun encode(m: Message): String; fun decode(text: String): Message /* throws ProtocolException on bad JSON/unknown type */ }`. JSON: `classDiscriminator = "type"`, `ignoreUnknownKeys = true`.
Tests: round-trip every message type; known JSON shape for `Command(1, Set(ISO, 0x48))` contains `"type":"command"`; decode garbage → `ProtocolException`; CameraState ↔ RemoteState round-trip.

### Task 3: `MessageChannel` + Bluetooth framing
Files: `core/.../remote/MessageChannel.kt`, `StreamFraming.kt`; test util `core/src/test/.../remote/MemoryChannel.kt`; tests `StreamFramingTest`.
- `sealed interface Incoming { data class Msg(val message: Message); class Jpeg(val bytes: ByteArray) }`
- `interface MessageChannel { val incoming: Flow<Incoming> /* completes or throws on close/error */; suspend fun send(message: Message); suspend fun sendJpeg(bytes: ByteArray); fun close() }`
- `StreamFraming`: frame = `type u8 (0=JSON UTF-8, 1=JPEG)`, `length u32 LE`, payload; `fun writeFrame(out: OutputStream, type: Int, payload: ByteArray)`, `fun readFrame(input: InputStream): Pair<Int, ByteArray>?` (null on clean EOF; `ProtocolException` on length > 16 MiB or truncated).
- `class StreamMessageChannel(input: InputStream, output: OutputStream, onClose: () -> Unit, io: CoroutineDispatcher) : MessageChannel` (used by Bluetooth in :app; JVM-testable with piped streams).
- `MemoryChannel.pair(): Pair<MessageChannel, MessageChannel>` (test-only) with `cut()` simulating a silent drop (no more delivery, no error) and `fail()` simulating an error.
Tests: frame round-trip, EOF → null, oversized → exception, StreamMessageChannel pair over PipedStreams exchanges a Message and a JPEG.

### Task 4: `RelayServer`
Files: `core/.../remote/RelayServer.kt`; tests `RelayServerTest` (use a `FakeController : CameraController` in test sources).
- `class RelayServer(camera: CameraController, log: LogBus, scope: CoroutineScope, pairingCode: String, pingIntervalMs: Long = 2000, linkTimeoutMs: Long = 6000)`; `val clients: StateFlow<RelayClient?>` (`data class RelayClient(name: String, link: LinkKind)`), `enum class LinkKind { WIFI, BLUETOOTH }`; `fun accept(channel: MessageChannel, link: LinkKind)` (launches a session).
- Session: first message must be `Hello`; protocol ≠ 1 → `Rejected("Version incompatible")`; WIFI and token ≠ code → `Rejected("Code d'appairage incorrect")`; another client active → `Rejected("Relais occupé")`; else `Welcome`, then immediately `StateMsg(current)`, then a `StateMsg` on every state change, live view JPEGs (conflated, latest wins; never on BLUETOOTH), `Result` for each `Command`; Ping every interval; no incoming for timeout → session ends. Session end: close channel, `clients = null`, log INFO "Télécommande déconnectée", camera untouched.
- `LiveView(true)` on BLUETOOTH → `Result(ok=false, error="Live view indisponible en Bluetooth")`.
Tests: handshake ok/wrong code/wrong version/busy; state pushed on connect and on change; commands forwarded and Result returned; BT live view rejected; silent cut → session ends after timeout and FakeController recorded no calls; reconnect gets full state; frames conflated (slow receiver sees latest).

### Task 5: `RemoteCamera`
Files: `core/.../remote/RemoteCamera.kt`; tests `RemoteCameraTest` (RelayServer + FakeController + MemoryChannel end-to-end, plus a scripted fake relay for edge cases).
- `class RemoteCamera(connect: suspend () -> MessageChannel, link: LinkKind, token: String?, clientName: String, log: LogBus, scope: CoroutineScope, pingIntervalMs = 2000, linkTimeoutMs = 6000, reconnectWindowMs = 15000, commandTimeoutMs = 10000) : CameraController`; `suspend fun connect()`; extra `val link: LinkKind`.
- Mirrors `StateMsg` into `state`; JPEGs into `liveViewFrames`; commands send `Command` and await `Result` (true/false; error → `lastError`). Not connected → false without sending.
- `setLiveView(true)` when `link == BLUETOOTH` → returns false, sets `lastError = "Le Bluetooth est trop lent pour le live view. Passe en Wi-Fi pour l'utiliser."` without sending.
- `Rejected(reason)` → DISCONNECTED, `lastError = reason`, no reconnect.
- Link lost (error, close, or no message for timeout): state DISCONNECTED with `lastError = "Connexion au relais perdue"`, pending commands complete false with `lastError = "<label> non confirmé — vérifie l'appareil"` (labels: Réglage <title>, Déclenchement, Mise au point, Live view); then reconnect attempts (CONNECTING) until `reconnectWindowMs` elapsed; success → CONNECTED with fresh state.
- `disconnect()` closes and stops reconnecting.
Tests: end-to-end set/shoot via real RelayServer; state mirrored; lost link during shoot → "Déclenchement non confirmé…"; silent cut → detected by timeout; reconnect within window restores CONNECTED; give up after window; rejected reasons surfaced; BT live view refused locally.

### Task 6: App — mode selection + relay (Wi-Fi local, hotspot, Bluetooth) + remote + UI
Files (app): `remote/WebSocketChannel.kt` (Ktor server + client sessions → MessageChannel), `remote/RelayService.kt` (foreground service, type connectedDevice, notification "Relais actif"), `remote/NsdHelper.kt` (`_canonremote._tcp`), `remote/HotspotHelper.kt` (LocalOnlyHotspot, IP of hotspot interface), `remote/QrPayload.kt` (`canonremote://connect?ssid=&pass=&host=&port=&code=` build/parse — JVM unit test in app/src/test), `remote/QrImage.kt` (zxing → ImageBitmap), `remote/BluetoothLinks.kt` (RFCOMM server/client, fixed UUID), ConnectionScreen rewrite (mode segmented buttons; Relais and Télécommande sections), CameraViewModel mode handling, live view Bluetooth dialog, manifest permissions/service.
- Port 8765, WebSocket path `/ws`. Text frame = JSON, binary frame = JPEG.
- Permissions: INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, CHANGE_NETWORK_STATE, NEARBY_WIFI_DEVICES (neverForLocation), ACCESS_FINE_LOCATION (maxSdk 32), BLUETOOTH_CONNECT, BLUETOOTH_SCAN (neverForLocation), BLUETOOTH_ADVERTISE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, POST_NOTIFICATIONS. Runtime requests before each feature.
