package dev.canonremote.core

import dev.canonremote.ptp.ContainerType
import dev.canonremote.ptp.PtpContainer
import dev.canonremote.ptp.PtpOp
import dev.canonremote.ptp.PtpResponse
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PtpException(val responseCode: Int, message: String) : Exception(message)

class PtpResult(val responseCode: Int, val params: List<Int>, val data: ByteArray?) {
    val isOk: Boolean get() = responseCode == PtpResponse.OK
}

class PtpSession(private val transport: Transport, private val log: LogBus) {
    private val mutex = Mutex()
    private var nextTid = 1

    /**
     * Runs one PTP transaction.
     * @param hexMax maximum number of payload bytes dumped in hex for DATA phases (0 = no hex).
     * @param logIf null logs every packet as it is sent/received; otherwise the transaction's
     *   packet lines are held back and logged only if [logIf] returns true for the result (or if
     *   the transaction fails with an exception). Used to keep idle polls out of the log.
     */
    suspend fun execute(
        op: Int,
        params: List<Int> = emptyList(),
        dataOut: ByteArray? = null,
        hexMax: Int = DEFAULT_HEX_MAX,
        logIf: ((PtpResult) -> Boolean)? = null,
    ): PtpResult = mutex.withLock {
        // Never abandon a transaction half-way: a stale response would desynchronize the next call.
        withContext(NonCancellable) {
            val held = if (logIf == null) null else mutableListOf<String>()
            fun ptp(msg: String) {
                if (held == null) log.ptp(msg) else held += msg
            }
            fun dataLine(dir: String, payload: ByteArray) =
                "$dir DATA ${payload.size} o" + if (hexMax > 0) ": ${payload.toHexDump(hexMax)}" else ""

            val result = try {
                val tid = if (op == PtpOp.OPEN_SESSION) 0 else nextTid++
                ptp("→ ${hex(op)} tid=$tid ${params.map { hex(it) }}")
                transport.send(PtpContainer(ContainerType.COMMAND, op, tid, params).encode())
                if (dataOut != null) {
                    ptp(dataLine("→", dataOut))
                    transport.send(PtpContainer(ContainerType.DATA, op, tid, payload = dataOut).encode())
                }
                var data: ByteArray? = null
                var response: PtpResult? = null
                while (response == null) {
                    val c = PtpContainer.decode(transport.receive())
                    if ((c.type == ContainerType.DATA || c.type == ContainerType.RESPONSE) && c.transactionId != tid) {
                        log.warn("Conteneur ${c.type} ${hex(c.code)} tid=${c.transactionId} ignoré (transaction $tid en cours)")
                        continue
                    }
                    when (c.type) {
                        ContainerType.EVENT -> ptp("← EVENT ${hex(c.code)} ${c.params.map { hex(it) }}")
                        ContainerType.DATA -> {
                            data = c.payload
                            ptp(dataLine("←", c.payload))
                        }
                        ContainerType.RESPONSE -> {
                            ptp("← ${hex(c.code)}")
                            response = PtpResult(c.code, c.params, data)
                        }
                        ContainerType.COMMAND -> log.warn("Conteneur COMMAND inattendu reçu, ignoré")
                    }
                }
                response
            } catch (e: Throwable) {
                held?.forEach(log::ptp)
                throw e
            }
            if (logIf != null && logIf(result)) held?.forEach(log::ptp)
            result
        }
    }

    suspend fun executeOk(
        op: Int,
        params: List<Int> = emptyList(),
        dataOut: ByteArray? = null,
        hexMax: Int = DEFAULT_HEX_MAX,
        logIf: ((PtpResult) -> Boolean)? = null,
    ): PtpResult {
        val r = execute(op, params, dataOut, hexMax, logIf)
        if (!r.isOk) throw PtpException(r.responseCode, "Réponse PTP ${hex(r.responseCode)} pour ${hex(op)}")
        return r
    }

    private fun hex(v: Int) = "0x%04X".format(v)

    companion object {
        const val DEFAULT_HEX_MAX = 64
    }
}
