package bip158

/** MSB-first bit stream matching BIP-158 / btcd `write_bits_big_endian`. */
internal class BitWriter {
    private val bytes = ArrayList<Byte>()
    private var acc = 0
    private var bitPos = 0

    fun writeBit(b: Int) {
        if (bitPos == 0) {
            acc = 0
        }
        if (b != 0) {
            acc = acc or (1 shl (7 - bitPos))
        }
        bitPos++
        if (bitPos == 8) {
            bytes.add(acc.toByte())
            bitPos = 0
        }
    }

    /** Writes the `n` least significant bits of `value` in big-endian bit order. */
    fun writeBits(value: ULong, n: Int) {
        for (i in n - 1 downTo 0) {
            writeBit(((value shr i) and 1uL).toInt())
        }
    }

    fun finish(): ByteArray {
        val extra = if (bitPos == 0) 0 else 1
        val out = ByteArray(bytes.size + extra)
        for (i in bytes.indices) {
            out[i] = bytes[i]
        }
        if (bitPos != 0) {
            out[bytes.size] = acc.toByte()
        }
        return out
    }
}

internal class BitReader(private val data: ByteArray) {
    private var byteIdx = 0
    private var bitPos = 0

    val bitsRead: Int
        get() = byteIdx * 8 + bitPos

    fun readBit(): Int {
        if (byteIdx >= data.size) {
            throw IllegalArgumentException("unexpected end of bit stream")
        }
        val bit = (data[byteIdx].toUInt8() ushr (7 - bitPos)) and 1
        bitPos++
        if (bitPos == 8) {
            bitPos = 0
            byteIdx++
        }
        return bit
    }

    /** Reads `n` bits as a big-endian integer (BIP-158 `read_bits_big_endian`). */
    fun readBits(n: Int): ULong {
        var result = 0uL
        repeat(n) {
            result = (result shl 1) or readBit().toULong()
        }
        return result
    }
}

/**
 * MSB-first bit reader for the match hot path.
 * Does not enforce canonical trailing padding.
 */
internal class FastBitReader(private val data: ByteArray, start: Int = 0) {
    private var byteIdx = start
    private var bitPos = 0

    fun readBit(): Int {
        if (byteIdx >= data.size) {
            throw IllegalArgumentException("unexpected end of bit stream")
        }
        val bit = (data[byteIdx].toUInt8() ushr (7 - bitPos)) and 1
        bitPos++
        if (bitPos == 8) {
            bitPos = 0
            byteIdx++
        }
        return bit
    }

    /** Reads `n` bits (0..32) as an unsigned number. */
    fun readBits(n: Int): Long {
        var result = 0L
        var remaining = n
        while (remaining > 0) {
            if (byteIdx >= data.size) {
                throw IllegalArgumentException("unexpected end of bit stream")
            }
            val available = 8 - bitPos
            val take = if (remaining < available) remaining else available
            val shift = available - take
            val mask = (1 shl take) - 1
            result = (result shl take) or
                ((data[byteIdx].toUInt8() ushr shift) and mask).toLong()
            remaining -= take
            bitPos += take
            if (bitPos == 8) {
                bitPos = 0
                byteIdx++
            }
        }
        return result
    }

    /**
     * Golomb-Rice decode (no range checks). Safe as a number when the decoded
     * value fits in a JS safe integer (true for BIP-158 basic-filter deltas).
     */
    fun readGolombRice(p: Int): Long {
        var q = 0L
        while (true) {
            if (byteIdx >= data.size) {
                throw IllegalArgumentException("unexpected end of bit stream")
            }
            val byte = data[byteIdx].toUInt8()
            for (b in bitPos until 8) {
                if (((byte ushr (7 - b)) and 1) == 0) {
                    bitPos = b + 1
                    if (bitPos == 8) {
                        bitPos = 0
                        byteIdx++
                    }
                    val rem = if (p == 0) 0L else readBits(p)
                    return q * (1L shl p) + rem
                }
                q++
            }
            byteIdx++
            bitPos = 0
        }
    }

    /** Golomb-Rice decode returning unsigned 64-bit (large filter ranges). */
    fun readGolombRiceULong(p: Int): ULong {
        var q = 0uL
        while (true) {
            if (readBit() == 0) break
            q++
        }
        if (p == 0) return q
        return (q shl p) or readBits(p).toULong()
    }
}
