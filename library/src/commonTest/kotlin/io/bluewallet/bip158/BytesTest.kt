package io.bluewallet.bip158

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class BytesTest {
    @Test
    fun hexToBytesParsesMixedCase() {
        assertContentEquals(
            hexToBytes("deadbeef"),
            hexToBytes("DeAdBeEf"),
        )
        assertContentEquals(
            byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()),
            hexToBytes("DeAdBeEf"),
        )
    }

    @Test
    fun hexToBytesRejectsNonHexAndOddLength() {
        assertFailsContaining("invalid hex string") { hexToBytes("gg") }
        val error = assertFails { hexToBytes("abc") }
        assertEquals("odd hex length: 3", error.message)
    }

    @Test
    fun bytesToHexRoundTripsAndLowercases() {
        val bytes = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        assertEquals("deadbeef", bytesToHex(bytes))
        assertContentEquals(bytes, hexToBytes(bytesToHex(bytes)))
    }

    @Test
    fun concatBytesConcatenatesAndLeavesInputsUnchanged() {
        val a = byteArrayOf(1, 2)
        val b = byteArrayOf(3)
        assertContentEquals(byteArrayOf(1, 2, 3), concatBytes(a, b))
        assertContentEquals(ByteArray(0), concatBytes())
        assertContentEquals(byteArrayOf(1, 2), a)
    }
}
