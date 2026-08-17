package io.bluewallet.bip158

/**
 * SipHash-2-4 with uint32 limbs (adapted from jedisct1/siphash-js / the TS port).
 *
 * Mutable `{h,l}` slots are reused via [SipHashScratch] so the wallet match
 * path does not allocate per watch item. Callers must not share a scratch
 * across threads.
 */

internal class U64(var h: UInt, var l: UInt) {
    fun copyFrom(other: U64) {
        h = other.h
        l = other.l
    }

    fun copy(): U64 = U64(h, l)

    fun toULong(): ULong = (h.toULong() shl 32) or l.toULong()
}

internal class SipHashKey(
    val v0: U64,
    val v1: U64,
    val v2: U64,
    val v3: U64,
)

/** Reusable SipHash working state. Not thread-safe. */
internal class SipHashScratch {
    val v0 = U64(0u, 0u)
    val v1 = U64(0u, 0u)
    val v2 = U64(0u, 0u)
    val v3 = U64(0u, 0u)
    val mi = U64(0u, 0u)
    val out = U64(0u, 0u)
}

private val K0_XOR = U64(0x736f6d65u, 0x70736575u)
private val K1_XOR = U64(0x646f7261u, 0x6e646f6du)
private val K2_XOR = U64(0x6c796765u, 0x6e657261u)
private val K3_XOR = U64(0x74656462u, 0x79746573u)
private val SIP_FF = U64(0u, 0xffu)

private fun add(a: U64, b: U64) {
    val rl = a.l.toULong() + b.l.toULong()
    a.l = rl.toUInt()
    a.h = a.h + b.h + (rl shr 32).toUInt()
}

private fun xor(a: U64, b: U64) {
    a.h = a.h xor b.h
    a.l = a.l xor b.l
}

private fun rotl(a: U64, n: Int) {
    val h = a.h
    val l = a.l
    a.h = (h shl n) or (l shr (32 - n))
    a.l = (l shl n) or (h shr (32 - n))
}

private fun rotl32(a: U64) {
    val t = a.l
    a.l = a.h
    a.h = t
}

private fun compress(v0: U64, v1: U64, v2: U64, v3: U64) {
    add(v0, v1)
    add(v2, v3)
    rotl(v1, 13)
    rotl(v3, 16)
    xor(v1, v0)
    xor(v3, v2)
    rotl32(v0)
    add(v2, v1)
    add(v0, v3)
    rotl(v1, 17)
    rotl(v3, 21)
    xor(v1, v2)
    xor(v3, v0)
    rotl32(v2)
}

private fun getIntLE(a: ByteArray, offset: Int): UInt {
    return a[offset].toUByte().toUInt() or
        (a[offset + 1].toUByte().toUInt() shl 8) or
        (a[offset + 2].toUByte().toUInt() shl 16) or
        (a[offset + 3].toUByte().toUInt() shl 24)
}

private fun sipAbsorbBlock(s: SipHashScratch, mil: UInt, mih: UInt) {
    val mi = s.mi
    mi.l = mil
    mi.h = mih
    xor(s.v3, mi)
    compress(s.v0, s.v1, s.v2, s.v3)
    compress(s.v0, s.v1, s.v2, s.v3)
    xor(s.v0, mi)
}

private fun sipFinalize(s: SipHashScratch, mil: UInt, mih: UInt) {
    sipAbsorbBlock(s, mil, mih)
    xor(s.v2, SIP_FF)
    compress(s.v0, s.v1, s.v2, s.v3)
    compress(s.v0, s.v1, s.v2, s.v3)
    compress(s.v0, s.v1, s.v2, s.v3)
    compress(s.v0, s.v1, s.v2, s.v3)
}

internal fun createSipHashKey(key: ByteArray): SipHashKey {
    if (key.size != 16) {
        throw IllegalArgumentException("SipHash key must be 16 bytes, got ${key.size}")
    }
    val k0 = U64(getIntLE(key, 4), getIntLE(key, 0))
    val k1 = U64(getIntLE(key, 12), getIntLE(key, 8))
    val v0 = k0.copy()
    val v1 = k1.copy()
    val v2 = k0.copy()
    val v3 = k1.copy()
    xor(v0, K0_XOR)
    xor(v1, K1_XOR)
    xor(v2, K2_XOR)
    xor(v3, K3_XOR)
    return SipHashKey(v0, v1, v2, v3)
}

internal fun siphash24(key: ByteArray, data: ByteArray): ULong {
    val scratch = SipHashScratch()
    siphash24KeyedInto(createSipHashKey(key), data, scratch)
    return scratch.out.toULong()
}

internal fun siphash24KeyedInto(keyed: SipHashKey, data: ByteArray, scratch: SipHashScratch) {
    scratch.v0.copyFrom(keyed.v0)
    scratch.v1.copyFrom(keyed.v1)
    scratch.v2.copyFrom(keyed.v2)
    scratch.v3.copyFrom(keyed.v3)

    val ml = data.size
    if (ml == 22) {
        sipAbsorbBlock(scratch, getIntLE(data, 0), getIntLE(data, 4))
        sipAbsorbBlock(scratch, getIntLE(data, 8), getIntLE(data, 12))
        sipFinalize(
            scratch,
            data[16].toUByte().toUInt() or
                (data[17].toUByte().toUInt() shl 8) or
                (data[18].toUByte().toUInt() shl 16) or
                (data[19].toUByte().toUInt() shl 24),
            data[20].toUByte().toUInt() or
                (data[21].toUByte().toUInt() shl 8) or
                (22u shl 24),
        )
    } else if (ml == 25) {
        sipAbsorbBlock(scratch, getIntLE(data, 0), getIntLE(data, 4))
        sipAbsorbBlock(scratch, getIntLE(data, 8), getIntLE(data, 12))
        sipAbsorbBlock(scratch, getIntLE(data, 16), getIntLE(data, 20))
        sipFinalize(scratch, data[24].toUByte().toUInt(), 25u shl 24)
    } else if (ml == 34) {
        sipAbsorbBlock(scratch, getIntLE(data, 0), getIntLE(data, 4))
        sipAbsorbBlock(scratch, getIntLE(data, 8), getIntLE(data, 12))
        sipAbsorbBlock(scratch, getIntLE(data, 16), getIntLE(data, 20))
        sipAbsorbBlock(scratch, getIntLE(data, 24), getIntLE(data, 28))
        sipFinalize(
            scratch,
            data[32].toUByte().toUInt() or (data[33].toUByte().toUInt() shl 8),
            34u shl 24,
        )
    } else {
        var mp = 0
        val ml7 = ml - 7
        while (mp < ml7) {
            sipAbsorbBlock(scratch, getIntLE(data, mp), getIntLE(data, mp + 4))
            mp += 8
        }

        var b0 = 0u
        var b1 = 0u
        var b2 = 0u
        var b3 = 0u
        var b4 = 0u
        var b5 = 0u
        var b6 = 0u
        val b7 = (ml and 0xff).toUInt()
        val left = ml - mp
        if (left > 0) b0 = data[mp].toUByte().toUInt()
        if (left > 1) b1 = data[mp + 1].toUByte().toUInt()
        if (left > 2) b2 = data[mp + 2].toUByte().toUInt()
        if (left > 3) b3 = data[mp + 3].toUByte().toUInt()
        if (left > 4) b4 = data[mp + 4].toUByte().toUInt()
        if (left > 5) b5 = data[mp + 5].toUByte().toUInt()
        if (left > 6) b6 = data[mp + 6].toUByte().toUInt()

        sipFinalize(
            scratch,
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24),
            b4 or (b5 shl 8) or (b6 shl 16) or (b7 shl 24),
        )
    }

    val out = scratch.out
    out.copyFrom(scratch.v0)
    xor(out, scratch.v1)
    xor(out, scratch.v2)
    xor(out, scratch.v3)
}

/** High 64 bits of a 64x64 -> 128 unsigned multiply. */
internal fun mul64Hi(a: ULong, b: ULong): ULong {
    val aLo = a and 0xFFFFFFFFuL
    val aHi = a shr 32
    val bLo = b and 0xFFFFFFFFuL
    val bHi = b shr 32
    val p0 = aLo * bLo
    val p1 = aLo * bHi
    val p2 = aHi * bLo
    val p3 = aHi * bHi
    val mid = (p0 shr 32) + (p1 and 0xFFFFFFFFuL) + (p2 and 0xFFFFFFFFuL)
    return p3 + (p1 shr 32) + (p2 shr 32) + (mid shr 32)
}

internal fun hashToRange(item: ByteArray, F: ULong, key: ByteArray): ULong {
    if (F < 1uL) {
        throw IllegalArgumentException("F must be a bigint in 1..UINT64_MAX, got $F")
    }
    val scratch = SipHashScratch()
    siphash24KeyedInto(createSipHashKey(key), item, scratch)
    return mul64Hi(scratch.out.toULong(), F)
}

internal fun hashToRangeNumberKeyed(
    item: ByteArray,
    F: Long,
    keyed: SipHashKey,
    scratch: SipHashScratch,
): Long {
    if (F < 1L || F > MAX_SAFE_INTEGER) {
        throw IllegalArgumentException("F must be a safe integer >= 1, got $F")
    }
    siphash24KeyedInto(keyed, item, scratch)
    return mul64Hi(scratch.out.toULong(), F.toULong()).toLong()
}
