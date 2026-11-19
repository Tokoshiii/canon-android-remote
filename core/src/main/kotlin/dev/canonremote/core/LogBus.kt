package dev.canonremote.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date

enum class LogLevel { INFO, WARN, ERROR, PTP }

data class LogEntry(val timeMillis: Long, val level: LogLevel, val message: String)

class LogBus(
    private val capacity: Int = 5000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun info(msg: String) = add(LogLevel.INFO, msg)
    fun warn(msg: String) = add(LogLevel.WARN, msg)
    fun error(msg: String, t: Throwable? = null) =
        add(LogLevel.ERROR, if (t == null) msg else "$msg: ${t::class.simpleName}: ${t.message}")
    fun ptp(msg: String) = add(LogLevel.PTP, msg)

    fun clear() {
        _entries.value = emptyList()
    }

    /**
     * Formats the log for copying. If the result would exceed [maxChars], keeps the header and
     * the most recent lines that fit, after a « … (début tronqué) » line.
     */
    fun export(header: String, maxChars: Int = Int.MAX_VALUE): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS") // default time zone
        val lines = _entries.value.map { "${fmt.format(Date(it.timeMillis))} ${it.level.name} ${it.message}\n" }
        val head = "$header\n\n"
        if (head.length + lines.sumOf { it.length } <= maxChars) {
            return head + lines.joinToString("")
        }
        var budget = maxChars - head.length - TRUNCATED_MARKER.length
        val kept = ArrayDeque<String>()
        for (line in lines.asReversed()) {
            if (line.length > budget) break
            budget -= line.length
            kept.addFirst(line)
        }
        return head + TRUNCATED_MARKER + kept.joinToString("")
    }

    private fun add(level: LogLevel, msg: String) {
        val entry = LogEntry(clock(), level, msg)
        _entries.update { (it + entry).takeLast(capacity) }
    }
}

private const val TRUNCATED_MARKER = "… (début tronqué)\n"

fun ByteArray.toHexDump(max: Int = 64): String {
    val shown = take(max).joinToString(" ") { "%02x".format(it) }
    return if (size > max) "$shown … (+${size - max} octets)" else shown
}
