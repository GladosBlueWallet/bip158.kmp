package bip158

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class FilterTest {
    private val genesisBlockHashDisplayHex =
        "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943"
    private val genesisOutputScriptHex =
        "4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac"
    private val genesisFilterHex = "019dfca8"

    @Test
    fun displayHashToInternalReversesByteOrder() {
        val display = hexToBytes(
            "0000000000000000000000000000000000000000000000000000000000000001",
        )
        assertEquals(
            "0100000000000000000000000000000000000000000000000000000000000000",
            bytesToHex(displayHashToInternal(display)),
        )
    }

    @Test
    fun displayHashToInternalRejectsNon32ByteHashes() {
        assertFails { displayHashToInternal(hexToBytes("0011223344")) }
    }

    @Test
    fun displayHashToInternalRoundTripsUnderDoubleReversal() {
        val display = hexToBytes(genesisBlockHashDisplayHex)
        assertContentEquals(display, displayHashToInternal(displayHashToInternal(display)))
    }

    @Test
    fun buildBasicFilterMatchesTheOfficialGenesisFilterVector() {
        val blockHashDisplay = hexToBytes(genesisBlockHashDisplayHex)
        val elements = listOf(hexToBytes(genesisOutputScriptHex))
        val filterBytes = buildBasicFilter(blockHashDisplay, elements)
        assertEquals(genesisFilterHex, bytesToHex(filterBytes))
    }

    @Test
    fun omitsAZeroLengthElement() {
        val blockHashDisplay = hexToBytes(genesisBlockHashDisplayHex)
        val script = hexToBytes(genesisOutputScriptHex)
        val withEmpty = buildBasicFilter(
            blockHashDisplay,
            listOf(script, ByteArray(0)),
        )
        val withoutEmpty = buildBasicFilter(blockHashDisplay, listOf(script))
        assertContentEquals(withoutEmpty, withEmpty)
    }

    @Test
    fun omitsZeroLengthElementsWhileRetainingGcsDeduplication() {
        val blockHashDisplay = ByteArray(32) { 7 }
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6)
        val uniqueNonempty = buildBasicFilter(blockHashDisplay, listOf(first, second))
        val mixed = buildBasicFilter(
            blockHashDisplay,
            listOf(first, ByteArray(0), first, ByteArray(0), second),
        )
        assertContentEquals(uniqueNonempty, mixed)
    }

    @Test
    fun matchAnyBasicFiltersMatchesGenesisAndWatchlistCases() {
        val blockHashDisplay = hexToBytes(genesisBlockHashDisplayHex)
        val script = hexToBytes(genesisOutputScriptHex)
        val filterBytes = buildBasicFilter(blockHashDisplay, listOf(script))

        val hashA = ByteArray(32) { 0x11 }
        val hashB = ByteArray(32) { 0x22 }
        val hit = byteArrayOf(0x00, 0x14) + ByteArray(20) { 9 }
        val miss = byteArrayOf(0x00, 0x14) + ByteArray(20) { 1 }
        val filterHit = buildBasicFilter(hashA, listOf(hit))
        val filterMiss = buildBasicFilter(hashB, listOf(miss))

        assertEquals(genesisFilterHex, bytesToHex(filterBytes))
        assertEquals(
            listOf(true),
            matchAnyBasicFilters(listOf(filterBytes), listOf(blockHashDisplay), listOf(script)),
        )
        val absent = "not-in-the-genesis-filter".encodeToByteArray()
        assertEquals(
            listOf(false),
            matchAnyBasicFilters(listOf(filterBytes), listOf(blockHashDisplay), listOf(absent)),
        )
        val wrongHash = ByteArray(32) { 0xaa.toByte() }
        assertEquals(
            listOf(false),
            matchAnyBasicFilters(listOf(filterBytes), listOf(wrongHash), listOf(script)),
        )
        assertEquals(
            listOf(true, false),
            matchAnyBasicFilters(listOf(filterHit, filterMiss), listOf(hashA, hashB), listOf(hit)),
        )
        assertEquals(
            listOf(true),
            matchAnyBasicFilters(listOf(filterHit), listOf(hashA), listOf(miss, hit)),
        )
        assertEquals(
            listOf(false),
            matchAnyBasicFilters(listOf(filterHit), listOf(hashA), listOf(miss)),
        )
        assertEquals(
            listOf(false, false),
            matchAnyBasicFilters(listOf(filterHit, filterMiss), listOf(hashA, hashB), emptyList()),
        )
        assertFailsMatching(Regex("filter/hash length mismatch")) {
            matchAnyBasicFilters(listOf(filterHit), listOf(hashA, hashB), listOf(hit))
        }
        assertFailsMatching(Regex("display hash must be 32 bytes")) {
            matchAnyBasicFilters(listOf(filterHit), listOf(ByteArray(16)), listOf(hit))
        }
    }
}
