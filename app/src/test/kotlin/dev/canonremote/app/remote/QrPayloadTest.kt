package dev.canonremote.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPayloadTest {
    @Test
    fun roundTripWithSpecialCharacters() {
        val p = QrPayload(
            ssid = "Mon réseau & co=1?#",
            password = "p@ss word+%/\"'",
            host = "192.168.49.1",
            port = 8765,
            code = "042137",
        )
        val uri = p.toUri()
        assertTrue(uri.startsWith("canonremote://connect?"))
        assertFalse(uri.contains(' '))
        assertEquals(p, QrPayload.parse(uri))
    }

    @Test
    fun ssidAndPasswordOmittedWhenNull() {
        val p = QrPayload(ssid = null, password = null, host = "10.0.0.5", port = 9000, code = "123456")
        val uri = p.toUri()
        assertFalse(uri.contains("ssid="))
        assertFalse(uri.contains("pass="))
        assertEquals("canonremote://connect?host=10.0.0.5&port=9000&code=123456", uri)
        assertEquals(p, QrPayload.parse(uri))
    }

    @Test
    fun exactFormat() {
        val p = QrPayload(ssid = "AP", password = "secret", host = "h", port = 1, code = "000000")
        assertEquals("canonremote://connect?ssid=AP&pass=secret&host=h&port=1&code=000000", p.toUri())
    }

    @Test
    fun parseToleratesSurroundingWhitespaceAndParamOrder() {
        val p = QrPayload.parse("  canonremote://connect?code=111111&port=8765&host=1.2.3.4\n")
        assertEquals(QrPayload(null, null, "1.2.3.4", 8765, "111111"), p)
    }

    @Test
    fun missingRequiredParamsGiveNull() {
        assertNull(QrPayload.parse("canonremote://connect?port=8765&code=123456"))
        assertNull(QrPayload.parse("canonremote://connect?host=h&code=123456"))
        assertNull(QrPayload.parse("canonremote://connect?host=h&port=8765"))
        assertNull(QrPayload.parse("canonremote://connect?host=&port=8765&code=123456"))
        assertNull(QrPayload.parse("canonremote://connect"))
    }

    @Test
    fun invalidPortGivesNull() {
        assertNull(QrPayload.parse("canonremote://connect?host=h&port=abc&code=123456"))
        assertNull(QrPayload.parse("canonremote://connect?host=h&port=0&code=123456"))
        assertNull(QrPayload.parse("canonremote://connect?host=h&port=70000&code=123456"))
    }

    @Test
    fun foreignTextGivesNull() {
        assertNull(QrPayload.parse("WIFI:T:WPA;S:MyNet;P:secret;;"))
        assertNull(QrPayload.parse("https://example.com/connect?host=h&port=1&code=123456"))
        assertNull(QrPayload.parse("canonremote://other?host=h&port=1&code=123456"))
        assertNull(QrPayload.parse(""))
        assertNull(QrPayload.parse("canonremote://connect?host=%ZZ&port=1&code=123456"))
    }
}
