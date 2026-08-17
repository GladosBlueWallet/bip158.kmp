package io.bluewallet.bip158

private const val UINT32_MAX: Long = 0xFFFF_FFFFL
private val UINT32_MAX_ULONG: ULong = 0xFFFF_FFFFuL
private const val MAX_FILTER_DATA_BYTES = 4_000_000
private val MAX_FILTER_DATA_BITS: ULong = MAX_FILTER_DATA_BYTES.toULong() * 8uL
private val MAX_SAFE: ULong = MAX_SAFE_INTEGER.toULong()

class GcsFilter internal constructor(
    val N: Long,
    val P: Int,
    val M: ULong,
    data: ByteArray,
    private val dataOffset: Int,
) {
    constructor(N: Long, P: Int, M: ULong, data: ByteArray) : this(N, P, M, data, 0)

    private val dataStorage = data

    /**
     * GCS body bytes. [deserializeGcs] returns the owned body.
     * [parseGcs] copies the tail of the original buffer on each access;
     * matching reads that buffer directly (mutations of the original are visible).
     */
    val data: ByteArray
        get() = if (dataOffset == 0) {
            dataStorage
        } else {
            dataStorage.copyOfRange(dataOffset, dataStorage.size)
        }

    internal fun fastBitReader(): FastBitReader = FastBitReader(dataStorage, dataOffset)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GcsFilter) return false
        if (N != other.N || P != other.P || M != other.M) return false
        val len = dataStorage.size - dataOffset
        val otherLen = other.dataStorage.size - other.dataOffset
        if (len != otherLen) return false
        for (i in 0 until len) {
            if (dataStorage[dataOffset + i] != other.dataStorage[other.dataOffset + i]) {
                return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        var result = N.hashCode()
        result = 31 * result + P
        result = 31 * result + M.hashCode()
        var body = 1
        for (i in dataOffset until dataStorage.size) {
            body = 31 * body + dataStorage[i]
        }
        result = 31 * result + body
        return result
    }
}

data class BuildGcsOptions(
    val items: List<ByteArray>,
    val key: ByteArray,
    val P: Int,
    val M: ULong,
)

data class DeserializeGcsOptions(
    val P: Int = BASIC_FILTER_P,
    val M: ULong = BASIC_FILTER_M,
)

private data class ValidatedFilterParameters(
    val N: Long,
    val P: Int,
    val M: ULong,
    val F: ULong,
)

private fun writeGolombRice(writer: BitWriter, value: ULong, p: Int) {
    var quotient = value shr p
    val remainder = value and ((1uL shl p) - 1uL)
    while (quotient > 0uL) {
        writer.writeBit(1)
        quotient -= 1uL
    }
    writer.writeBit(0)
    writer.writeBits(remainder, p)
}

private fun readGolombRice(reader: BitReader, p: Int, maxValue: ULong): ULong {
    val maxQuotient = maxValue shr p
    var quotient = 0uL
    while (reader.readBit() == 1) {
        quotient += 1uL
        if (quotient > maxQuotient) {
            throw IllegalArgumentException("Golomb-Rice code is outside filter range")
        }
    }

    val base = quotient shl p
    val maxRemainder = maxValue - base
    var remainder = 0uL
    for (i in 0 until p) {
        remainder = (remainder shl 1) or reader.readBit().toULong()
        val remainingBits = p - i - 1
        if ((remainder shl remainingBits) > maxRemainder) {
            throw IllegalArgumentException("Golomb-Rice code is outside filter range")
        }
    }
    return base or remainder
}

private fun dedupeItems(items: List<ByteArray>): List<ByteArray> {
    val seen = HashSet<String>()
    val out = ArrayList<ByteArray>()
    for (item in items) {
        val hex = bytesToHex(item)
        if (seen.add(hex)) {
            out.add(item)
        }
    }
    return out
}

internal fun validateP(P: Int): Int {
    if (P < 0 || P > 32) {
        throw IllegalArgumentException("P must be an integer in 0..32, got $P")
    }
    return P
}

internal fun normalizeM(M: ULong): ULong {
    if (M < 1uL || M > UINT32_MAX_ULONG) {
        throw IllegalArgumentException("M must be an integer in 1..0xffffffff, got $M")
    }
    return M
}

private fun validateN(N: Long): Long {
    if (N < 0L || N > UINT32_MAX) {
        throw IllegalArgumentException("N must be an integer in 0..0xffffffff, got $N")
    }
    return N
}

private fun validateKey(key: ByteArray) {
    if (key.size != 16) {
        throw IllegalArgumentException("SipHash key must be 16 bytes, got ${key.size}")
    }
}

private fun checkedRange(N: Long, M: ULong): ULong {
    val n = N.toULong()
    if (n != 0uL && M > UINT64_MAX / n) {
        throw IllegalArgumentException("filter range F exceeds UINT64_MAX: ${n.toString()}*$M")
    }
    return n * M
}

private fun validateFilterParameters(filter: GcsFilter): ValidatedFilterParameters {
    val N = validateN(filter.N)
    val P = validateP(filter.P)
    val M = normalizeM(filter.M)
    val F = checkedRange(N, M)
    return ValidatedFilterParameters(N, P, M, F)
}

private fun validateFilterBody(data: ByteArray, parameters: ValidatedFilterParameters) {
    if (data.size > MAX_FILTER_DATA_BYTES) {
        throw IllegalArgumentException("GCS data exceeds the 4,000,000-byte limit")
    }

    val N = parameters.N
    val P = parameters.P
    val F = parameters.F
    if (N == 0L) {
        if (data.isNotEmpty()) {
            throw IllegalArgumentException("empty GCS filter must not contain body data")
        }
        return
    }

    val availableBits = data.size.toULong() * 8uL
    val minimumBits = N.toULong() * (P + 1).toULong()
    if (minimumBits > availableBits) {
        throw IllegalArgumentException(
            "truncated GCS body: $N values require at least $minimumBits bits",
        )
    }

    val reader = BitReader(data)
    var value = 0uL
    for (i in 0 until N) {
        val maxDelta = F - value - 1uL
        value += readGolombRice(reader, P, maxDelta)
        if (value >= F) {
            throw IllegalArgumentException("GCS value $value is outside filter range $F")
        }
    }

    val expectedBytes = (reader.bitsRead + 7) / 8
    if (data.size != expectedBytes) {
        throw IllegalArgumentException("non-canonical GCS body contains excess bytes")
    }

    val usedBitsInLastByte = reader.bitsRead % 8
    if (usedBitsInLastByte != 0) {
        val paddingBits = 8 - usedBitsInLastByte
        val paddingMask = (1 shl paddingBits) - 1
        if ((data[data.size - 1].toUInt8() and paddingMask) != 0) {
            throw IllegalArgumentException("non-canonical GCS body has nonzero padding")
        }
    }
}

private fun assertEncodedSize(values: List<ULong>, P: Int) {
    var bits = 0uL
    var last = 0uL
    for (value in values) {
        val delta = value - last
        bits += (delta shr P) + 1uL + P.toULong()
        if (bits > MAX_FILTER_DATA_BITS) {
            throw IllegalArgumentException("GCS data exceeds the 4,000,000-byte limit")
        }
        last = value
    }
}

/** Builds a Golomb-coded set filter (BIP-158 / btcd `BuildGCSFilter`). */
fun buildGcs(options: BuildGcsOptions): GcsFilter =
    buildGcs(options.items, options.key, options.P, options.M)

fun buildGcs(items: List<ByteArray>, key: ByteArray, P: Int, M: ULong): GcsFilter {
    validateKey(key)
    val validP = validateP(P)
    val validM = normalizeM(M)
    val unique = dedupeItems(items)
    val N = validateN(unique.size.toLong())
    val F = checkedRange(N, validM)

    val hashed = if (N == 0L) {
        emptyList()
    } else {
        unique.map { hashToRange(it, F, key) }.sorted()
    }
    assertEncodedSize(hashed, validP)

    val writer = BitWriter()
    var last = 0uL
    for (value in hashed) {
        val delta = value - last
        writeGolombRice(writer, delta, validP)
        last = value
    }

    return GcsFilter(N = N, P = validP, M = validM, data = writer.finish())
}

/** Serializes a filter as `CompactSize(N) || data`. Empty filters are a single `0x00` byte. */
fun serializeGcs(filter: GcsFilter): ByteArray {
    val parameters = validateFilterParameters(filter)
    validateFilterBody(filter.data, parameters)
    return concatBytes(encodeCompactSize(parameters.N), filter.data)
}

/**
 * Lightweight parse of `CompactSize(N) || data` without body validation.
 * Matching reads [bytes] from the CompactSize onward (no body copy).
 * [GcsFilter.data] copies that tail. Use [validateGcs] / [deserializeGcs]
 * when ingesting untrusted bytes.
 */
fun parseGcs(bytes: ByteArray, options: DeserializeGcsOptions): GcsFilter =
    parseGcs(bytes, options.P, options.M)

fun parseGcs(bytes: ByteArray, P: Int = BASIC_FILTER_P, M: ULong = BASIC_FILTER_M): GcsFilter {
    val decoded = decodeCompactSize(bytes, 0)
    val validP = validateP(P)
    val validM = normalizeM(M)
    val N = validateN(decoded.value)
    checkedRange(N, validM)
    if (bytes.size - decoded.length > MAX_FILTER_DATA_BYTES) {
        throw IllegalArgumentException("GCS data exceeds the 4,000,000-byte limit")
    }
    return GcsFilter(
        N = N,
        P = validP,
        M = validM,
        data = bytes,
        dataOffset = decoded.length,
    )
}

/** Deserializes and fully validates a canonical GCS filter body. */
fun deserializeGcs(bytes: ByteArray, options: DeserializeGcsOptions): GcsFilter =
    deserializeGcs(bytes, options.P, options.M)

fun deserializeGcs(
    bytes: ByteArray,
    P: Int = BASIC_FILTER_P,
    M: ULong = BASIC_FILTER_M,
): GcsFilter {
    val decoded = decodeCompactSize(bytes, 0)
    val validP = validateP(P)
    val validM = normalizeM(M)
    if (bytes.size - decoded.length > MAX_FILTER_DATA_BYTES) {
        throw IllegalArgumentException("GCS data exceeds the 4,000,000-byte limit")
    }
    val data = bytes.copyOfRange(decoded.length, bytes.size)
    val filter = GcsFilter(N = decoded.value, P = validP, M = validM, data = data)
    val parameters = validateFilterParameters(filter)
    validateFilterBody(data, parameters)
    return filter
}

/** Validates filter parameters and canonical body encoding. */
fun validateGcs(filter: GcsFilter) {
    val parameters = validateFilterParameters(filter)
    validateFilterBody(filter.data, parameters)
}

/** Tests whether `item` is a member of the filter (fast path; no body re-validation). */
fun matchGcs(filter: GcsFilter, key: ByteArray, item: ByteArray): Boolean {
    validateKey(key)
    val parameters = validateFilterParameters(filter)
    val N = parameters.N
    val P = parameters.P
    val F = parameters.F
    if (N == 0L) return false

    if (F <= MAX_SAFE) {
        val fNum = F.toLong()
        val keyed = createSipHashKey(key)
        val target = hashToRangeNumberKeyed(item, fNum, keyed, SipHashScratch())
        val reader = filter.fastBitReader()
        var value = 0L
        var i = 0L
        while (i < N) {
            value += reader.readGolombRice(P)
            if (value == target) return true
            if (value > target) return false
            i++
        }
        return false
    }

    val target = hashToRange(item, F, key)
    val reader = filter.fastBitReader()
    var value = 0uL
    var i = 0L
    while (i < N) {
        value += reader.readGolombRiceULong(P)
        if (value == target) return true
        if (value > target) return false
        i++
    }
    return false
}

/**
 * Tests whether any of `items` is a member of the filter (fast path; no body
 * re-validation).
 */
fun matchAnyGcs(filter: GcsFilter, key: ByteArray, items: List<ByteArray>): Boolean {
    return matchAnyGcsFast(filter, key, items, LongArray(items.size), SipHashScratch())
}

private fun matchAnyGcsULong(
    filter: GcsFilter,
    N: Long,
    P: Int,
    F: ULong,
    key: ByteArray,
    items: List<ByteArray>,
): Boolean {
    val targets = Array(items.size) { hashToRange(items[it], F, key) }
    targets.sort()

    val reader = filter.fastBitReader()
    var value = 0uL
    var ti = 0
    val lastTarget = targets.last()
    var i = 0L
    while (i < N) {
        value += reader.readGolombRiceULong(P)
        if (value > lastTarget) return false
        while (ti < targets.size && targets[ti] < value) {
            ti++
        }
        if (ti == targets.size) return false
        if (targets[ti] == value) return true
        i++
    }
    return false
}

private fun matchSortedNumberTargets(
    filter: GcsFilter,
    N: Long,
    P: Int,
    targets: LongArray,
    targetCount: Int,
): Boolean {
    if (targetCount == 0) return false
    val reader = filter.fastBitReader()
    var value = 0L
    var ti = 0
    val lastTarget = targets[targetCount - 1]
    var i = 0L
    while (i < N) {
        value += reader.readGolombRice(P)
        if (value > lastTarget) return false
        while (ti < targetCount && targets[ti] < value) {
            ti++
        }
        if (ti == targetCount) return false
        if (targets[ti] == value) return true
        i++
    }
    return false
}

internal fun matchAnyGcsFast(
    filter: GcsFilter,
    key: ByteArray,
    items: List<ByteArray>,
    targetsScratch: LongArray,
    sipScratch: SipHashScratch,
): Boolean {
    validateKey(key)
    if (items.isEmpty()) return false
    val parameters = validateFilterParameters(filter)
    val N = parameters.N
    val P = parameters.P
    val F = parameters.F
    if (N == 0L) return false
    if (F > MAX_SAFE) {
        return matchAnyGcsULong(filter, N, P, F, key, items)
    }

    val fNum = F.toLong()
    val keyed = createSipHashKey(key)
    val scratch = if (targetsScratch.size >= items.size) {
        targetsScratch
    } else {
        LongArray(items.size)
    }
    for (i in items.indices) {
        scratch[i] = hashToRangeNumberKeyed(items[i], fNum, keyed, sipScratch)
    }
    scratch.sort(0, items.size)
    return matchSortedNumberTargets(filter, N, P, scratch, items.size)
}
