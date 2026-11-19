# Canon Remote V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** App Android (Compose, Material You) qui affiche les commandes d'un Canon EOS 90D (grisées hors connexion), se connecte en USB via PTP Canon, et dont l'APK debug est produite par GitHub Actions.

**Architecture:** Deux modules Kotlin/JVM purs (`:ptp` codec, `:core` session/caméra/journal/modèle d'UI) testables sans SDK Android, et un module `:app` (transport USB, ViewModel, UI Compose) compilé uniquement en CI.

**Tech Stack:** Gradle 8.14.3 (wrapper), Kotlin 2.1.0, AGP 8.7.3, Compose BOM 2024.12.01, Material 3, kotlinx-coroutines 1.9.0 (+ coroutines-test), JUnit 4.13.2, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-19-canon-remote-v1-design.md`

## Global Constraints

- Appareil visé : Canon EOS 90D uniquement ; vendor ID USB `0x04A9` (1193).
- `minSdk = 31`, `compileSdk = 35`, `targetSdk = 35`, `applicationId = "dev.canonremote"`, JVM target 17.
- Pas de simulateur ni de mode démo dans l'app (les faux transports n'existent que dans `src/test`).
- Sans appareil connecté : toutes les commandes visibles, `enabled = false`, valeurs affichées « — ».
- Live view désactivé par défaut.
- Valeur inconnue → libellé hexa `"0x%02X"`, jamais d'exception.
- Tout échec PTP → entrée de journal niveau `ERROR` + message court à l'écran.
- Textes de l'UI en français.
- Le SDK Android n'est pas disponible en local : `:app` est exclu localement via `-PskipApp=true` ; vérification de `:app` = CI verte.
- Clé debug fixe commitée `app/debug.keystore` (store/key password `android`, alias `androiddebugkey`).

## Review Focus

1. Câble débranché en pleine session (lecture du transport qui lève une exception) → état `DISCONNECTED`, log `ERROR`, pas de crash, boucles arrêtées. (Task 8)
2. Paquet `GetEvent` tronqué ou corrompu → événements déjà lus conservés, reste ignoré et logué, aucune exception. (Task 3)
3. Appareil occupé (`0x2019 DeviceBusy`) lors d'un réglage ou d'un déclenchement → valeur inchangée, erreur remontée, boucle d'événements continue. (Task 9)
4. Image live view absente / réponse `0xA102` (pas prête) → image ignorée, boucle continue. (Tasks 5 et 9)
5. Double appui rapide sur Déclencher → un seul déclenchement (le second est ignoré pendant `busy`). (Task 9)

---

## Structure des fichiers

```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradlew*, gradle/wrapper/*
ptp/src/main/kotlin/dev/canonremote/ptp/
  PtpBytes.kt         lecture/écriture little-endian, chaînes PTP
  PtpCodes.kt         codes d'opérations, réponses, propriétés, événements
  PtpContainer.kt     conteneur USB PTP encode/decode
  DeviceInfo.kt       décodage GetDeviceInfo
  CanonEvents.kt      parseur GetEvent Canon
  CanonValues.kt      tables valeur ↔ libellé, payload SetDevicePropValueEx
  LiveView.kt         extraction JPEG de GetViewFinderData
core/src/main/kotlin/dev/canonremote/core/
  LogBus.kt           journal borné
  Transport.kt        interface de transport
  PtpSession.kt       transactions PTP
  CameraState.kt      état observable
  CanonCamera.kt      pilotage 90D
  ControlsModel.kt    modèle d'UI pur (activé/désactivé, libellés)
core/src/test/kotlin/dev/canonremote/core/FakeTransport.kt
app/…               (Tasks 11–13)
.github/workflows/android.yml
```

---

### Task 1: Squelette Gradle + `:ptp` octets et conteneur PTP

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, wrapper (`gradle wrapper --gradle-version 8.14.3`), `.gitignore` (build/, .gradle/, local.properties, *.iml, .idea/)
- Create: `ptp/build.gradle.kts` (plugin `kotlin("jvm")`, JUnit 4)
- Create: `ptp/src/main/kotlin/dev/canonremote/ptp/PtpBytes.kt`, `PtpCodes.kt`, `PtpContainer.kt`
- Test: `ptp/src/test/kotlin/dev/canonremote/ptp/PtpContainerTest.kt`

**Interfaces:**
- Produces:
  - `class ByteWriter { fun u8(v: Int); fun u16(v: Int); fun u32(v: Int); fun bytes(b: ByteArray); fun toByteArray(): ByteArray }`
  - `class ByteReader(val data: ByteArray, var pos: Int = 0) { val remaining: Int; fun u8(): Int; fun u16(): Int; fun u32(): Int /* signé Int, bits bruts */; fun bytes(n: Int): ByteArray; fun ptpString(): String; fun u16Array(): List<Int>; fun u32Array(): List<Int> }` — lève `PtpFormatException(message)` si dépassement.
  - `object PtpOp { GET_DEVICE_INFO=0x1001; OPEN_SESSION=0x1002; CLOSE_SESSION=0x1003 }`
  - `object CanonOp { SET_DEVICE_PROP_VALUE_EX=0x9110; SET_REMOTE_MODE=0x9114; SET_EVENT_MODE=0x9115; GET_EVENT=0x9116; REMOTE_RELEASE_ON=0x9128; REMOTE_RELEASE_OFF=0x9129; GET_VIEWFINDER_DATA=0x9153; DO_AF=0x9154; DRIVE_LENS=0x9155 }`
  - `object PtpResponse { OK=0x2001; DEVICE_BUSY=0x2019; SESSION_ALREADY_OPEN=0x201E; CANON_NOT_READY=0xA102 }`
  - `object CanonProp { APERTURE=0xD101; SHUTTER_SPEED=0xD102; ISO=0xD103; WHITE_BALANCE=0xD109; EVF_OUTPUT_DEVICE=0xD1B0 }`
  - `object CanonEventCode { PROP_VALUE_CHANGED=0xC189; AVAILABLE_LIST_CHANGED=0xC18A }`
  - `enum class ContainerType(val code: Int) { COMMAND(1), DATA(2), RESPONSE(3), EVENT(4) }`
  - `data class PtpContainer(val type: ContainerType, val code: Int, val transactionId: Int, val params: List<Int> = emptyList(), val payload: ByteArray = ByteArray(0)) { fun encode(): ByteArray; companion object { fun decode(bytes: ByteArray): PtpContainer } }` — COMMAND/RESPONSE/EVENT : `params` (0..5 u32) ; DATA : `payload` brut. `equals`/`hashCode` comparent `payload` par contenu.

- [ ] **Step 1: Écrire la config Gradle.** `settings.gradle.kts` : `rootProject.name = "canon-remote"`, `include(":ptp", ":core")`, et `if (providers.gradleProperty("skipApp").orNull != "true") include(":app")`. Dépôts `google()`, `mavenCentral()`. Versions dans `libs.versions.toml` (voir Tech Stack).

- [ ] **Step 2: Écrire les tests qui échouent** (`PtpContainerTest`) :
  - `encodesOpenSessionCommand` : `PtpContainer(COMMAND, 0x1002, 0, listOf(1)).encode()` == octets `10 00 00 00 01 00 02 10 00 00 00 00 01 00 00 00`.
  - `decodesOkResponse` : décoder `0C 00 00 00 03 00 01 20 05 00 00 00` → type RESPONSE, code 0x2001, transactionId 5, params vides.
  - `roundTripsDataContainer` : DATA 0x9110, tid 7, payload `[0x0C,0,0,0,0x03,0xD1,0,0,0x48,0,0,0]` → encode puis decode == original ; longueur encodée 24.
  - `decodeRejectsTruncatedHeader` : 6 octets → `PtpFormatException`.
  - `decodeRejectsLengthMismatch` : en-tête annonçant 20 octets pour 12 reçus → `PtpFormatException`.
  - `readerReadsPtpString` : `[3, 'H',0,'i',0, 0,0]` → `"Hi"` ; `[0]` → `""`.

- [ ] **Step 3: Lancer** `gradle -PskipApp=true :ptp:test` (avant wrapper) / `./gradlew -PskipApp=true :ptp:test` → FAIL (classes absentes).
- [ ] **Step 4: Implémenter** `PtpBytes.kt`, `PtpCodes.kt`, `PtpContainer.kt`. Chaîne PTP : u8 = nombre de caractères UTF-16LE *y compris* le nul final, 0 = chaîne vide.
- [ ] **Step 5: Lancer** `./gradlew -PskipApp=true :ptp:test` → PASS.
- [ ] **Step 6: Commit** `feat(ptp): conteneur PTP et lecture/écriture little-endian`.

### Task 2: Décodage `GetDeviceInfo`

**Files:** Create `ptp/src/main/kotlin/dev/canonremote/ptp/DeviceInfo.kt`; Test `ptp/src/test/kotlin/dev/canonremote/ptp/DeviceInfoTest.kt`

**Interfaces:**
- Produces: `data class DeviceInfo(val standardVersion: Int, val vendorExtensionId: Int, val vendorExtensionDesc: String, val operationsSupported: List<Int>, val eventsSupported: List<Int>, val devicePropertiesSupported: List<Int>, val manufacturer: String, val model: String, val deviceVersion: String, val serialNumber: String) { companion object { fun decode(data: ByteArray): DeviceInfo } }`
- Ordre du dataset : standardVersion u16, vendorExtId u32, vendorExtVersion u16, vendorExtDesc str, functionalMode u16, ops u16[], events u16[], props u16[], captureFormats u16[], imageFormats u16[], manufacturer str, model str, deviceVersion str, serialNumber str.

- [ ] **Step 1: Test** `decodesCanonDeviceInfo` : construire le dataset avec `ByteWriter` (helper de test `ptpString(s)`) avec manufacturer `"Canon Inc."`, model `"Canon EOS 90D"`, serial `"0123456789"`, ops `[0x1001, 0x9116]` → champs égaux. `decodeTruncatedThrowsFormatException` : dataset coupé après `model` → `PtpFormatException`.
- [ ] **Step 2: Lancer** `./gradlew -PskipApp=true :ptp:test` → FAIL.
- [ ] **Step 3: Implémenter** `DeviceInfo.decode` avec `ByteReader`.
- [ ] **Step 4: Lancer** → PASS.
- [ ] **Step 5: Commit** `feat(ptp): décodage GetDeviceInfo`.

### Task 3: Parseur d'événements Canon (`GetEvent`)

**Files:** Create `ptp/src/main/kotlin/dev/canonremote/ptp/CanonEvents.kt`; Test `ptp/src/test/kotlin/dev/canonremote/ptp/CanonEventsTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface CanonEvent { data class PropValueChanged(val prop: Int, val value: Int); data class AvailableValuesChanged(val prop: Int, val values: List<Int>); data class Unknown(val type: Int, val size: Int); data class Malformed(val offset: Int, val reason: String) }`
  - `fun parseCanonEvents(data: ByteArray): List<CanonEvent>`
- Format : suite d'enregistrements `size u32, type u32, corps (size-8)`. `0xC189` corps = `prop u32, value u32`. `0xC18A` corps = `prop u32, dataType u32, count u32, values u32 × count`. Fin sur `type == 0` ou fin des données. `size < 8` ou dépassant les données → ajouter `Malformed` et arrêter.

- [ ] **Step 1: Tests :**
  - `parsesPropChangedAndAvailableList` : enregistrement C189 (ISO 0xD103 = 0x58) + C18A (ISO, type 3, count 3, `[0x48,0x50,0x58]`) + terminateur `08 00 00 00 00 00 00 00` → `[PropValueChanged(0xD103,0x58), AvailableValuesChanged(0xD103, listOf(0x48,0x50,0x58))]`.
  - `skipsUnknownRecords` : enregistrement type 0xC1A7 de 16 octets puis C189 → `[Unknown(0xC1A7,16), PropValueChanged(...)]`.
  - `emptyDataGivesEmptyList` : `ByteArray(0)` → `[]` ; terminateur seul → `[]`.
  - `truncatedRecordKeepsPreviousEvents` *(Review Focus 2)* : C189 valide puis en-tête annonçant 40 octets avec 12 restants → `[PropValueChanged(...), Malformed(offset=16, …)]`, pas d'exception.
  - `sizeBelowHeaderIsMalformed` : `size=4` → `[Malformed(0, …)]`.
  - `availableListWithOverflowingCountIsMalformed` : count = 1000 dans un enregistrement de 20 octets → `Malformed`.
- [ ] **Step 2: Lancer** → FAIL.
- [ ] **Step 3: Implémenter** `parseCanonEvents` (chaque corps lu via un `ByteReader` borné à `size-8` ; toute `PtpFormatException` d'un corps → `Malformed` et arrêt).
- [ ] **Step 4: Lancer** → PASS.
- [ ] **Step 5: Commit** `feat(ptp): parseur d'événements Canon`.

### Task 4: Tables de valeurs Canon + payload de réglage

**Files:** Create `ptp/src/main/kotlin/dev/canonremote/ptp/CanonValues.kt`; Test `ptp/src/test/kotlin/dev/canonremote/ptp/CanonValuesTest.kt`

**Interfaces:**
- Produces:
  - `object CanonValues { fun label(prop: Int, value: Int): String; fun setPropPayload(prop: Int, value: Int): ByteArray }`
  - `setPropPayload` = 12 octets `0x0C u32, prop u32, value u32`.
- Tables (libgphoto2) — copier exactement :
  - ISO `0xD103` : 0x00 "Auto", 0x40 "50", 0x48 "100", 0x4B "125", 0x4D "160", 0x50 "200", 0x53 "250", 0x55 "320", 0x58 "400", 0x5B "500", 0x5D "640", 0x60 "800", 0x63 "1000", 0x65 "1250", 0x68 "1600", 0x6B "2000", 0x6D "2500", 0x70 "3200", 0x73 "4000", 0x75 "5000", 0x78 "6400", 0x7B "8000", 0x7D "10000", 0x80 "12800", 0x83 "16000", 0x85 "20000", 0x88 "25600", 0x90 "51200".
  - Vitesse `0xD102` : 0x00 "Auto", 0x0C "Bulb", 0x10 "30\"", 0x13 "25\"", 0x14 "20\"", 0x15 "20\"", 0x18 "15\"", 0x1B "13\"", 0x1C "10\"", 0x1D "10\"", 0x20 "8\"", 0x23 "6\"", 0x24 "6\"", 0x25 "5\"", 0x28 "4\"", 0x2B "3\"2", 0x2C "3\"", 0x2D "2\"5", 0x30 "2\"", 0x33 "1\"6", 0x34 "1\"5", 0x35 "1\"3", 0x38 "1\"", 0x3B "0\"8", 0x3C "0\"7", 0x3D "0\"6", 0x40 "0\"5", 0x43 "0\"4", 0x44 "0\"3", 0x45 "0\"3", 0x48 "1/4", 0x4B "1/5", 0x4C "1/6", 0x4D "1/6", 0x50 "1/8", 0x53 "1/10", 0x54 "1/10", 0x55 "1/13", 0x58 "1/15", 0x5B "1/20", 0x5C "1/20", 0x5D "1/25", 0x60 "1/30", 0x63 "1/40", 0x64 "1/45", 0x65 "1/50", 0x68 "1/60", 0x6B "1/80", 0x6C "1/90", 0x6D "1/100", 0x70 "1/125", 0x73 "1/160", 0x74 "1/180", 0x75 "1/200", 0x78 "1/250", 0x7B "1/320", 0x7C "1/350", 0x7D "1/400", 0x80 "1/500", 0x83 "1/640", 0x84 "1/750", 0x85 "1/800", 0x88 "1/1000", 0x8B "1/1250", 0x8C "1/1500", 0x8D "1/1600", 0x90 "1/2000", 0x93 "1/2500", 0x94 "1/3000", 0x95 "1/3200", 0x98 "1/4000", 0x9B "1/5000", 0x9C "1/6000", 0x9D "1/6400", 0xA0 "1/8000".
  - Ouverture `0xD101` (préfixe "f/") : 0x08 1.0, 0x0B 1.1, 0x0C 1.2, 0x0D 1.2, 0x10 1.4, 0x13 1.6, 0x14 1.8, 0x15 1.8, 0x18 2.0, 0x1B 2.2, 0x1C 2.5, 0x1D 2.5, 0x20 2.8, 0x23 3.2, 0x24 3.5, 0x25 3.5, 0x28 4.0, 0x2B 4.5, 0x2C 4.5, 0x2D 5.0, 0x30 5.6, 0x33 6.3, 0x34 6.7, 0x35 7.1, 0x38 8.0, 0x3B 9.0, 0x3C 9.5, 0x3D 10, 0x40 11, 0x43 13, 0x44 13, 0x45 14, 0x48 16, 0x4B 18, 0x4C 19, 0x4D 20, 0x50 22, 0x53 25, 0x54 27, 0x55 29, 0x58 32, 0x5B 36, 0x5C 38, 0x5D 40, 0x60 45, 0x63 51, 0x64 54, 0x65 57, 0x68 64, 0x6B 72, 0x6C 76, 0x6D 80, 0x70 91. 0xFF → "—" (pas d'objectif / ouverture inconnue).
  - Balance des blancs `0xD109` : 0 "Auto", 1 "Lumière du jour", 2 "Nuageux", 3 "Tungstène", 4 "Fluorescent", 5 "Flash", 6 "Personnalisée", 8 "Ombre", 9 "Température K", 23 "Auto (blanc)".
  - Autre propriété ou valeur absente → `"0x%02X".format(value)`.

- [ ] **Step 1: Tests :** `isoLabels` (0x58→"400", 0x00→"Auto"), `shutterLabels` (0x78→"1/250", 0x10→"30\"", 0x0C→"Bulb"), `apertureLabels` (0x30→"f/5.6", 0x20→"f/2.8"), `whiteBalanceLabels` (0→"Auto", 8→"Ombre"), `unknownValueIsHex` *(Review Focus 4)* (ISO 0x41→"0x41", prop 0xD1FF valeur 3→"0x03"), `setPropPayloadLayout` (ISO 0x48 → `0C 00 00 00 03 D1 00 00 48 00 00 00`).
- [ ] **Step 2: Lancer** → FAIL. **Step 3: Implémenter.** **Step 4: Lancer** → PASS.
- [ ] **Step 5: Commit** `feat(ptp): tables de valeurs Canon`.

### Task 5: Extraction JPEG du live view

**Files:** Create `ptp/src/main/kotlin/dev/canonremote/ptp/LiveView.kt`; Test `ptp/src/test/kotlin/dev/canonremote/ptp/LiveViewTest.kt`

**Interfaces:**
- Produces: `fun extractLiveViewJpeg(data: ByteArray): ByteArray?`
- Algorithme : parcourir des blocs `size u32, type u32, données (size-8)` ; renvoyer les données du premier bloc `type == 1` qui commencent par `FF D8`. Si la structure en blocs est invalide, repli : chercher le premier `FF D8` et le dernier `FF D9` et renvoyer la tranche incluse. Sinon `null`.

- [ ] **Step 1: Tests :** `extractsJpegBlock` (bloc type 1 contenant `FF D8 01 02 FF D9` suivi d'un bloc type 4 → les 6 octets), `fallsBackToMarkerScan` (octets aléatoires + `FF D8 AA FF D9` + octets → `FF D8 AA FF D9`), `returnsNullWithoutJpeg` *(Review Focus 4)* (`ByteArray(0)` et 32 octets zéro → `null`).
- [ ] **Step 2–4:** FAIL → implémenter → PASS (`./gradlew -PskipApp=true :ptp:test`).
- [ ] **Step 5: Commit** `feat(ptp): extraction JPEG live view`.

### Task 6: `:core` journal (`LogBus`)

**Files:** Create `core/build.gradle.kts` (kotlin jvm, dépend de `:ptp`, coroutines-core ; test : junit, coroutines-test), `core/src/main/kotlin/dev/canonremote/core/LogBus.kt`; Test `core/src/test/kotlin/dev/canonremote/core/LogBusTest.kt`

**Interfaces:**
- Produces:
  - `enum class LogLevel { INFO, WARN, ERROR, PTP }`
  - `data class LogEntry(val timeMillis: Long, val level: LogLevel, val message: String)`
  - `class LogBus(private val capacity: Int = 5000, private val clock: () -> Long = System::currentTimeMillis) { val entries: StateFlow<List<LogEntry>>; fun info(msg: String); fun warn(msg: String); fun error(msg: String, t: Throwable? = null); fun ptp(msg: String); fun clear(); fun export(header: String): String }`
  - Ligne exportée : `"HH:mm:ss.SSS LEVEL message"` (heure locale), précédée de `header` puis ligne vide. `error` avec `t` ajoute `": ${t::class.simpleName}: ${t.message}"`.
  - `fun ByteArray.toHexDump(max: Int = 64): String` — octets en hexa séparés par espace, suffixe `" … (+N octets)"` si tronqué (dans `LogBus.kt`).

- [ ] **Step 1: Tests :** `keepsOnlyLastCapacityEntries` (capacity 3, 5 messages → les 3 derniers), `exportFormatsLines` (clock fixe → contient header puis `"INFO bonjour"`), `errorIncludesThrowable`, `clearEmpties`, `hexDumpTruncates` (`ByteArray(70)` max 64 → finit par `"(+6 octets)"`).
- [ ] **Step 2: Lancer** `./gradlew -PskipApp=true :core:test` → FAIL. **Step 3: Implémenter** (thread-safe via `MutableStateFlow.update`). **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(core): journal LogBus`.

### Task 7: `Transport` + `PtpSession`

**Files:** Create `core/src/main/kotlin/dev/canonremote/core/Transport.kt`, `PtpSession.kt`; Create `core/src/test/kotlin/dev/canonremote/core/FakeTransport.kt`; Test `core/src/test/kotlin/dev/canonremote/core/PtpSessionTest.kt`

**Interfaces:**
- Produces:
  - `interface Transport { val name: String; suspend fun send(bytes: ByteArray); suspend fun receive(): ByteArray /* exactement un conteneur PTP complet */; fun close() }`
  - `class PtpException(val responseCode: Int, message: String) : Exception(message)`
  - `data class PtpResult(val responseCode: Int, val params: List<Int>, val data: ByteArray?) { val isOk: Boolean }`
  - `class PtpSession(private val transport: Transport, private val log: LogBus) { suspend fun execute(op: Int, params: List<Int> = emptyList(), dataOut: ByteArray? = null, logData: Boolean = true): PtpResult; suspend fun executeOk(op: Int, params: List<Int> = emptyList(), dataOut: ByteArray? = null, logData: Boolean = true): PtpResult /* lève PtpException si != OK */ }`
  - Déroulé : `Mutex` ; tid incrémenté à chaque transaction (OpenSession utilise tid 0, ensuite 1, 2, …) ; envoie COMMAND ; si `dataOut`, envoie DATA même code ; reçoit ; si DATA, la garde et reçoit la RESPONSE ; un conteneur EVENT reçu est logué et ignoré. Logs `PTP` : `"→ 0x9116 tid=3 [params]"`, `"← DATA 1234 o: <hex>"` (hex omis si `logData = false`), `"← 0x2001"`.
  - `FakeTransport` (test) : `val sent: MutableList<PtpContainer>` ; `var handler: (PtpContainer) -> List<PtpContainer>` appelé à chaque COMMAND reçue (après sa DATA éventuelle) pour fournir les réponses ; `var failNextReceive: Throwable?` ; helper `fun respond(cmd, code = 0x2001, data: ByteArray? = null): List<PtpContainer>`.

- [ ] **Step 1: Tests :** `sendsCommandAndReadsResponse` (OpenSession → sent[0] COMMAND 0x1002 tid 0 params [1], résultat OK), `transactionIdsIncrement`, `readsDataPhase` (GetDeviceInfo → `data` == payload), `sendsDataPhaseAfterCommand` (dataOut → sent = [COMMAND, DATA] même tid), `executeOkThrowsOnBusy` (réponse 0x2019 → `PtpException(0x2019)`), `skipsInterleavedEvent`, `concurrentCallsAreSerialized` (deux `async` → conteneurs non entrelacés), `logsEachPacket` (au moins une ligne `PTP` commençant par "→").
- [ ] **Step 2–4:** FAIL → implémenter → PASS.
- [ ] **Step 5: Commit** `feat(core): transport et session PTP`.

### Task 8: `CanonCamera` — connexion, boucle d'événements, état

**Files:** Create `core/src/main/kotlin/dev/canonremote/core/CameraState.kt`, `CanonCamera.kt`; Test `core/src/test/kotlin/dev/canonremote/core/CanonCameraConnectTest.kt`

**Interfaces:**
- Consumes: `PtpSession`, `parseCanonEvents`, `DeviceInfo.decode`, `CanonProp`, `CanonOp`.
- Produces:
  - `enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }`
  - `enum class CameraSetting(val prop: Int, val title: String) { ISO(0xD103, "ISO"), SHUTTER(0xD102, "Vitesse"), APERTURE(0xD101, "Ouverture"), WHITE_BALANCE(0xD109, "Balance des blancs") }`
  - `data class SettingState(val current: Int? = null, val available: List<Int> = emptyList())`
  - `data class CameraState(val status: ConnectionStatus = DISCONNECTED, val model: String? = null, val serial: String? = null, val settings: Map<CameraSetting, SettingState> = emptyMap(), val liveView: Boolean = false, val busy: Boolean = false, val lastError: String? = null)`
  - `class CanonCamera(transport: Transport, private val log: LogBus, private val scope: CoroutineScope, private val pollIntervalMs: Long = 200) { val state: StateFlow<CameraState>; val liveViewFrames: StateFlow<ByteArray?>; suspend fun connect(); suspend fun disconnect() }`
  - `connect()` : CONNECTING → `OpenSession(1)` (accepter `0x201E`) → `GetDeviceInfo` (model/serial + log INFO) → `SetRemoteMode(1)` → `SetEventMode(1)` → un `GetEvent` appliqué → CONNECTED → lance la boucle `GetEvent` toutes les `pollIntervalMs` dans `scope`. `PropValueChanged` met à jour `current`, `AvailableValuesChanged` met à jour `available`, pour les 4 propriétés de `CameraSetting` ; `Unknown`/`Malformed` → log WARN (Malformed) ou ignoré (Unknown).
  - Toute exception du transport (pas `PtpException`) dans `connect` ou une boucle → `handleTransportFailure` : annule les boucles, `transport.close()`, état `CameraState(status = DISCONNECTED, lastError = "Appareil déconnecté")`, log ERROR.
  - `disconnect()` : arrête les boucles, stoppe le live view si actif, `CloseSession` (erreurs ignorées), `close()`, état initial.

- [ ] **Step 1: Tests** (`runTest`, `scope = backgroundScope`, `advanceTimeBy`) :
  - `connectRunsHandshakeInOrder` : codes envoyés commencent par `[0x1002, 0x1001, 0x9114, 0x9115, 0x9116]` ; état CONNECTED ; model `"Canon EOS 90D"`.
  - `initialEventsFillSettings` : 1er GetEvent renvoie C18A ISO `[0x48,0x50]` + C189 ISO 0x50 → `settings[ISO] == SettingState(0x50, listOf(0x48,0x50))`.
  - `pollingAppliesLaterChanges` : 2e GetEvent renvoie C189 ISO 0x48 → après `advanceTimeBy(250)` current == 0x48.
  - `unplugDuringPollingDisconnects` *(Review Focus 1)* : `failNextReceive = IOException()` après connexion → après `advanceTimeBy(250)` status DISCONNECTED, `lastError == "Appareil déconnecté"`, une entrée ERROR, plus aucun envoi ensuite.
  - `failedHandshakeLeavesDisconnected` : OpenSession → `IOException` → DISCONNECTED, `connect()` ne propage pas.
  - `disconnectClosesSession` : dernier code envoyé 0x1003, status DISCONNECTED.
- [ ] **Step 2–4:** FAIL → implémenter → PASS (`./gradlew -PskipApp=true :core:test`).
- [ ] **Step 5: Commit** `feat(core): connexion Canon et boucle d'événements`.

### Task 9: `CanonCamera` — réglages, déclenchement, mise au point, live view

**Files:** Modify `core/src/main/kotlin/dev/canonremote/core/CanonCamera.kt`; Test `core/src/test/kotlin/dev/canonremote/core/CanonCameraCommandsTest.kt`

**Interfaces:**
- Produces (dans `CanonCamera`) :
  - `enum class FocusStep(val code: Int) { NEAR_3(0x0003), NEAR_2(0x0002), NEAR_1(0x0001), FAR_1(0x8001), FAR_2(0x8002), FAR_3(0x8003) }` (fichier `CameraState.kt`)
  - `suspend fun set(setting: CameraSetting, value: Int): Boolean` — `SetDevicePropValueEx` avec `CanonValues.setPropPayload` ; ne modifie pas `current` localement (attend l'événement).
  - `suspend fun shoot(): Boolean` — ignoré (renvoie `false`, aucun envoi) si `busy` ; sinon `busy = true`, `RemoteReleaseOn(1,0)`, `RemoteReleaseOn(2,0)`, `RemoteReleaseOff(2)`, `RemoteReleaseOff(1)`, `busy = false` (aussi en cas d'erreur).
  - `suspend fun autofocus(): Boolean` — `DoAf`.
  - `suspend fun focus(step: FocusStep): Boolean` — `DriveLens(step.code)` ; renvoie `false` sans envoi si live view inactif.
  - `suspend fun setLiveView(on: Boolean): Boolean` — on : `0xD1B0 = 2`, état `liveView = true`, boucle `GetViewFinderData(0x00200000, 0, 0)` avec `logData = false` toutes les 50 ms ; `extractLiveViewJpeg` non nul → `liveViewFrames` ; réponse `0xA102`/`0x2019` ou JPEG nul → image ignorée. off : annule la boucle, `0xD1B0 = 0`, `liveView = false`, `liveViewFrames = null`.
  - Pour toutes : `PtpException` → log ERROR `"<action> refusé : 0x%04X"`, `lastError` mis à jour, renvoie `false`, boucles continuent. Hors CONNECTED → `false` sans envoi. `fun clearError()` remet `lastError` à null.

- [ ] **Step 1: Tests :**
  - `setSendsPropPayload` : `set(ISO, 0x48)` → DATA 0x9110 payload `0C 00 00 00 03 D1 00 00 48 00 00 00`, renvoie true.
  - `setWhenBusyReportsError` *(Review Focus 3)* : réponse 0x2019 → false, `lastError` contient "0x2019", `current` inchangé, polling toujours actif (un GetEvent envoyé après `advanceTimeBy(250)`).
  - `shootSendsReleaseSequence` : ops/params == `[(0x9128,[1,0]), (0x9128,[2,0]), (0x9129,[2]), (0x9129,[1])]`.
  - `secondShootWhileBusyIsIgnored` *(Review Focus 5)* : handler retardé (`delay(100)`) ; deux `shoot()` lancés en parallèle → une seule séquence envoyée, l'un renvoie false.
  - `focusRequiresLiveView` : sans live view → false, aucun envoi ; avec → DRIVE_LENS param `0x8001`.
  - `liveViewPublishesFrames` : GetViewFinderData renvoie bloc JPEG → `liveViewFrames.value` == JPEG ; puis `setLiveView(false)` → dernier SET 0xD1B0 = 0, frames null.
  - `liveViewSkipsNotReady` *(Review Focus 4)* : 1re réponse 0xA102, 2e JPEG → pas d'erreur, frame publiée.
  - `commandsWhenDisconnectedDoNothing` : sans connect → toutes renvoient false, `sent` vide.
- [ ] **Step 2–4:** FAIL → implémenter → PASS.
- [ ] **Step 5: Commit** `feat(core): réglages, déclenchement, AF et live view`.

### Task 10: Modèle d'UI pur (`ControlsModel`)

**Files:** Create `core/src/main/kotlin/dev/canonremote/core/ControlsModel.kt`; Test `core/src/test/kotlin/dev/canonremote/core/ControlsModelTest.kt`

**Interfaces:**
- Produces:
  - `data class TileModel(val setting: CameraSetting, val title: String, val valueLabel: String, val enabled: Boolean, val options: List<Pair<Int, String>>, val selected: Int?)`
  - `data class ControlsModel(val statusLabel: String, val connected: Boolean, val tiles: List<TileModel>, val shutterEnabled: Boolean, val afEnabled: Boolean, val manualFocusEnabled: Boolean, val liveViewToggleEnabled: Boolean, val liveViewOn: Boolean)`
  - `fun CameraState.toControls(): ControlsModel` — tuiles toujours dans l'ordre ISO, Vitesse, Ouverture, Balance des blancs ; `statusLabel` : "Déconnecté" / "Connexion…" / `model ?: "Connecté"` ; non connecté → `valueLabel = "—"`, tout `enabled = false` ; connecté → libellés `CanonValues.label`, tuile activée si `available` non vide, `shutterEnabled = !busy`, `manualFocusEnabled = liveView`.

- [ ] **Step 1: Tests :** `disconnectedShowsGreyedTiles` (`CameraState()` → 4 tuiles, toutes `enabled=false`, `"—"`, shutter/af/liveView désactivés, statut "Déconnecté"), `connectedShowsLabels` (ISO 0x58 dispo [0x48,0x58] → "400", options `[(0x48,"100"),(0x58,"400")]`), `tileWithoutAvailableValuesDisabled`, `busyDisablesShutter`, `manualFocusOnlyWithLiveView`.
- [ ] **Step 2–4:** FAIL → implémenter → PASS.
- [ ] **Step 5: Commit** `feat(core): modèle d'UI des commandes`.

### Task 11: Module `:app` (thème, navigation, écran Appareil) + CI

**Files:**
- Create: `app/build.gradle.kts` (plugins `com.android.application`, `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose` ; dépend de `:core` ; Compose BOM, material3, activity-compose, lifecycle-viewmodel-compose, lifecycle-runtime-compose, material-icons-extended ; `signingConfigs.debug` → `debug.keystore`)
- Create: `app/debug.keystore` (`keytool -genkeypair -v -keystore app/debug.keystore -storepass android -keypass android -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000`)
- Create: `app/src/main/AndroidManifest.xml` (`uses-feature android.hardware.usb.host required=false`, activité `MainActivity` exportée, label "Canon Remote")
- Create: `app/src/main/kotlin/dev/canonremote/app/MainActivity.kt`, `ui/theme/Theme.kt`, `ui/MainScreen.kt`, `ui/camera/CameraScreen.kt`, `ui/camera/SettingTile.kt`, `ui/camera/ValuePickerSheet.kt`, `ui/camera/FocusRow.kt`, `ui/camera/ShutterButton.kt`, `ui/camera/LiveViewCard.kt`, `ui/connection/ConnectionScreen.kt` (provisoire), `ui/logs/LogsScreen.kt` (provisoire)
- Create: `.github/workflows/android.yml`

**Interfaces:**
- Consumes: `ControlsModel`, `TileModel`, `FocusStep`.
- Produces:
  - `@Composable fun CanonRemoteTheme(content: @Composable () -> Unit)` — `dynamicLightColorScheme`/`dynamicDarkColorScheme` selon `isSystemInDarkTheme()`, edge-to-edge.
  - `@Composable fun MainScreen(controls: ControlsModel, frame: ByteArray?, actions: CameraActions, connection: @Composable () -> Unit, logs: @Composable () -> Unit)` — `NavigationBar` à 3 onglets « Appareil » (icône PhotoCamera), « Connexion » (Usb), « Logs » (Article) ; la puce de statut ouvre l'onglet Connexion.
  - `interface CameraActions { fun onSet(setting: CameraSetting, value: Int); fun onShoot(); fun onAutofocus(); fun onFocus(step: FocusStep); fun onLiveView(on: Boolean) }`
  - `CameraScreen` : disposition du croquis du spec — en-tête (titre + `AssistChip` statut), `LiveViewCard` 3:2 si `liveViewOn` (JPEG décodé par `BitmapFactory`, `remember(frame)`), grille 2×2 de `SettingTile` (`ElevatedCard` arrondie, titre `labelMedium`, valeur `headlineSmall`, alpha réduit si désactivée), `ModalBottomSheet` de choix (valeur sélectionnée surlignée), `FocusRow` (bouton « AF » + `◀◀ ◀ ▶ ▶▶` = NEAR_2, NEAR_1, FAR_1, FAR_2), ligne « Live view » + `Switch`, `ShutterButton` pilule pleine largeur hauteur 72 dp (`Button` avec `CircleShape`), texte « Déclencher ».
- Workflow `android.yml` : `on: [push, pull_request, workflow_dispatch]` ; `ubuntu-latest` ; `actions/checkout@v4` ; `actions/setup-java@v4` (temurin 17) ; `gradle/actions/setup-gradle@v4` ; `./gradlew test assembleDebug` ; `actions/upload-artifact@v4` name `canon-remote-debug`, path `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 1: Créer** le module, le keystore, le thème, `MainActivity` qui affiche `MainScreen` avec `CameraState().toControls()` et des actions vides, onglets Connexion/Logs avec texte provisoire.
- [ ] **Step 2: Vérifier localement** `./gradlew -PskipApp=true test` → PASS (rien de cassé).
- [ ] **Step 3: Commit + push**, puis vérifier le run GitHub Actions (`mcp__github__actions_list` / `get_job_logs`) → job vert, artefact `canon-remote-debug` présent. En cas d'échec de compilation : corriger d'après les logs, re-pousser.
- [ ] **Step 4: Commit** `feat(app): thème Material You, navigation et écran Appareil + CI`.

### Task 12: USB — transport, connexion, ViewModel, onglet Connexion

**Files:**
- Create: `app/src/main/kotlin/dev/canonremote/app/usb/UsbTransport.kt`, `usb/UsbConnector.kt`, `CameraViewModel.kt`, `CanonRemoteApp.kt` (Application, détient le `LogBus` unique)
- Modify: `ui/connection/ConnectionScreen.kt`, `MainActivity.kt`, `AndroidManifest.xml` (+ `android:name=".CanonRemoteApp"`, intent-filter `android.hardware.usb.action.USB_DEVICE_ATTACHED` + meta-data `@xml/device_filter`), Create `app/src/main/res/xml/device_filter.xml` (`<usb-device vendor-id="1193" />`)

**Interfaces:**
- Consumes: `Transport`, `CanonCamera`, `LogBus`, `toControls()`.
- Produces:
  - `class UsbTransport(private val connection: UsbDeviceConnection, private val iface: UsbInterface, private val bulkIn: UsbEndpoint, private val bulkOut: UsbEndpoint) : Transport` — `companion fun open(manager: UsbManager, device: UsbDevice): UsbTransport?` (interface de classe 6 ou la première ayant 2 endpoints bulk ; `claimInterface(force=true)`). `send` : `bulkTransfer` en morceaux de 16 Ko, timeout 5000 ms. `receive` : tampon 1 Mo ; lire le premier transfert, longueur = u32 des 4 premiers octets, continuer jusqu'à la longueur annoncée ; transfert < 0 → `IOException("USB: transfert échoué")`. Tout sur `Dispatchers.IO`. `close` : `releaseInterface` + `close`.
  - `sealed interface UsbStatus { object NoDevice; data class Detected(val name: String); object PermissionDenied; data class Connected(val name: String) }`
  - `class UsbConnector(context: Context, log: LogBus) { val status: StateFlow<UsbStatus>; fun refresh(); fun requestPermission(onGranted: (UsbDevice) -> Unit) }` — filtre `vendorId == 0x04A9` ; `PendingIntent` `FLAG_MUTABLE` avec intent explicite (`setPackage`) ; récepteurs `ACTION_USB_DEVICE_ATTACHED/DETACHED` + action de permission (`RECEIVER_NOT_EXPORTED`).
  - `class CameraViewModel(app: Application) : AndroidViewModel(app), CameraActions { val controls: StateFlow<ControlsModel>; val frame: StateFlow<ByteArray?>; val usbStatus: StateFlow<UsbStatus>; val errors: Flow<String>; fun connect(); fun disconnect() }` — crée `CanonCamera(UsbTransport, log, viewModelScope)` à l'obtention de la permission ; détachement USB → `disconnect()` ; `lastError` non nul → émis dans `errors` puis `clearError()`.
  - `ConnectionScreen(status: UsbStatus, cameraModel: String?, serial: String?, onConnect, onDisconnect)` — carte « USB » (libellés : « Aucun appareil détecté. Branche le 90D en USB-C. », « Canon détecté : <nom> », « Autorisation USB refusée », « Connecté : <modèle> (n° <série>) ») + bouton « Connecter »/« Déconnecter » ; cartes « Wi-Fi », « Bluetooth », « Relais » désactivées avec « Bientôt ».
  - Snackbar dans `MainScreen` pour `errors`.

- [ ] **Step 1: Implémenter** les fichiers ci-dessus (logique testable déjà couverte par `:core`).
- [ ] **Step 2: Vérifier** `./gradlew -PskipApp=true test` PASS, puis push → CI verte.
- [ ] **Step 3: Commit** `feat(app): connexion USB et onglet Connexion`.

### Task 13: Onglet Logs + README

**Files:** Modify `ui/logs/LogsScreen.kt`, `MainActivity.kt` ; Modify `README.md`

**Interfaces:**
- Consumes: `LogBus.entries`, `LogBus.export(header)`, `LogBus.clear()`.
- Produces: `LogsScreen(entries: List<LogEntry>, onCopy: () -> Unit, onClear: () -> Unit)` — `LazyColumn` en police monospace, couleur par niveau (`ERROR` = `colorScheme.error`, `WARN` = `tertiary`, `PTP` = `onSurfaceVariant`), défilement auto vers le bas ; barre avec boutons « Copier les logs » et « Effacer ». Copie : `ClipboardManager.setPrimaryClip` avec `export("Canon Remote ${BuildConfig.VERSION_NAME} — ${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE}")` + snackbar « Logs copiés ». (`buildFeatures.buildConfig = true`.)
- README : but de l'app, état V1, comment télécharger l'APK (onglet Actions → dernier run → artefact `canon-remote-debug` → dézipper → installer, autoriser les sources inconnues), comment lancer les tests (`./gradlew -PskipApp=true test`), et comment envoyer des logs.

- [ ] **Step 1: Implémenter.** **Step 2:** push → CI verte, artefact présent.
- [ ] **Step 3: Commit** `feat(app): écran de logs copiables + README`.
