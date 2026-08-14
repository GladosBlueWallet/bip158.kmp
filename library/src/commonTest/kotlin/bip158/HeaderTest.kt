package bip158

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class HeaderTest {
    private val genesisFilterHex = "019dfca8"
    private val genesisPrevHeaderHex = "0".repeat(64)
    private val genesisHeaderDisplayHex =
        "21584579b7eb08997773e5aeff3a7f932700042d0ed2a6129012b7d7ae81b750"
    private val sha256dEmptyHex =
        "5df6e0e2761359d30a8275058e299fcc0381534545f55cf43e41983f5d4c9456"
    private val sha256dZeroHex =
        "1406e05881e299367766d313e26c05564ec91bf721d31726bd6e46e60689539a"
    private val concatHeaderHex =
        "4d096399fbebb96c1a900eba3e9f7e0303390fd75dae25c4a0542f911ccc4e4b"

    @Test
    fun filterHashMatchesIndependentSha256dOfSerializedFilterBytes() {
        val filterBytes = hexToBytes(genesisFilterHex)
        assertContentEquals(sha256d(filterBytes), filterHash(filterBytes))
        assertEquals(
            "4c8af7fa3ac4111dc5fd7581d176c02dbbfde83fd6f16496a576fbd6b20537c0",
            bytesToHex(filterHash(filterBytes)),
        )
    }

    @Test
    fun hashesEmptyAndNonEmptyPayloads() {
        val empty = filterHash(ByteArray(0))
        val one = filterHash(byteArrayOf(0x00))
        assertEquals(sha256dEmptyHex, bytesToHex(empty))
        assertEquals(sha256dZeroHex, bytesToHex(one))
        assertTrue(!empty.contentEquals(one))
    }

    @Test
    fun filterHeaderMatchesGenesisVectorFromAllZeroPreviousHeader() {
        val filterBytes = hexToBytes(genesisFilterHex)
        val prevHeader = hexToBytes(genesisPrevHeaderHex)
        val header = filterHeader(filterHash(filterBytes), prevHeader)
        assertEquals(genesisHeaderDisplayHex, bytesToHex(displayHashToInternal(header)))
    }

    @Test
    fun filterHeaderIsSha256dOfConcatenation() {
        val filterHashBytes = hexToBytes(
            "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20",
        )
        val prevHeader = hexToBytes(
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        )
        assertEquals(concatHeaderHex, bytesToHex(filterHeader(filterHashBytes, prevHeader)))
    }

    @Test
    fun rejectsWrongLengthHashAndHeader() {
        for (length in listOf(0, 8, 31, 33)) {
            val errorHash = assertFails {
                filterHeader(ByteArray(length), ByteArray(32))
            }
            assertEquals("filter hash must be 32 bytes, got $length", errorHash.message)
            val errorPrev = assertFails {
                filterHeader(ByteArray(32), ByteArray(length))
            }
            assertEquals("previous header must be 32 bytes, got $length", errorPrev.message)
        }
    }
}
