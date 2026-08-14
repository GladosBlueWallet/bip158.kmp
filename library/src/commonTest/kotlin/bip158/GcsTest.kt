package bip158

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class GcsTest {
    private val key = key16(1)
    private val p = 19
    private val m = 784_931uL
    private val emptyFilter = GcsFilter(N = 0, P = 0, M = 1uL, data = ByteArray(0))
    private val validDomainCases = listOf(
        Triple("P=0, M=1", 0, 1uL),
        Triple("P=1, M=2", 1, 2uL),
        Triple("P=7, M=128", 7, 128uL),
        Triple("P=19, M=784931", 19, 784_931uL),
        Triple("P=31, M=4294967295", 31, 0xffff_ffffuL),
        Triple("P=32, M=4294967295", 32, 0xffff_ffffuL),
    )

    @Test
    fun emptyFilterSerializesAsSingleZeroByte() {
        val filter = buildGcs(items = emptyList(), key = key, P = p, M = m)
        assertEquals(0L, filter.N)
        assertContentEquals(byteArrayOf(0x00), serializeGcs(filter))
    }

    @Test
    fun roundTripsASmallSetOfItemsThroughSerializeDeserialize() {
        val items = listOf(item("alpha"), item("bravo"), item("charlie"))
        val filter = buildGcs(items = items, key = key, P = p, M = m)
        assertEquals(3L, filter.N)

        val bytes = serializeGcs(filter)
        val decoded = deserializeGcs(bytes, P = p, M = m)

        assertEquals(filter.N, decoded.N)
        assertEquals(filter.P, decoded.P)
        assertEquals(filter.M, decoded.M)
        assertContentEquals(filter.data, decoded.data)
    }

    @Test
    fun deduplicatesIdenticalItems() {
        val items = listOf(item("dup"), item("dup"), item("unique"))
        val filter = buildGcs(items = items, key = key, P = p, M = m)
        assertEquals(2L, filter.N)
    }

    @Test
    fun largeNProducesAMultiByteCompactSizePrefix() {
        val items = List(300) { item("item-$it") }
        val filter = buildGcs(items = items, key = key, P = p, M = m)
        assertEquals(300L, filter.N)
        val bytes = serializeGcs(filter)
        assertEquals(0xfd, bytes[0].toUInt8())
        assertEquals(300 and 0xff, bytes[1].toUInt8())
        assertEquals((300 shr 8) and 0xff, bytes[2].toUInt8())

        val decoded = deserializeGcs(bytes, P = p, M = m)
        assertEquals(300L, decoded.N)
    }

    @Test
    fun acceptsTheValidP32Boundary() {
        val member = item("p32-member")
        val filter = buildGcs(items = listOf(member), key = key, P = 32, M = 1uL)
        assertContentEquals(ByteArray(5), filter.data)

        val decoded = deserializeGcs(serializeGcs(filter), P = 32, M = 1uL)
        assertTrue(matchGcs(decoded, key, member))
    }

    @Test
    fun validDomainRoundTripsAndPreservesMatching() {
        for ((label, propertyP, propertyM) in validDomainCases) {
            val items = List(37) { index ->
                item("valid-domain-member-${index.toString().padStart(2, '0')}")
            }
            val absentItems = List(5) { index ->
                item("valid-domain-absent-${index.toString().padStart(2, '0')}")
            }

            val built = buildGcs(items = items, key = key, P = propertyP, M = propertyM)
            assertEquals(items.size.toLong(), built.N, label)

            val serialized = serializeGcs(built)
            val decoded = deserializeGcs(serialized, P = propertyP, M = propertyM)
            assertEquals(built, decoded, label)
            assertContentEquals(serialized, serializeGcs(decoded), label)

            for (member in items) {
                assertTrue(matchGcs(decoded, key, member), "$label member")
            }

            val querySets = listOf(
                emptyList(),
                listOf(absentItems[0]),
                absentItems,
                listOf(items[0]),
                listOf(absentItems[0], items[18], absentItems[1]),
                listOf(items[36], items[0], items[18]),
                items.filterIndexed { index, _ -> index % 5 == 0 },
                listOf(absentItems[4], absentItems[4], items[5]),
            )
            for (queries in querySets) {
                val repeatedMatch = queries.any { matchGcs(decoded, key, it) }
                assertEquals(
                    repeatedMatch,
                    matchAnyGcs(decoded, key, queries),
                    label,
                )
            }
        }
    }

    @Test
    fun parseGcsDoesNotRequireACanonicalBody() {
        val member = item("member")
        val built = buildGcs(items = listOf(member), key = key, P = 0, M = 2uL)
        val bytes = serializeGcs(built)
        val corrupt = bytes.copyOf()
        corrupt[corrupt.size - 1] = (corrupt[corrupt.size - 1].toUInt8() or 0x01).toByte()

        val parsed = parseGcs(corrupt, P = 0, M = 2uL)
        assertEquals(1L, parsed.N)
        assertEquals(0, parsed.P)
        assertEquals(2uL, parsed.M)
        assertFailsMatching(Regex("padding|canonical", RegexOption.IGNORE_CASE)) {
            deserializeGcs(corrupt, P = 0, M = 2uL)
        }
        assertTrue(matchGcs(parsed, key, member))
    }

    @Test
    fun parseGcsBodyIsAViewOfTheInputBuffer() {
        val member = item("member")
        val built = buildGcs(items = listOf(member), key = key, P = 0, M = 2uL)
        val bytes = serializeGcs(built)
        val parsed = parseGcs(bytes, P = 0, M = 2uL)
        val last = bytes.size - 1
        bytes[last] = (bytes[last].toUInt8() xor 0xff).toByte()
        assertEquals(bytes[last], parsed.data.last())
    }

    @Test
    fun parseGcsRejectsTruncatedHeadersAndOversizedBodies() {
        assertFails { parseGcs(ByteArray(0), P = 0, M = 1uL) }
        val oversized = ByteArray(4_000_002)
        oversized[0] = 0x01
        assertFailsMatching(Regex("4.?000.?000|large|size", RegexOption.IGNORE_CASE)) {
            parseGcs(oversized, P = 0, M = 1uL)
        }
    }

    @Test
    fun matchGcsMatchesEveryItemUsedToBuildTheFilter() {
        val items = listOf(item("alpha"), item("bravo"), item("charlie"), item("delta"))
        val filter = buildGcs(items = items, key = key, P = p, M = m)
        for (it in items) {
            assertTrue(matchGcs(filter, key, it))
        }
        assertEquals(false, matchGcs(filter, key, item("absent-item-not-in-set")))
        assertEquals(false, matchGcs(filter, key16(99), items[0]))
        val empty = buildGcs(items = emptyList(), key = key, P = p, M = m)
        assertEquals(false, matchGcs(empty, key, item("anything")))
    }

    @Test
    fun matchAnyGcsBehavesAsRepeatedMatchGcs() {
        val items = listOf(item("alpha"), item("bravo"), item("charlie"), item("delta"))
        val filter = buildGcs(items = items, key = key, P = p, M = m)
        assertTrue(
            matchAnyGcs(filter, key, listOf(item("nope"), item("bravo"), item("still-nope"))),
        )
        assertEquals(
            false,
            matchAnyGcs(filter, key, listOf(item("nope"), item("still-nope"), item("nada"))),
        )
        assertEquals(false, matchAnyGcs(filter, key, emptyList()))
    }

    @Test
    fun rejectsInvalidPCentrally() {
        for (badP in listOf(-1, 33)) {
            assertFailsMatching(Regex("P")) {
                buildGcs(items = emptyList(), key = key, P = badP, M = 1uL)
            }
            assertFailsMatching(Regex("P")) {
                deserializeGcs(byteArrayOf(0), P = badP, M = 1uL)
            }
            assertFailsMatching(Regex("P")) {
                serializeGcs(GcsFilter(N = 0, P = badP, M = 1uL, data = ByteArray(0)))
            }
        }
    }

    @Test
    fun rejectsMOutsideRange() {
        for (badM in listOf(0uL, 0x1_0000_0000uL)) {
            assertFailsMatching(Regex("M")) {
                buildGcs(items = emptyList(), key = key, P = 0, M = badM)
            }
            assertFailsMatching(Regex("M")) {
                deserializeGcs(byteArrayOf(0), P = 0, M = badM)
            }
        }
    }

    @Test
    fun acceptsMAtAllowedMaximum() {
        assertEquals(
            0xffff_ffffuL,
            buildGcs(items = emptyList(), key = key, P = 0, M = 0xffff_ffffuL).M,
        )
        assertEquals(
            0xffff_ffffuL,
            deserializeGcs(byteArrayOf(0), P = 0, M = 0xffff_ffffuL).M,
        )
    }

    @Test
    fun rejectsInvalidDirectFilterN() {
        for (badN in listOf(-1L, 0x1_0000_0000L)) {
            assertFailsMatching(Regex("N|count", RegexOption.IGNORE_CASE)) {
                serializeGcs(GcsFilter(N = badN, P = 0, M = 1uL, data = ByteArray(0)))
            }
        }
    }

    @Test
    fun validatesKeysBeforeEmptyOperationShortCircuit() {
        val invalidKey = ByteArray(15)
        assertFailsMatching(Regex("16 bytes", RegexOption.IGNORE_CASE)) {
            buildGcs(items = emptyList(), key = invalidKey, P = 0, M = 1uL)
        }
        assertFailsMatching(Regex("16 bytes", RegexOption.IGNORE_CASE)) {
            matchGcs(emptyFilter, invalidKey, ByteArray(0))
        }
        assertFailsMatching(Regex("16 bytes", RegexOption.IGNORE_CASE)) {
            matchAnyGcs(emptyFilter, invalidKey, emptyList())
        }
    }

    @Test
    fun acceptsTheCanonicalGenesisBody() {
        val bytes = hexToBytes("019dfca8")
        assertContentEquals(bytes, serializeGcs(deserializeGcs(bytes)))
    }

    @Test
    fun rejectsANoncanonicalCompactSizeCount() {
        assertFailsMatching(Regex("canonical", RegexOption.IGNORE_CASE)) {
            deserializeGcs(hexToBytes("fd0100"), P = 0, M = 1uL)
        }
    }

    @Test
    fun rejectsNoncanonicalSerializedBodies() {
        val cases = listOf(
            Triple("empty filter with a body", "00aa", null),
            Triple("audit truncation 0d000000", "0d000000", null),
            Triple("audit truncation 0d000010", "0d000010", null),
            Triple("truncated one-item body", "01", DeserializeGcsOptions(P = 0, M = 2uL)),
            Triple("out-of-range cumulative value", "0180", DeserializeGcsOptions(P = 0, M = 1uL)),
            Triple("genesis nonzero padding", "019dfcaf", null),
            Triple("genesis excess byte", "019dfca800", null),
        )
        for ((_, hex, options) in cases) {
            assertFails {
                if (options == null) {
                    deserializeGcs(hexToBytes(hex))
                } else {
                    deserializeGcs(hexToBytes(hex), options)
                }
            }
        }
    }

    @Test
    fun rejectsDirectInvalidFiltersOnSerialize() {
        val cases = listOf(
            GcsFilter(N = 0, P = 0, M = 1uL, data = byteArrayOf(0)),
            GcsFilter(N = 1, P = 0, M = 2uL, data = ByteArray(0)),
            GcsFilter(N = 1, P = 0, M = 2uL, data = byteArrayOf(0x01)),
            GcsFilter(N = 1, P = 0, M = 2uL, data = byteArrayOf(0x00, 0x00)),
            GcsFilter(N = 1, P = 0, M = 1uL, data = byteArrayOf(0x80.toByte())),
        )
        for (filter in cases) {
            assertFails { serializeGcs(filter) }
        }
    }

    @Test
    fun validateGcsRejectsCorruptBodiesWhileMatchIsAFastPath() {
        val member = item("member")
        val valid = buildGcs(items = listOf(member), key = key, P = 0, M = 2uL)
        val corruptData = valid.data.copyOf()
        corruptData[corruptData.size - 1] =
            (corruptData[corruptData.size - 1].toUInt8() or 0x01).toByte()
        val corrupt = GcsFilter(N = valid.N, P = valid.P, M = valid.M, data = corruptData)

        assertFailsMatching(Regex("padding|canonical", RegexOption.IGNORE_CASE)) {
            validateGcs(corrupt)
        }
        assertTrue(matchGcs(corrupt, key, member))
        assertTrue(matchAnyGcs(corrupt, key, listOf(member)))
    }

    @Test
    fun deserializationClonesBodyBytes() {
        val source = byteArrayOf(0x01, 0x00)
        val filter = deserializeGcs(source, P = 0, M = 2uL)
        source[1] = 0xff.toByte()
        assertContentEquals(byteArrayOf(0x00), filter.data)
    }

    @Test
    fun rejectsDirectDataLargerThanFourMillionBytes() {
        val filter = GcsFilter(
            N = 1,
            P = 0,
            M = 2uL,
            data = ByteArray(4_000_001),
        )
        assertFailsMatching(Regex("4.?000.?000|large|size", RegexOption.IGNORE_CASE)) {
            serializeGcs(filter)
        }
    }

    @Test
    fun rejectsAnOversizedSerializedBodyBeforeDecoding() {
        val serialized = ByteArray(4_000_002)
        serialized[0] = 0x01
        assertFailsMatching(Regex("4.?000.?000|large|size", RegexOption.IGNORE_CASE)) {
            deserializeGcs(serialized, P = 0, M = 1uL)
        }
    }

    @Test
    fun rejectsUint32MaxNWhenTheBodyCannotContainThatManyCodes() {
        val serialized = byteArrayOf(
            0xfe.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
        )
        assertFailsMatching(Regex("truncated|require", RegexOption.IGNORE_CASE)) {
            deserializeGcs(serialized, P = 0, M = 1uL)
        }
    }

    @Test
    fun rejectsAnAllOnesBodyWhoseGolombValueIsOutsideF() {
        val body = ByteArray(4_000_000) { 0xff.toByte() }
        assertFailsMatching(Regex("outside filter range", RegexOption.IGNORE_CASE)) {
            serializeGcs(
                GcsFilter(N = 1, P = 0, M = 1uL, data = body),
            )
        }
    }

    @Test
    fun rejectsThePathologicalUnaryBuildBeforeEncoding() {
        assertFailsMatching(Regex("4.?000.?000|large|size", RegexOption.IGNORE_CASE)) {
            buildGcs(
                items = listOf(ByteArray(0)),
                key = ByteArray(16),
                P = 0,
                M = 0xffffffffuL,
            )
        }
    }
}
