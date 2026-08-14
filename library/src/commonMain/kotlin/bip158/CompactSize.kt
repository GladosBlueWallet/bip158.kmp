package bip158

import kotlin.jvm.JvmName

/** Bitcoin CompactSize (a.k.a. varint) encode/decode. */

internal const val UINT64_MAX: ULong = 0xFFFF_FFFF_FFFF_FFFFuL
internal const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L
private val MAX_SAFE_INTEGER_ULONG: ULong = MAX_SAFE_INTEGER.toULong()

fun encodeCompactSize(n: Int): ByteArray = encodeCompactSize(n.toLong())

fun encodeCompactSize(n: Long): ByteArray {
    if (n < 0L) {
        throw IllegalArgumentException("CompactSize value must be non-negative, got $n")
    }
    return encodeCompactSize(n.toULong())
}

@JvmName("encodeCompactSizeULong")
fun encodeCompactSize(n: ULong): ByteArray {
    if (n <= 0xfcuL) {
        return byteArrayOf(n.toByte())
    }
    if (n <= 0xffffuL) {
        return encodePrefixed(n, 0xfd, 2)
    }
    if (n <= 0xffff_ffffuL) {
        return encodePrefixed(n, 0xfe, 4)
    }
    return encodePrefixed(n, 0xff, 8)
}

data class CompactSizeDecodeResult(val value: Long, val length: Int)

internal data class CompactSizeULongDecodeResult(val value: ULong, val length: Int)

fun decodeCompactSize(bytes: ByteArray, offset: Int = 0): CompactSizeDecodeResult {
    val decoded = decodeCompactSizeULong(bytes, offset)
    if (decoded.value > MAX_SAFE_INTEGER_ULONG) {
        throw IllegalArgumentException("CompactSize value exceeds safe integer range")
    }
    return CompactSizeDecodeResult(decoded.value.toLong(), decoded.length)
}

internal fun decodeCompactSizeULong(
    bytes: ByteArray,
    offset: Int = 0,
): CompactSizeULongDecodeResult {
    if (offset < 0) {
        throw IllegalArgumentException(
            "CompactSize offset must be a non-negative integer, got $offset",
        )
    }
    if (offset >= bytes.size) {
        throw IllegalArgumentException("unexpected end of data reading CompactSize")
    }
    val first = bytes[offset].toUInt8()
    if (first <= 0xfc) {
        return CompactSizeULongDecodeResult(first.toULong(), 1)
    }
    if (first == 0xfd) {
        requireBytes(bytes, offset, 3)
        val value = readLittleEndian(bytes, offset + 1, 2)
        if (value <= 0xfcuL) {
            throw IllegalArgumentException("non-canonical CompactSize encoding")
        }
        return CompactSizeULongDecodeResult(value, 3)
    }
    if (first == 0xfe) {
        requireBytes(bytes, offset, 5)
        val value = readLittleEndian(bytes, offset + 1, 4)
        if (value <= 0xffffuL) {
            throw IllegalArgumentException("non-canonical CompactSize encoding")
        }
        return CompactSizeULongDecodeResult(value, 5)
    }
    requireBytes(bytes, offset, 9)
    val value = readLittleEndian(bytes, offset + 1, 8)
    if (value <= 0xffff_ffffuL) {
        throw IllegalArgumentException("non-canonical CompactSize encoding")
    }
    return CompactSizeULongDecodeResult(value, 9)
}

private fun encodePrefixed(value: ULong, prefix: Int, byteLength: Int): ByteArray {
    val out = ByteArray(byteLength + 1)
    out[0] = prefix.toByte()
    for (i in 0 until byteLength) {
        out[i + 1] = ((value shr (8 * i)) and 0xffuL).toByte()
    }
    return out
}

private fun readLittleEndian(bytes: ByteArray, offset: Int, byteLength: Int): ULong {
    var value = 0uL
    for (i in 0 until byteLength) {
        value = value or (bytes[offset + i].toUByte().toULong() shl (8 * i))
    }
    return value
}

private fun requireBytes(bytes: ByteArray, offset: Int, needed: Int) {
    if (offset + needed > bytes.size) {
        throw IllegalArgumentException("unexpected end of data reading CompactSize")
    }
}
