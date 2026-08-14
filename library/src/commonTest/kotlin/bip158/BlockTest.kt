package bip158

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class BlockTest {
    private val genesisOutputScriptHex =
        "4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac"
    private val maxBlockBytes = 4_000_000

    @Test
    fun genesisBlockSingleCoinbaseTx() {
        val row = loadTestnet19().first { it.height == 0 }
        val txs = decodeBlockTransactions(hexToBytes(row.blockHex))
        assertEquals(1, txs.size)
        assertTrue(txs[0].isCoinbase)
        assertEquals(1, txs[0].inputs.size)
        assertEquals(1, txs[0].outputs.size)
        assertEquals(genesisOutputScriptHex, bytesToHex(txs[0].outputs[0].scriptPubKey))
    }

    @Test
    fun copiesDecodedScriptsSoMutatingTheBlockDoesNotAffectThem() {
        val expectedScriptSig = byteArrayOf(0x03, 0x01, 0x02)
        val expectedScriptPubKey = byteArrayOf(0x51, 0x52)
        val block = buildBlock(
            listOf(
                buildTx(
                    TxSpec(
                        inputs = listOf(coinbaseInput(scriptSig = expectedScriptSig)),
                        outputs = listOf(OutputSpec(expectedScriptPubKey)),
                    ),
                ),
            ),
        )
        val txs = decodeBlockTransactions(block)
        val scriptSig = txs[0].inputs[0].scriptSig
        val scriptPubKey = txs[0].outputs[0].scriptPubKey
        block.fill(0xff.toByte())

        assertContentEquals(expectedScriptSig, scriptSig)
        assertContentEquals(expectedScriptPubKey, scriptPubKey)
    }

    @Test
    fun multiTransactionBlockFlagsOnlyItsFirstTransactionAsCoinbase() {
        val row = loadTestnet19().first { it.height == 180_480 }
        val txs = decodeBlockTransactions(hexToBytes(row.blockHex))
        assertEquals(5, txs.size)
        assertEquals(listOf(true, false, false, false, false), txs.map { it.isCoinbase })
    }

    @Test
    fun witnessBlockDecodesWithoutErrorAndStaysAligned() {
        val row = loadTestnet19().first { it.height == 1_263_442 }
        val txs = decodeBlockTransactions(hexToBytes(row.blockHex))
        assertTrue(txs.isNotEmpty())
        assertTrue(txs[0].isCoinbase)
        val nonCoinbaseInputCount = txs.drop(1).sumOf { it.inputs.size }
        assertEquals(row.prevScriptsHex.size, nonCoinbaseInputCount)
    }

    @Test
    fun decodesEveryVectorBlockWithoutThrowing() {
        val rows = loadTestnet19()
        assertEquals(10, rows.size)
        for (row in rows) {
            decodeBlockTransactions(hexToBytes(row.blockHex))
        }
    }

    @Test
    fun rejectsEveryProperPrefixOfAStructurallyValidBlock() {
        val tx = buildLargeCoinbaseTx()
        assertTrue(tx.size > MIN_VALID_TRANSACTION_BYTES)
        val block = buildBlock(listOf(tx))
        decodeBlockTransactions(block)
        for (length in 0 until block.size) {
            assertFails { decodeBlockTransactions(block.copyOf(length)) }
        }
    }

    @Test
    fun rejectsANonCanonicalTransactionCount() {
        val coinbaseTx = buildTx(
            TxSpec(inputs = listOf(coinbaseInput()), outputs = listOf(EMPTY_OUTPUT)),
        )
        val block = concatBytes(
            ByteArray(BLOCK_HEADER_LENGTH),
            byteArrayOf(0xfd.toByte(), 0x01, 0x00),
            coinbaseTx,
        )
        assertFailsMatching(Regex("non-canonical CompactSize")) {
            decodeBlockTransactions(block)
        }
    }

    @Test
    fun rejectsTrailingBytesAfterTheFinalTransaction() {
        val block = buildBlock(
            listOf(buildTx(TxSpec(inputs = listOf(coinbaseInput()), outputs = listOf(EMPTY_OUTPUT)))),
        )
        assertFailsMatching(Regex("trailing block bytes")) {
            decodeBlockTransactions(concatBytes(block, byteArrayOf(0x00)))
        }
    }

    @Test
    fun rejectsAZeroTransactionBlock() {
        assertFailsMatching(Regex("block must contain at least one transaction")) {
            decodeBlockTransactions(buildBlock(emptyList()))
        }
    }

    @Test
    fun rejectsATransactionWithZeroInputs() {
        val tx = buildTx(
            TxSpec(
                inputs = emptyList(),
                outputs = listOf(EMPTY_OUTPUT),
                witnessStacks = emptyList(),
            ),
        )
        assertFailsMatching(Regex("transaction must contain at least one input")) {
            decodeBlockTransactions(buildBlock(listOf(padPastTransactionCountGuard(tx))))
        }
    }

    @Test
    fun rejectsATransactionWithZeroOutputs() {
        val tx = buildTx(TxSpec(inputs = listOf(coinbaseInput()), outputs = emptyList()))
        assertFailsMatching(Regex("transaction must contain at least one output")) {
            decodeBlockTransactions(buildBlock(listOf(padPastTransactionCountGuard(tx))))
        }
    }

    @Test
    fun rejectsAFirstTransactionThatIsNotStructurallyCoinbase() {
        val tx = buildTx(TxSpec(inputs = listOf(regularInput()), outputs = listOf(EMPTY_OUTPUT)))
        assertFailsMatching(Regex("first transaction must be coinbase")) {
            decodeBlockTransactions(buildBlock(listOf(tx)))
        }
    }

    @Test
    fun rejectsAnAdditionalCoinbaseTransaction() {
        val coinbaseTx = buildTx(
            TxSpec(inputs = listOf(coinbaseInput()), outputs = listOf(EMPTY_OUTPUT)),
        )
        assertFailsMatching(Regex("coinbase transaction must be first")) {
            decodeBlockTransactions(buildBlock(listOf(coinbaseTx, coinbaseTx)))
        }
    }

    @Test
    fun rejectsANullPrevoutInAMultiInputNonCoinbaseTransaction() {
        val coinbaseTx = buildTx(
            TxSpec(inputs = listOf(coinbaseInput()), outputs = listOf(EMPTY_OUTPUT)),
        )
        val malformedTx = buildTx(
            TxSpec(
                inputs = listOf(regularInput(), coinbaseInput()),
                outputs = listOf(EMPTY_OUTPUT),
            ),
        )
        assertFailsMatching(Regex("null prevout is only valid in a coinbase transaction")) {
            decodeBlockTransactions(buildBlock(listOf(coinbaseTx, malformedTx)))
        }
    }

    @Test
    fun rejectsAnUnknownWitnessFlag() {
        val tx = buildTx(
            TxSpec(
                inputs = listOf(coinbaseInput()),
                outputs = listOf(EMPTY_OUTPUT),
                witnessFlag = 0x02,
            ),
        )
        assertFailsMatching(Regex("unsupported segwit flag byte: 0x2")) {
            decodeBlockTransactions(buildBlock(listOf(tx)))
        }
    }

    @Test
    fun rejectsATruncatedWitnessFlag() {
        val truncatedTx = concatBytes(ByteArray(4), byteArrayOf(0x00))
        val block = buildBlock(listOf(buildLargeCoinbaseTx(), truncatedTx))
        assertFailsMatching(Regex("unexpected end of block data")) {
            decodeBlockTransactions(block)
        }
    }

    @Test
    fun rejectsWitnessSerializationWhenEveryInputStackIsEmpty() {
        val tx = buildTx(
            TxSpec(
                inputs = listOf(coinbaseInput()),
                outputs = listOf(EMPTY_OUTPUT),
                witnessStacks = listOf(emptyList()),
            ),
        )
        assertFailsMatching(Regex("superfluous witness serialization")) {
            decodeBlockTransactions(buildBlock(listOf(tx)))
        }
    }

    @Test
    fun acceptsAWitnessStackContainingOneEmptyItem() {
        val tx = buildTx(
            TxSpec(
                inputs = listOf(coinbaseInput()),
                outputs = listOf(EMPTY_OUTPUT),
                witnessStacks = listOf(listOf(ByteArray(0))),
            ),
        )
        val decoded = decodeBlockTransactions(buildBlock(listOf(tx)))
        assertEquals(1, decoded.size)
        assertTrue(decoded[0].isCoinbase)
    }

    @Test
    fun rejectsABlockLargerThanFourMillionBytes() {
        assertFailsMatching(Regex("block exceeds 4,000,000 bytes")) {
            decodeBlockTransactions(ByteArray(maxBlockBytes + 1))
        }
    }

    @Test
    fun rejectsAnImpossibleTransactionCountBeforeItsLoop() {
        val block = concatBytes(
            ByteArray(BLOCK_HEADER_LENGTH),
            encodeCompactSize(MAX_SAFE_INTEGER),
        )
        assertFailsMatching(Regex("transaction count .* cannot fit in .* remaining bytes")) {
            decodeBlockTransactions(block)
        }
    }

    @Test
    fun rejectsAnImpossibleInputCountBeforeItsLoop() {
        val tx = padPastTransactionCountGuard(
            concatBytes(ByteArray(4), encodeCompactSize(MAX_SAFE_INTEGER)),
        )
        assertFailsMatching(Regex("input count .* cannot fit in .* remaining bytes")) {
            decodeBlockTransactions(buildBlock(listOf(tx)))
        }
    }

    @Test
    fun rejectsAnImpossibleOutputCountBeforeItsLoop() {
        val tx = padPastTransactionCountGuard(
            concatBytes(
                ByteArray(4),
                encodeCompactSize(1),
                serializeInput(coinbaseInput()),
                encodeCompactSize(MAX_SAFE_INTEGER),
            ),
        )
        assertFailsMatching(Regex("output count .* cannot fit in .* remaining bytes")) {
            decodeBlockTransactions(buildBlock(listOf(tx)))
        }
    }

    @Test
    fun rejectsAnImpossibleWitnessItemCountBeforeItsLoop() {
        val block = concatBytes(
            ByteArray(BLOCK_HEADER_LENGTH),
            encodeCompactSize(1),
            ByteArray(4),
            byteArrayOf(0x00, 0x01),
            encodeCompactSize(1),
            serializeInput(coinbaseInput()),
            encodeCompactSize(1),
            serializeOutput(EMPTY_OUTPUT),
            encodeCompactSize(MAX_SAFE_INTEGER),
        )
        assertFailsMatching(Regex("witness item count .* cannot fit in .* remaining bytes")) {
            decodeBlockTransactions(block)
        }
    }

    @Test
    fun rejectsAVarBytesLengthThatWouldWrapThroughToInt() {
        val tx = padPastTransactionCountGuard(
            concatBytes(
                ByteArray(4),
                encodeCompactSize(1),
                ByteArray(32),
                byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()),
                encodeCompactSize(0x1_0000_0000uL),
            ),
        )
        assertFailsMatching(Regex("unexpected end of block data")) {
            decodeBlockTransactions(buildBlock(listOf(tx)))
        }
    }

    @Test
    fun rejectsAWitnessItemLengthThatWouldWrapThroughToInt() {
        val block = concatBytes(
            ByteArray(BLOCK_HEADER_LENGTH),
            encodeCompactSize(1),
            ByteArray(4),
            byteArrayOf(0x00, 0x01),
            encodeCompactSize(1),
            serializeInput(coinbaseInput()),
            encodeCompactSize(1),
            serializeOutput(EMPTY_OUTPUT),
            encodeCompactSize(1),
            encodeCompactSize(0x1_0000_0000uL),
        )
        assertFailsMatching(Regex("unexpected end of block data")) {
            decodeBlockTransactions(block)
        }
    }
}
