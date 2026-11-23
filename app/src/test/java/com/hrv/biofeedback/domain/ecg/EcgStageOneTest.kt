package com.hrv.biofeedback.domain.ecg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EcgStageOneTest {

    @Test
    fun `default buffer is sixty seconds at 130 Hz`() {
        val config = EcgModeConfig()

        assertEquals(130, config.sampleRateHz)
        assertEquals(60, config.bufferDurationSeconds)
        assertEquals(7_800, config.bufferCapacity)
    }

    @Test
    fun `buffer configuration rejects nonpositive values`() {
        assertThrows(IllegalArgumentException::class.java) {
            EcgModeConfig(bufferDurationSeconds = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EcgModeConfig(sampleRateHz = 0)
        }
    }

    @Test
    fun `timestamps advance at 130 Hz without accumulated rounding drift`() {
        val timing = EcgTimeIndex()
        val segment = EcgContinuitySegment(
            startSampleIndex = 10,
            startTimestampNanos = 100_000_000
        )

        assertEquals(100_000_000, timing.timestampAt(segment, 10))
        assertEquals(107_692_308, timing.timestampAt(segment, 11))
        assertEquals(1_100_000_000, timing.timestampAt(segment, 140))
    }

    @Test
    fun `timestamp converts back to sample offset`() {
        val timing = EcgTimeIndex()
        val segment = EcgContinuitySegment(0, 50_000_000)

        assertEquals(0, timing.sampleOffsetAt(segment, 50_000_000))
        assertEquals(65, timing.sampleOffsetAt(segment, 550_000_000))
        assertEquals(130, timing.sampleOffsetAt(segment, 1_050_000_000))
    }

    @Test
    fun `normal sample interval has no missing sample`() {
        val timing = EcgTimeIndex()

        assertEquals(0, timing.missingSamplesBetween(0, 7_692_308))
        assertNull(timing.discontinuityBetween(41, 0, 7_692_308))
    }

    @Test
    fun `large timestamp jump becomes discontinuity with missing samples`() {
        val timing = EcgTimeIndex()

        val discontinuity = timing.discontinuityBetween(
            previousSampleIndex = 41,
            previousTimestampNanos = 1_000_000,
            nextTimestampNanos = 39_461_538
        )

        assertEquals(4, discontinuity?.missingSampleCount)
        assertEquals(41L, discontinuity?.beforeSampleIndex)
        assertEquals(42L, discontinuity?.afterSampleIndex)
        assertEquals(38_461_538L, discontinuity?.elapsedNanos)
    }

    @Test
    fun `backwards ECG timestamps are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            EcgTimeIndex().missingSamplesBetween(2, 1)
        }
    }

    @Test
    fun `polar epoch zero converts to unix epoch plus polar epoch offset`() {
        assertEquals(
            EcgTimeIndex.POLAR_EPOCH_UNIX_MILLIS,
            EcgTimeIndex.polarTimestampNanosToUnixMillis(0)
        )
        assertEquals(
            EcgTimeIndex.POLAR_EPOCH_UNIX_MILLIS + 1,
            EcgTimeIndex.polarTimestampNanosToUnixMillis(1_000_000)
        )
    }

    @Test
    fun `polar and unix millisecond conversions round trip`() {
        val unixMillis = 1_735_689_123_456L

        assertEquals(
            unixMillis,
            EcgTimeIndex.polarTimestampNanosToUnixMillis(
                EcgTimeIndex.unixMillisToPolarTimestampNanos(unixMillis)
            )
        )
    }

    @Test
    fun `24 bit samples encode as little endian signed values`() {
        assertArrayEquals(
            byteArrayOf(0x56.toByte(), 0x34.toByte(), 0x12.toByte()),
            Ecg24Bit.encodeLittleEndian(0x123456)
        )
        assertArrayEquals(
            byteArrayOf(0xaa.toByte(), 0xcb.toByte(), 0xed.toByte()),
            Ecg24Bit.encodeLittleEndian(-0x123456)
        )
    }

    @Test
    fun `all signed 24 bit boundary values round trip`() {
        val values = listOf(
            Ecg24Bit.MIN_VALUE,
            -1,
            0,
            1,
            Ecg24Bit.MAX_VALUE
        )

        values.forEach { value ->
            assertEquals(
                value,
                Ecg24Bit.decodeLittleEndian(Ecg24Bit.encodeLittleEndian(value))
            )
        }
    }

    @Test
    fun `24 bit encoder rejects out of range values`() {
        assertThrows(IllegalArgumentException::class.java) {
            Ecg24Bit.encodeLittleEndian(Ecg24Bit.MIN_VALUE - 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Ecg24Bit.encodeLittleEndian(Ecg24Bit.MAX_VALUE + 1)
        }
    }
}
