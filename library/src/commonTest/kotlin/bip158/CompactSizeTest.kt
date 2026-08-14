package bip158

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class CompactSizeTest {
    @Test
    fun encodesPrefixBoundariesCanonically() {
        val cases = listOf(
            0uL to "00",
            0xfcuL to "fc",
            0xfduL to "fdfd00",
            0xffffuL to "fdffff",
            0x1_0000uL to "fe00000100",
            0xffff_ffffuL to "feffffffff",
            0x1_0000_0000uL to "ff0000000001000000",
            0xffff_ffff_ffff_ffffuL to "ffffffffffffffffff",
        )
        for ((value, expected) in cases) {
            assertEquals(expected, bytesToHex(encodeCompactSize(value)), "value $value")
        }
    }

    @Test
    fun acceptsSafeIntegerNumberInputs() {
        val encoded = encodeCompactSize(MAX_SAFE_INTEGER)
        assertEquals(MAX_SAFE_INTEGER, decodeCompactSize(encoded).value)
    }

    @Test
    fun rejectsNegativeLongInput() {
        assertFails { encodeCompactSize(-1L) }
        assertFails { encodeCompactSize(-1) }
    }

    @Test
    fun decodeCompactSizeULongReadsEveryCanonicalPrefixBoundary() {
        val cases = listOf(
            byteArrayOf(0xfc.toByte()) to Pair(0xfcuL, 1),
            byteArrayOf(0xfd.toByte(), 0xfd.toByte(), 0x00) to Pair(0xfduL, 3),
            byteArrayOf(0xfd.toByte(), 0xff.toByte(), 0xff.toByte()) to Pair(0xffffuL, 3),
            byteArrayOf(0xfe.toByte(), 0x00, 0x00, 0x01, 0x00) to Pair(0x1_0000uL, 5),
            byteArrayOf(
                0xfe.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            ) to Pair(0xffff_ffffuL, 5),
            byteArrayOf(
                0xff.toByte(), 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
            ) to Pair(0x1_0000_0000uL, 9),
            byteArrayOf(
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            ) to Pair(0xffff_ffff_ffff_ffffuL, 9),
        )
        for ((bytes, expected) in cases) {
            val decoded = decodeCompactSizeULong(bytes)
            assertEquals(expected.first, decoded.value)
            assertEquals(expected.second, decoded.length)
        }
    }

    @Test
    fun rejectsNoncanonicalEncoding() {
        val cases = listOf(
            byteArrayOf(0xfd.toByte(), 0xfc.toByte(), 0x00),
            byteArrayOf(0xfd.toByte(), 0x01, 0x00),
            byteArrayOf(0xfe.toByte(), 0xff.toByte(), 0xff.toByte(), 0x00, 0x00),
            byteArrayOf(
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
                0xff.toByte(), 0x00, 0x00, 0x00, 0x00,
            ),
        )
        for (bytes in cases) {
            assertFailsMatching(Regex("canonical", RegexOption.IGNORE_CASE)) {
                decodeCompactSizeULong(bytes)
            }
            assertFailsMatching(Regex("canonical", RegexOption.IGNORE_CASE)) {
                decodeCompactSize(bytes)
            }
        }
    }

    @Test
    fun rejectsTruncatedInput() {
        val cases = listOf(
            ByteArray(0),
            byteArrayOf(0xfd.toByte()),
            byteArrayOf(0xfd.toByte(), 0xfd.toByte()),
            byteArrayOf(0xfe.toByte(), 0x00, 0x00, 0x01),
            byteArrayOf(0xff.toByte(), 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00),
        )
        for (bytes in cases) {
            assertFailsMatching(Regex("end", RegexOption.IGNORE_CASE)) {
                decodeCompactSizeULong(bytes)
            }
        }
    }

    @Test
    fun supportsAValidNonzeroOffset() {
        val bytes = byteArrayOf(0xaa.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0x00, 0xbb.toByte())
        val decoded = decodeCompactSizeULong(bytes, 1)
        assertEquals(0xfduL, decoded.value)
        assertEquals(3, decoded.length)
    }

    @Test
    fun rejectsInvalidOffset() {
        assertFailsMatching(Regex("offset", RegexOption.IGNORE_CASE)) {
            decodeCompactSizeULong(byteArrayOf(0x00), -1)
        }
        assertFailsMatching(Regex("offset", RegexOption.IGNORE_CASE)) {
            decodeCompactSize(byteArrayOf(0x00), -1)
        }
    }

    @Test
    fun decodeCompactSizeRetainsExactNumberResultsThroughMaxSafeInteger() {
        val encoded = encodeCompactSize(MAX_SAFE_INTEGER)
        val decoded = decodeCompactSize(encoded)
        assertEquals(MAX_SAFE_INTEGER, decoded.value)
        assertEquals(9, decoded.length)
    }

    @Test
    fun rejectsDecodedValuesAboveMaxSafeInteger() {
        val encoded = encodeCompactSize(MAX_SAFE_INTEGER.toULong() + 1uL)
        assertFailsMatching(Regex("safe integer", RegexOption.IGNORE_CASE)) {
            decodeCompactSize(encoded)
        }
        assertEquals(
            MAX_SAFE_INTEGER.toULong() + 1uL,
            decodeCompactSizeULong(encoded).value,
        )
    }

    @Test
    fun publicApiRoundTripsFromPackageRoot() {
        val decoded = decodeCompactSize(encodeCompactSize(0x100))
        assertEquals(0x100L, decoded.value)
        assertEquals(3, decoded.length)
    }
}
