package bip158

/**
 * Minimal Bitcoin block wire decoder — just enough structure (coinbase
 * detection, input count, output scripts) to feed BIP-158 basic filter
 * element extraction.
 */

data class DecodedTxInput(
    val scriptSig: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is DecodedTxInput && scriptSig.contentEquals(other.scriptSig)

    override fun hashCode(): Int = scriptSig.contentHashCode()
}

data class DecodedTxOutput(
    val scriptPubKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is DecodedTxOutput && scriptPubKey.contentEquals(other.scriptPubKey)

    override fun hashCode(): Int = scriptPubKey.contentHashCode()
}

data class DecodedTx(
    /** True when the transaction's sole input has a null prevout. */
    val isCoinbase: Boolean,
    val inputs: List<DecodedTxInput>,
    val outputs: List<DecodedTxOutput>,
)

private const val BLOCK_HEADER_LENGTH = 80
private const val MAX_BLOCK_LENGTH = 4_000_000
private const val SEGWIT_MARKER = 0x00
private const val SEGWIT_FLAG = 0x01
private val NULL_PREVOUT_INDEX = 0xFFFF_FFFFu
private const val MIN_TRANSACTION_LENGTH = 60
private const val MIN_INPUT_LENGTH = 41
private const val MIN_OUTPUT_LENGTH = 9
private const val MIN_WITNESS_ITEM_LENGTH = 1

private class BlockReader(private val data: ByteArray) {
    private var offset = 0

    val remaining: Int
        get() = data.size - offset

    private fun ensure(length: Int) {
        if (length > remaining) {
            throw IllegalArgumentException(
                "unexpected end of block data: wanted $length bytes, have $remaining",
            )
        }
    }

    fun u8(): Int {
        ensure(1)
        return data[offset++].toUInt8()
    }

    fun peekU8(): Int {
        ensure(1)
        return data[offset].toUInt8()
    }

    fun u32LE(): UInt {
        ensure(4)
        val value = data[offset].toUByte().toUInt() or
            (data[offset + 1].toUByte().toUInt() shl 8) or
            (data[offset + 2].toUByte().toUInt() shl 16) or
            (data[offset + 3].toUByte().toUInt() shl 24)
        offset += 4
        return value
    }

    fun bytes(length: Int): ByteArray = bytes(length.toLong())

    fun bytes(length: Long): ByteArray {
        val n = checkedLength(length)
        val out = data.copyOfRange(offset, offset + n)
        offset += n
        return out
    }

    fun skip(length: Int) {
        skip(length.toLong())
    }

    fun skip(length: Long) {
        offset += checkedLength(length)
    }

    /**
     * Rejects lengths that cannot fit in the remaining bytes *before* narrowing
     * to [Int]. A CompactSize of 2^32 would otherwise become `0` via [Long.toInt]
     * and silently consume nothing.
     */
    private fun checkedLength(length: Long): Int {
        if (length < 0L || length > remaining.toLong()) {
            throw IllegalArgumentException(
                "unexpected end of block data: wanted $length bytes, have $remaining",
            )
        }
        return length.toInt()
    }

    fun allZero(length: Int): Boolean {
        ensure(length)
        var isZero = true
        for (i in 0 until length) {
            if (data[offset + i].toUInt8() != 0) isZero = false
        }
        offset += length
        return isZero
    }

    fun compactSize(): Long {
        val decoded = decodeCompactSize(data, offset)
        offset += decoded.length
        return decoded.value
    }

    fun varBytes(): ByteArray = bytes(compactSize())

    fun skipVarBytes() {
        skip(compactSize())
    }
}

private class DecodedTransactionDetails(
    val tx: DecodedTx,
    val hasNullPrevout: Boolean,
)

private fun requireCountFits(
    label: String,
    count: Long,
    remaining: Int,
    minimumItemLength: Int,
) {
    if (count > remaining / minimumItemLength) {
        throw IllegalArgumentException(
            "$label $count cannot fit in $remaining remaining bytes",
        )
    }
}

private fun decodeTransaction(reader: BlockReader): DecodedTransactionDetails {
    reader.skip(4) // version

    var hasWitness = false
    if (reader.peekU8() == SEGWIT_MARKER) {
        reader.u8()
        val flag = reader.u8()
        if (flag != SEGWIT_FLAG) {
            throw IllegalArgumentException(
                "unsupported segwit flag byte: 0x${flag.toString(16)}",
            )
        }
        hasWitness = true
    }

    val inputCount = reader.compactSize()
    if (inputCount == 0L) {
        throw IllegalArgumentException("transaction must contain at least one input")
    }
    requireCountFits("input count", inputCount, reader.remaining, MIN_INPUT_LENGTH)

    val inputs = ArrayList<DecodedTxInput>(inputCount.toInt())
    var nullPrevoutCount = 0
    for (i in 0 until inputCount) {
        val hasZeroTxid = reader.allZero(32)
        val previousOutputIndex = reader.u32LE()
        if (hasZeroTxid && previousOutputIndex == NULL_PREVOUT_INDEX) {
            nullPrevoutCount++
        }
        val scriptSig = reader.varBytes()
        reader.skip(4) // sequence
        inputs.add(DecodedTxInput(scriptSig))
    }

    val outputCount = reader.compactSize()
    if (outputCount == 0L) {
        throw IllegalArgumentException("transaction must contain at least one output")
    }
    requireCountFits("output count", outputCount, reader.remaining, MIN_OUTPUT_LENGTH)

    val outputs = ArrayList<DecodedTxOutput>(outputCount.toInt())
    for (i in 0 until outputCount) {
        reader.skip(8) // value
        val scriptPubKey = reader.varBytes()
        outputs.add(DecodedTxOutput(scriptPubKey))
    }

    if (hasWitness) {
        var hasWitnessStack = false
        for (i in 0 until inputCount) {
            val itemCount = reader.compactSize()
            requireCountFits(
                "witness item count",
                itemCount,
                reader.remaining,
                MIN_WITNESS_ITEM_LENGTH,
            )
            if (itemCount > 0L) hasWitnessStack = true
            for (j in 0 until itemCount) {
                reader.skipVarBytes()
            }
        }
        if (!hasWitnessStack) {
            throw IllegalArgumentException("superfluous witness serialization")
        }
    }

    reader.skip(4) // locktime

    return DecodedTransactionDetails(
        tx = DecodedTx(
            inputs = inputs,
            outputs = outputs,
            isCoinbase = inputCount == 1L && nullPrevoutCount == 1,
        ),
        hasNullPrevout = nullPrevoutCount > 0,
    )
}

/** Decodes just enough of a block's transactions for BIP-158 element extraction. */
fun decodeBlockTransactions(blockBytes: ByteArray): List<DecodedTx> {
    if (blockBytes.size > MAX_BLOCK_LENGTH) {
        throw IllegalArgumentException("block exceeds 4,000,000 bytes")
    }

    val reader = BlockReader(blockBytes)
    reader.skip(BLOCK_HEADER_LENGTH)

    val txCount = reader.compactSize()
    if (txCount == 0L) {
        throw IllegalArgumentException("block must contain at least one transaction")
    }
    requireCountFits(
        "transaction count",
        txCount,
        reader.remaining,
        MIN_TRANSACTION_LENGTH,
    )

    val txs = ArrayList<DecodedTx>(txCount.toInt())
    for (i in 0 until txCount) {
        val details = decodeTransaction(reader)
        if (i == 0L && !details.tx.isCoinbase) {
            throw IllegalArgumentException("first transaction must be coinbase")
        }
        if (i > 0L && details.tx.isCoinbase) {
            throw IllegalArgumentException("coinbase transaction must be first")
        }
        if (i > 0L && details.hasNullPrevout) {
            throw IllegalArgumentException(
                "null prevout is only valid in a coinbase transaction",
            )
        }
        txs.add(details.tx)
    }
    if (reader.remaining != 0) {
        throw IllegalArgumentException("trailing block bytes: ${reader.remaining}")
    }
    return txs
}
