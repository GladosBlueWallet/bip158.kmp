package io.bluewallet.bip158

internal fun concatBytes(vararg parts: ByteArray): ByteArray {
    var len = 0
    for (p in parts) len += p.size
    val out = ByteArray(len)
    var offset = 0
    for (p in parts) {
        p.copyInto(out, offset)
        offset += p.size
    }
    return out
}

fun hexToBytes(hex: String): ByteArray {
    if (hex.length % 2 != 0) {
        throw IllegalArgumentException("odd hex length: ${hex.length}")
    }
    for (ch in hex) {
        val valid = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
        if (!valid) throw IllegalArgumentException("invalid hex string")
    }
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = hexNibble(hex[i * 2])
        val lo = hexNibble(hex[i * 2 + 1])
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

fun bytesToHex(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xff
        out.append(HEX_DIGITS[v ushr 4])
        out.append(HEX_DIGITS[v and 0x0f])
    }
    return out.toString()
}

private val HEX_DIGITS = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7',
    '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
)

private fun hexNibble(ch: Char): Int = when (ch) {
    in '0'..'9' -> ch - '0'
    in 'a'..'f' -> ch - 'a' + 10
    in 'A'..'F' -> ch - 'A' + 10
    else -> throw IllegalArgumentException("invalid hex string")
}

internal fun Byte.toUInt8(): Int = toInt() and 0xff
