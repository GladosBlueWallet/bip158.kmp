package bip158

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SipHashTest {
    private val siphash24ReferenceVectors: List<ULong> = listOf(
        0x726fdb47dd0e0e31uL,
        0x74f839c593dc67fduL,
        0x0d6c8009d9a94f5auL,
        0x85676696d7fb7e2duL,
        0xcf2794e0277187b7uL,
        0x18765564cd99a68duL,
        0xcbc9466e58fee3ceuL,
        0xab0200f58b01d137uL,
        0x93f5f5799a932462uL,
        0x9e0082df0ba9e4b0uL,
        0x7a5dbbc594ddb9f3uL,
        0xf4b32f46226bada7uL,
        0x751e8fbc860ee5fbuL,
        0x14ea5627c0843d90uL,
        0xf723ca908e7af2eeuL,
        0xa129ca6149be45e5uL,
        0x3f2acc7f57c29bdbuL,
        0x699ae9f52cbe4794uL,
        0x4bc1b3f0968dd39cuL,
        0xbb6dc91da77961bduL,
        0xbed65cf21aa2ee98uL,
        0xd0f2cbb02e3b67c7uL,
        0x93536795e3a33e88uL,
        0xa80c038ccd5ccec8uL,
        0xb8ad50c6f649af94uL,
        0xbce192de8a85b8eauL,
        0x17d835b85bbb15f3uL,
        0x2f2e6163076bcfaduL,
        0xde4daaaca71dc9a5uL,
        0xa6a2506687956571uL,
        0xad87a3535c49ef28uL,
        0x32d892fad841c342uL,
        0x7127512f72f27cceuL,
        0xa7f32346f95978e3uL,
        0x12e0b01abb051238uL,
        0x15e034d40fa197aeuL,
        0x314dffbe0815a3b4uL,
        0x027990f029623981uL,
        0xcadcd4e59ef40c4duL,
        0x9abfd8766a33735cuL,
        0x0e3ea96b5304a7d0uL,
        0xad0c42d6fc585992uL,
        0x187306c89bc215a9uL,
        0xd4a60abcf3792b95uL,
        0xf935451de4f21df2uL,
        0xa9538f0419755787uL,
        0xdb9acddff56ca510uL,
        0xd06c98cd5c0975ebuL,
        0xe612a3cb9ecba951uL,
        0xc766e62cfcadaf96uL,
        0xee64435a9752fe72uL,
        0xa192d576b245165auL,
        0x0a8787bf8ecb74b2uL,
        0x81b3e73d20b49b6fuL,
        0x7fa8220ba3b2eceauL,
        0x245731c13ca42499uL,
        0xb78dbfaf3a8d83bduL,
        0xea1ad565322a1a0buL,
        0x60e61c23a3795013uL,
        0x6606d7e446282b93uL,
        0x6ca4ecb15c5f91e1uL,
        0x9f626da15c9625f3uL,
        0xe51b38608ef25f57uL,
        0x958a324ceb064572uL,
    )

    @Test
    fun siphash24MatchesCanonicalVectors() {
        val key = seqBytes(16)
        val message = seqBytes(64)
        for ((length, expected) in siphash24ReferenceVectors.withIndex()) {
            assertEquals(
                expected,
                siphash24(key, message.copyOf(length)),
                "message length $length",
            )
        }
    }

    @Test
    fun hashToRangeUses128BitMultiplyThenHigh64Bits() {
        val key = seqBytes(16)
        val payload = byteArrayOf(0x42)
        val F = 0x100000000uL
        val v = siphash24(key, payload)
        assertEquals(mul64Hi(v, F), hashToRange(payload, F, key))
    }

    @Test
    fun hashToRangeReducesViaHigh64BitsForF2() {
        val key = seqBytes(16)
        val payload = byteArrayOf(0)
        val v = siphash24(key, payload)
        assertEquals(mul64Hi(v, 2uL), hashToRange(payload, 2uL, key))
    }

    @Test
    fun hashToRangeRejectsZeroF() {
        assertFailsMatching(Regex("F|range|uint64", RegexOption.IGNORE_CASE)) {
            hashToRange(ByteArray(0), 0uL, seqBytes(16))
        }
    }

    @Test
    fun hashToRangeAcceptsUint64Max() {
        val result = hashToRange(byteArrayOf(0x42), UINT64_MAX, seqBytes(16))
        assertTrue(result < UINT64_MAX)
    }

    @Test
    fun hashToRangeValidatesSipHashKeyLength() {
        for (length in listOf(0, 15, 17)) {
            assertFailsMatching(Regex("16 bytes", RegexOption.IGNORE_CASE)) {
                hashToRange(ByteArray(0), 1uL, ByteArray(length))
            }
        }
    }
}
