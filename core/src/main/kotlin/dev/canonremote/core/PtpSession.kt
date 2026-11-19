package dev.canonremote.core

import dev.canonremote.ptp.ContainerType
import dev.canonremote.ptp.PtpContainer
import dev.canonremote.ptp.PtpOp
import dev.canonremote.ptp.PtpResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PtpException(val responseCode: Int, message: String) : Exception(message)

class PtpResult(val responseCode: Int, val params: List<Int>, val data: ByteArray?) {
    val isOk: Boolean get() = responseCode == PtpResponse.OK
}

class PtpSession(private val transport: Transport, private val log: LogBus) {
    private val mutex = Mutex()
    private var nextTid = 1

    suspend fun execute(
        op: Int,
        params: List<Int> = emptyList(),
        dataOut: ByteArray? = null,
        logData: Boolean = true,
    ): PtpResult = mutex.withLock {
        val tid = if (op == PtpOp.OPEN_SESSION) 0 else nextTid++
        log.ptp("→ ${hex(op)} tid=$tid ${params.map { hex(it) }}")
        transport.send(PtpContainer(ContainerType.COMMAND, op, tid, params).encode())
        if (dataOut != null) {
            log.ptp("→ DATA ${dataOut.size} o" + if (logData) ": ${dataOut.toHexDump()}" else "")
            transport.send(PtpContainer(ContainerType.DATA, op, tid, payload = dataOut).encode())
        }
        var data: ByteArray? = null
        var result: PtpResult? = null
        while (result == null) {
            val c = PtpContainer.decode(transport.receive())
            when (c.type) {
                ContainerType.EVENT -> log.ptp("← EVENT ${hex(c.code)} ${c.params.map { hex(it) }}")
                ContainerType.DATA -> {
                    data = c.payload
                    log.ptp("← DATA ${c.payload.size} o" + if (logData) ": ${c.payload.toHexDump()}" else "")
                }
                ContainerType.RESPONSE -> {
                    log.ptp("← ${hex(c.code)}")
                    result = PtpResult(c.code, c.params, data)
                }
                ContainerType.COMMAND -> log.warn("Conteneur COMMAND inattendu reçu, ignoré")
            }
        }
        result
    }

    suspend fun executeOk(
        op: Int,
        params: List<Int> = emptyList(),
        dataOut: ByteArray? = null,
        logData: Boolean = true,
    ): PtpResult {
        val r = execute(op, params, dataOut, logData)
        if (!r.isOk) throw PtpException(r.responseCode, "Réponse PTP ${hex(r.responseCode)} pour ${hex(op)}")
        return r
    }

    private fun hex(v: Int) = "0x%04X".format(v)
}
