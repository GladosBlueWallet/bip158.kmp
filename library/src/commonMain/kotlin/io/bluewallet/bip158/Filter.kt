package io.bluewallet.bip158

/**
 * Reverses a display-order (RPC/big-endian hex) block hash into Bitcoin's
 * internal little-endian byte representation.
 */
fun displayHashToInternal(display: ByteArray): ByteArray {
    if (display.size != 32) {
        throw IllegalArgumentException("display hash must be 32 bytes, got ${display.size}")
    }
    val out = ByteArray(display.size)
    for (i in display.indices) {
        out[i] = display[display.size - 1 - i]
    }
    return out
}

/**
 * Derives the SipHash key for a basic filter: the first 16 bytes of the
 * block hash in internal little-endian representation (BIP-158).
 */
private fun basicFilterKey(blockHashInternal: ByteArray): ByteArray {
    if (blockHashInternal.size != 32) {
        throw IllegalArgumentException(
            "block hash must be 32 bytes, got ${blockHashInternal.size}",
        )
    }
    return blockHashInternal.copyOf(16)
}

/**
 * Derives the SipHash key from a display-order (RPC/big-endian hex) block
 * hash, as supplied by explorers and the official BIP-158 test vectors.
 */
private fun basicFilterKeyFromDisplay(displayBlockHash: ByteArray): ByteArray {
    return basicFilterKey(displayHashToInternal(displayBlockHash))
}

data class BuildBasicFilterOptions(
    val blockHashDisplay: ByteArray,
    val elements: List<ByteArray>,
)

/** Builds a serialized basic (type 0) BIP-158 filter for a block. */
fun buildBasicFilter(options: BuildBasicFilterOptions): ByteArray =
    buildBasicFilter(options.blockHashDisplay, options.elements)

fun buildBasicFilter(blockHashDisplay: ByteArray, elements: List<ByteArray>): ByteArray {
    val key = basicFilterKeyFromDisplay(blockHashDisplay)
    val filter = buildGcs(
        items = elements.filter { it.isNotEmpty() },
        key = key,
        P = BASIC_FILTER_P,
        M = BASIC_FILTER_M,
    )
    return serializeGcs(filter)
}

/**
 * Match one watchlist against many basic filters (wallet sync hot path).
 * Returns a boolean per filter. Reuses one target scratch buffer across filters.
 * Pass a single filter/hash for one-filter matching.
 *
 * Filters are matched without canonical body re-validation — validate on ingest
 * with [deserializeGcs] / [validateGcs].
 */
fun matchAnyBasicFilters(
    filterBytesList: List<ByteArray>,
    blockHashDisplayList: List<ByteArray>,
    items: List<ByteArray>,
): List<Boolean> {
    if (filterBytesList.size != blockHashDisplayList.size) {
        throw IllegalArgumentException(
            "filter/hash length mismatch: ${filterBytesList.size} filters, ${blockHashDisplayList.size} hashes",
        )
    }
    if (items.isEmpty()) {
        return List(filterBytesList.size) { false }
    }

    val scratch = LongArray(items.size)
    val sipScratch = SipHashScratch()
    val internalHash = ByteArray(32)
    val key = ByteArray(16)
    val out = ArrayList<Boolean>(filterBytesList.size)

    for (i in filterBytesList.indices) {
        val display = blockHashDisplayList[i]
        if (display.size != 32) {
            throw IllegalArgumentException("display hash must be 32 bytes, got ${display.size}")
        }
        for (j in 0 until 32) {
            internalHash[j] = display[31 - j]
        }
        internalHash.copyInto(key, 0, 0, 16)
        val filter = parseGcs(filterBytesList[i], P = BASIC_FILTER_P, M = BASIC_FILTER_M)
        out.add(matchAnyGcsFast(filter, key, items, scratch, sipScratch))
    }
    return out
}
