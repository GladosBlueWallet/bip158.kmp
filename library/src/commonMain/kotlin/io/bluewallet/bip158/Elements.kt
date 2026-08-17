package io.bluewallet.bip158

data class BasicFilterBlockInput(
    val blockBytes: ByteArray,
    /** Previous output scripts for all non-coinbase inputs, in block order. */
    val prevOutputScripts: List<ByteArray>,
)

private const val OP_RETURN: Int = 0x6a

/**
 * Extracts the BIP-158 basic filter elements from a block: previous output
 * scripts for every non-coinbase input, plus each output `scriptPubKey`
 * except `OP_RETURN`-prefixed and empty scripts.
 */
fun basicFilterElements(input: BasicFilterBlockInput): List<ByteArray> {
    val txs = decodeBlockTransactions(input.blockBytes)
    val elements = ArrayList<ByteArray>()
    var prevScriptIndex = 0

    for (tx in txs) {
        if (!tx.isCoinbase) {
            for (i in tx.inputs.indices) {
                if (prevScriptIndex >= input.prevOutputScripts.size) {
                    throw IllegalArgumentException(
                        "prevOutputScripts has fewer entries than non-coinbase inputs",
                    )
                }
                val script = input.prevOutputScripts[prevScriptIndex++]
                if (script.isNotEmpty()) elements.add(script.copyOf())
            }
        }

        for (output in tx.outputs) {
            val script = output.scriptPubKey
            if (script.isEmpty()) continue
            if (script[0].toUInt8() == OP_RETURN) continue
            elements.add(script)
        }
    }

    if (prevScriptIndex != input.prevOutputScripts.size) {
        throw IllegalArgumentException(
            "prevOutputScripts count mismatch: consumed $prevScriptIndex, provided ${input.prevOutputScripts.size}",
        )
    }

    return elements
}
