package dev.canonremote.ptp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveViewTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** One live view block: size u32 (header included), type u32, payload. */
    private fun block(type: Int, payload: ByteArray): ByteArray =
        ByteWriter().apply {
            u32(8 + payload.size)
            u32(type)
            bytes(payload)
        }.toByteArray()

    @Test
    fun extractsJpegBlock() {
        val jpeg = bytes(0xFF, 0xD8, 0x01, 0x02, 0xFF, 0xD9)
        val data = block(1, jpeg) + block(4, bytes(0xAA, 0xBB, 0xCC, 0xDD))

        assertArrayEquals(jpeg, extractLiveViewJpeg(data))
    }

    @Test
    fun skipsType1BlockWithoutJpegStart() {
        val jpeg = bytes(0xFF, 0xD8, 0xAA, 0xFF, 0xD9)
        val data = block(1, bytes(0x00, 0x01, 0x02)) + block(1, jpeg)

        assertArrayEquals(jpeg, extractLiveViewJpeg(data))
    }

    @Test
    fun fallsBackToMarkerScan() {
        val jpeg = bytes(0xFF, 0xD8, 0xAA, 0xFF, 0xD9)
        // Leading bytes declare a block size far past the end of the data: structure invalid.
        val data = bytes(0x52, 0x13, 0x77, 0x01, 0x9A, 0x3C) + jpeg + bytes(0x10, 0x20, 0x30)

        assertArrayEquals(jpeg, extractLiveViewJpeg(data))
    }

    @Test
    fun returnsNullWithoutJpeg() {
        assertNull(extractLiveViewJpeg(ByteArray(0)))
        assertNull(extractLiveViewJpeg(ByteArray(32)))
    }
}
