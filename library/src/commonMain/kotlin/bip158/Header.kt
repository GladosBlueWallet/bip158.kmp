package bip158

/** Hash of a serialized filter: `SHA256d(filterBytes)` (BIP-158 "Filter Hash"). */
fun filterHash(filterBytes: ByteArray): ByteArray = sha256d(filterBytes)

/**
 * Chains a filter hash onto the previous filter header (BIP-158 "Filter Header"):
 * `SHA256d(filterHash || prevHeader)`.
 *
 * Both arguments and the return value are in **internal** (little-endian) byte
 * order. RPC / vector filter headers are display-order; convert with
 * [displayHashToInternal] before calling and when comparing results.
 */
fun filterHeader(filterHashBytes: ByteArray, prevHeader: ByteArray): ByteArray {
    if (filterHashBytes.size != 32) {
        throw IllegalArgumentException(
            "filter hash must be 32 bytes, got ${filterHashBytes.size}",
        )
    }
    if (prevHeader.size != 32) {
        throw IllegalArgumentException(
            "previous header must be 32 bytes, got ${prevHeader.size}",
        )
    }
    return sha256d(concatBytes(filterHashBytes, prevHeader))
}
