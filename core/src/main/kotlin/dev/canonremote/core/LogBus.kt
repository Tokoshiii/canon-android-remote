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

    fun export(header: String): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS") // default time zone
        return buildString {
            append(header).append('\n').append('\n')
            _entries.value.forEach {
                append(fmt.format(Date(it.timeMillis))).append(' ')
                    .append(it.level.name).append(' ').append(it.message).append('\n')
            }
        }
    }

    private fun add(level: LogLevel, msg: String) {
        val entry = LogEntry(clock(), level, msg)
        _entries.update { (it + entry).takeLast(capacity) }
    }
}

fun ByteArray.toHexDump(max: Int = 64): String {
    val shown = take(max).joinToString(" ") { "%02x".format(it) }
    return if (size > max) "$shown … (+${size - max} octets)" else shown
}
