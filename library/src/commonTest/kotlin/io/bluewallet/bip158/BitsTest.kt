package io.bluewallet.bip158

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BitsTest {
    @Test
    fun roundTripsUnaryPatternAndPBitRemainder() {
        val writer = BitWriter()
        writer.writeBit(1)
        writer.writeBit(0)
        writer.writeBits(1uL, 2)
        val bytes = writer.finish()
        assertContentEquals(byteArrayOf(0x90.toByte()), bytes)
        val reader = BitReader(bytes)
        assertEquals(1, reader.readBit())
        assertEquals(0, reader.readBit())
        assertEquals(1uL, reader.readBits(2))
    }

    @Test
    fun finishIsIdempotent() {
        val writer = BitWriter()
        writer.writeBits(0b101uL, 3)
        assertContentEquals(byteArrayOf(0xa0.toByte()), writer.finish())
        assertContentEquals(byteArrayOf(0xa0.toByte()), writer.finish())
    }

    @Test
    fun finishDoesNotCorruptWritesThatFollowIt() {
        val writer = BitWriter()
        writer.writeBits(0b101uL, 3)
        val first = writer.finish()
        writer.writeBit(1)
        assertContentEquals(byteArrayOf(0xa0.toByte()), first)
        assertContentEquals(byteArrayOf(0xb0.toByte()), writer.finish())
    }

    @Test
    fun reportsHowManyBitsHaveBeenConsumed() {
        val reader = BitReader(byteArrayOf(0xa5.toByte(), 0x80.toByte()))
        assertEquals(0, reader.bitsRead)
        assertEquals(0b101uL, reader.readBits(3))
        assertEquals(3, reader.bitsRead)
        assertEquals(0b001011uL, reader.readBits(6))
        assertEquals(9, reader.bitsRead)
    }

    @Test
    fun throwsWhenTheBitStreamEndsEarly() {
        val reader = BitReader(byteArrayOf(0x80.toByte()))
        assertEquals(1, reader.readBit())
        assertFailsMatching(Regex("unexpected end", RegexOption.IGNORE_CASE)) {
            reader.readBits(8)
        }
    }

    @Test
    fun fastBitReaderAgreesWithBitReader() {
        val writer = BitWriter()
        writer.writeBit(1)
        writer.writeBit(0)
        writer.writeBits(0b1011uL, 4)
        writer.writeBits(0uL, 3)
        val bytes = writer.finish()

        val slow = BitReader(bytes)
        val fast = FastBitReader(bytes)
        assertEquals(slow.readBit(), fast.readBit())
        assertEquals(slow.readBit(), fast.readBit())
        assertEquals(slow.readBits(4).toLong(), fast.readBits(4))
        assertEquals(slow.readBits(3).toLong(), fast.readBits(3))
    }

    @Test
    fun decodesGolombRiceValuesWrittenByBitWriter() {
        val writer = BitWriter()
        writer.writeBit(1)
        writer.writeBit(0)
        writer.writeBits(1uL, 2)
        writer.writeBit(0)
        writer.writeBits(0uL, 2)

        val reader = FastBitReader(writer.finish())
        assertEquals(5L, reader.readGolombRice(2))
        assertEquals(0L, reader.readGolombRice(2))
    }

    @Test
    fun readGolombRiceULongMatchesNumberPathForSmallValues() {
        val writer = BitWriter()
        writer.writeBit(1)
        writer.writeBit(0)
        writer.writeBits(1uL, 3)
        val bytes = writer.finish()

        assertEquals(9L, FastBitReader(bytes).readGolombRice(3))
        assertEquals(9uL, FastBitReader(bytes).readGolombRiceULong(3))
    }

    @Test
    fun throwsWhenGolombRiceUnaryRunsOffTheEnd() {
        val reader = FastBitReader(byteArrayOf(0xff.toByte()))
        assertFailsMatching(Regex("unexpected end", RegexOption.IGNORE_CASE)) {
            reader.readGolombRice(2)
        }
    }
}
