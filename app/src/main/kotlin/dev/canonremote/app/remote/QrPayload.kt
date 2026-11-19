package dev.canonremote.app.remote

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Content of the QR code shown by the relay: optional hotspot credentials, the relay's address
 * and the pairing code. Serialized as
 * `canonremote://connect?ssid=..&pass=..&host=..&port=..&code=..` (URL-encoded values, `ssid` and
 * `pass` omitted when null).
 */
data class QrPayload(
    val ssid: String?,
    val password: String?,
    val host: String,
    val port: Int,
    val code: String,
) {
    fun toUri(): String {
        val params = buildList {
            ssid?.let { add("ssid" to it) }
            password?.let { add("pass" to it) }
            add("host" to host)
            add("port" to port.toString())
            add("code" to code)
        }
        return PREFIX + params.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
    }

    companion object {
        private const val PREFIX = "canonremote://connect?"

        /** Returns null for any text that is not a valid relay QR payload. */
        fun parse(text: String): QrPayload? {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX)) return null
            val params = HashMap<String, String>()
            for (part in trimmed.substring(PREFIX.length).split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq <= 0) return null
                val value = decode(part.substring(eq + 1)) ?: return null
                params[part.substring(0, eq)] = value
            }
            val host = params["host"]?.takeIf { it.isNotBlank() } ?: return null
            val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val code = params["code"]?.takeIf { it.isNotBlank() } ?: return null
            return QrPayload(
                ssid = params["ssid"],
                password = params["pass"],
                host = host,
                port = port,
                code = code,
            )
        }

        private fun encode(v: String): String =
            URLEncoder.encode(v, Charsets.UTF_8.name()).replace("+", "%20")

        private fun decode(v: String): String? =
            try {
                URLDecoder.decode(v, Charsets.UTF_8.name())
            } catch (e: IllegalArgumentException) {
                null
            }
    }
}
