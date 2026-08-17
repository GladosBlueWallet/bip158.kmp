package io.bluewallet.bip158

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

internal fun key16(seed: Int): ByteArray =
    ByteArray(16) { ((seed + it) and 0xff).toByte() }

internal fun item(label: String): ByteArray = label.encodeToByteArray()

internal fun seqBytes(n: Int): ByteArray = ByteArray(n) { it.toByte() }

internal fun assertFailsMatching(pattern: Regex, block: () -> Unit) {
    val error = assertFails(block)
    val message = error.message ?: ""
    assertTrue(
        pattern.containsMatchIn(message),
        "expected message matching $pattern, got '$message'",
    )
}

internal fun assertFailsContaining(text: String, block: () -> Unit) {
    val error = assertFails(block)
    val message = error.message ?: ""
    assertContains(message, text)
}

internal fun assertByteListsEqual(expected: List<ByteArray>, actual: List<ByteArray>) {
    assertEquals(expected.size, actual.size, "byte list size")
    for (i in expected.indices) {
        kotlin.test.assertContentEquals(expected[i], actual[i], "index $i")
    }
}

internal fun u32LE(value: Int): ByteArray = byteArrayOf(
    (value and 0xff).toByte(),
    ((value ushr 8) and 0xff).toByte(),
    ((value ushr 16) and 0xff).toByte(),
    ((value ushr 24) and 0xff).toByte(),
)

internal fun varBytes(bytes: ByteArray): ByteArray =
    concatBytes(encodeCompactSize(bytes.size), bytes)

internal const val BLOCK_HEADER_LENGTH = 80
internal const val MIN_VALID_TRANSACTION_BYTES = 60

internal fun buildBlock(txs: List<ByteArray>): ByteArray = concatBytes(
    ByteArray(BLOCK_HEADER_LENGTH),
    encodeCompactSize(txs.size),
    *txs.toTypedArray(),
)

internal data class InputSpec(
    val previousTxid: ByteArray,
    val previousOutputIndex: Int,
    val scriptSig: ByteArray = ByteArray(0),
)

internal data class OutputSpec(
    val scriptPubKey: ByteArray,
)

internal data class TxSpec(
    val inputs: List<InputSpec>,
    val outputs: List<OutputSpec>,
    val witnessStacks: List<List<ByteArray>>? = null,
    val witnessFlag: Int? = null,
)

internal fun coinbaseInput(scriptSig: ByteArray = ByteArray(0)): InputSpec = InputSpec(
    previousTxid = ByteArray(32),
    previousOutputIndex = -1, // 0xffffffff
    scriptSig = scriptSig,
)

internal fun regularInput(): InputSpec {
    val previousTxid = ByteArray(32)
    previousTxid[0] = 1
    return InputSpec(previousTxid = previousTxid, previousOutputIndex = 0)
}

internal fun serializeInput(input: InputSpec): ByteArray = concatBytes(
    input.previousTxid,
    u32LE(input.previousOutputIndex),
    varBytes(input.scriptSig),
    ByteArray(4),
)

internal fun serializeOutput(output: OutputSpec): ByteArray =
    concatBytes(ByteArray(8), varBytes(output.scriptPubKey))

internal fun buildTx(spec: TxSpec): ByteArray {
    val hasWitnessEncoding = spec.witnessStacks != null || spec.witnessFlag != null
    val witnessStacks = spec.witnessStacks
        ?: spec.inputs.map { emptyList<ByteArray>() }
    require(witnessStacks.size == spec.inputs.size) {
        "test fixture must provide one witness stack per input"
    }
    val witnessParts = witnessStacks.map { stack ->
        concatBytes(
            encodeCompactSize(stack.size),
            *stack.map { varBytes(it) }.toTypedArray(),
        )
    }
    val parts = ArrayList<ByteArray>()
    parts.add(ByteArray(4))
    if (hasWitnessEncoding) {
        parts.add(byteArrayOf(0x00, (spec.witnessFlag ?: 0x01).toByte()))
    }
    parts.add(encodeCompactSize(spec.inputs.size))
    for (input in spec.inputs) parts.add(serializeInput(input))
    parts.add(encodeCompactSize(spec.outputs.size))
    for (output in spec.outputs) parts.add(serializeOutput(output))
    if (hasWitnessEncoding) parts.addAll(witnessParts)
    parts.add(ByteArray(4))
    return concatBytes(*parts.toTypedArray())
}

internal fun padPastTransactionCountGuard(tx: ByteArray): ByteArray {
    if (tx.size >= MIN_VALID_TRANSACTION_BYTES) return tx
    return concatBytes(tx, ByteArray(MIN_VALID_TRANSACTION_BYTES - tx.size))
}

internal val EMPTY_OUTPUT = OutputSpec(scriptPubKey = ByteArray(0))

internal fun buildLargeCoinbaseTx(): ByteArray = buildTx(
    TxSpec(
        inputs = listOf(coinbaseInput(scriptSig = ByteArray(64))),
        outputs = listOf(EMPTY_OUTPUT),
    ),
)
