package bip158

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ElementsTest {
    private val genesisOutputScriptHex =
        "4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac"

    private val opTrue = hexToBytes("51")
    private val normalScriptA =
        hexToBytes("76a914aabbccddeeff00112233445566778899aabbccdd88ac")
    private val normalScriptB =
        hexToBytes("76a914000102030405060708090a0b0c0d0e0f1011121388ac")
    private val opReturnScript = hexToBytes("6a0648656c6c6f21")
    private val emptyScript = ByteArray(0)

    private fun simpleCoinbaseTx(scriptPubKey: ByteArray = opTrue): ByteArray = buildTx(
        TxSpec(
            inputs = listOf(coinbaseInput()),
            outputs = listOf(OutputSpec(scriptPubKey)),
        ),
    )

    @Test
    fun genesisBlockYieldsExactlyTheSingleCoinbaseOutputScript() {
        val row = loadTestnet19().first { it.height == 0 }
        val elements = basicFilterElements(
            BasicFilterBlockInput(
                blockBytes = hexToBytes(row.blockHex),
                prevOutputScripts = row.prevScriptsHex.map(::hexToBytes),
            ),
        )
        assertEquals(1, elements.size)
        assertEquals(genesisOutputScriptHex, bytesToHex(elements[0]))
    }

    @Test
    fun skipsCoinbaseInputs() {
        val elements = basicFilterElements(
            BasicFilterBlockInput(
                blockBytes = buildBlock(listOf(simpleCoinbaseTx())),
                prevOutputScripts = emptyList(),
            ),
        )
        assertByteListsEqual(listOf(opTrue), elements)
    }

    @Test
    fun excludesOpReturnAndEmptyScriptsAndIncludesPrevScripts() {
        val coinbaseTx = simpleCoinbaseTx()
        val normalTx = buildTx(
            TxSpec(
                inputs = listOf(regularInput()),
                outputs = listOf(
                    OutputSpec(opReturnScript),
                    OutputSpec(normalScriptB),
                    OutputSpec(emptyScript),
                ),
            ),
        )
        val elements = basicFilterElements(
            BasicFilterBlockInput(
                blockBytes = buildBlock(listOf(coinbaseTx, normalTx)),
                prevOutputScripts = listOf(normalScriptA),
            ),
        )
        assertByteListsEqual(listOf(opTrue, normalScriptA, normalScriptB), elements)
    }

    @Test
    fun copiesPrevScriptsInElementOrder() {
        val plainPrevScript = byteArrayOf(0x51, 0x01)
        val secondPrevScript = byteArrayOf(0x52, 0x02)
        val expectedPlain = plainPrevScript.copyOf()
        val expectedSecond = secondPrevScript.copyOf()
        val coinbaseTx = simpleCoinbaseTx()
        val normalTx = buildTx(
            TxSpec(
                inputs = listOf(regularInput(), regularInput()),
                outputs = listOf(OutputSpec(opReturnScript)),
            ),
        )
        val elements = basicFilterElements(
            BasicFilterBlockInput(
                blockBytes = buildBlock(listOf(coinbaseTx, normalTx)),
                prevOutputScripts = listOf(plainPrevScript, secondPrevScript),
            ),
        )
        plainPrevScript.fill(0xff.toByte())
        secondPrevScript.fill(0xff.toByte())

        assertByteListsEqual(listOf(opTrue, expectedPlain, expectedSecond), elements)
        assertTrue(!elements[1].contentEquals(plainPrevScript))
        assertTrue(!elements[2].contentEquals(secondPrevScript))
    }

    @Test
    fun omitsAnEmptyPrevOutputScript() {
        val coinbaseTx = simpleCoinbaseTx()
        val normalTx = buildTx(
            TxSpec(
                inputs = listOf(regularInput()),
                outputs = listOf(OutputSpec(normalScriptB)),
            ),
        )
        val elements = basicFilterElements(
            BasicFilterBlockInput(
                blockBytes = buildBlock(listOf(coinbaseTx, normalTx)),
                prevOutputScripts = listOf(emptyScript),
            ),
        )
        assertByteListsEqual(listOf(opTrue, normalScriptB), elements)
    }

    @Test
    fun throwsWhenPrevOutputScriptsCountDoesNotMatch() {
        val coinbaseTx = simpleCoinbaseTx()
        val normalTx = buildTx(
            TxSpec(
                inputs = listOf(regularInput()),
                outputs = listOf(OutputSpec(normalScriptB)),
            ),
        )
        val block = buildBlock(listOf(coinbaseTx, normalTx))
        assertFails {
            basicFilterElements(BasicFilterBlockInput(block, emptyList()))
        }
        assertFails {
            basicFilterElements(
                BasicFilterBlockInput(block, listOf(normalScriptA, normalScriptA)),
            )
        }
    }

    @Test
    fun witnessBlockExtractsElementsThatRebuildTheOfficialFilter() {
        val row = loadTestnet19().first { it.height == 1_263_442 }
        val prevScripts = row.prevScriptsHex.map(::hexToBytes)
        val elements = basicFilterElements(
            BasicFilterBlockInput(
                blockBytes = hexToBytes(row.blockHex),
                prevOutputScripts = prevScripts,
            ),
        )
        assertEquals(1, prevScripts.size)
        assertEquals(3, elements.size)
        assertTrue(elements.any { it.contentEquals(prevScripts[0]) })

        val filterBytes = buildBasicFilter(hexToBytes(row.blockHashHex), elements)
        assertEquals(row.basicFilterHex, bytesToHex(filterBytes))
    }
}
