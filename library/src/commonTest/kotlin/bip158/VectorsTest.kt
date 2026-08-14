package bip158

import kotlin.test.Test
import kotlin.test.assertEquals

class VectorsTest {
    @Test
    fun loadsTenVectorRows() {
        assertEquals(10, loadTestnet19().size)
    }

    @Test
    fun officialTestnet19VectorsMatchFiltersAndHeaders() {
        for (row in loadTestnet19()) {
            val blockHash = hexToBytes(row.blockHashHex)
            val prevScripts = row.prevScriptsHex.map(::hexToBytes)
            val elements = basicFilterElements(
                BasicFilterBlockInput(
                    blockBytes = hexToBytes(row.blockHex),
                    prevOutputScripts = prevScripts,
                ),
            )
            val filterBytes = buildBasicFilter(blockHash, elements)
            assertEquals(row.basicFilterHex, bytesToHex(filterBytes), "height ${row.height} filter")
            val prevHeader = displayHashToInternal(hexToBytes(row.prevBasicHeaderHex))
            val header = filterHeader(filterHash(filterBytes), prevHeader)
            assertEquals(
                row.basicHeaderHex,
                bytesToHex(displayHashToInternal(header)),
                "height ${row.height} header",
            )
        }
    }
}
