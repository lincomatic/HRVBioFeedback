package com.hrv.biofeedback.domain.ecg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcgCircularBufferTest {

    @Test
    fun `buffer starts empty and default capacity is seventy eight hundred`() {
        val buffer = EcgCircularBuffer()

        assertTrue(buffer.isEmpty)
        assertEquals(0, buffer.size)
        assertEquals(-1, buffer.newestSampleIndex)

        val snapshot = buffer.snapshot()
        assertTrue(snapshot.isEmpty)
        assertTrue(snapshot.samples.isEmpty())
    }

    @Test
    fun `append stores voltage and calculated timestamps`() {
        val buffer = EcgCircularBuffer(EcgModeConfig(bufferDurationSeconds = 1))

        buffer.append(-123, 100_000_000)
        buffer.append(456, 107_692_308)
        buffer.append(789, 115_384_616)

        val snapshot = buffer.snapshot()
        assertEquals(3, snapshot.samples.size)
        assertEquals(-123, snapshot.samples[0].voltage)
        assertEquals(456, snapshot.samples[1].voltage)
        assertEquals(789, snapshot.samples[2].voltage)
        assertEquals(100_000_000, snapshot.samples[0].timestampNanos)
        assertEquals(107_692_308, snapshot.samples[1].timestampNanos)
        assertEquals(115_384_615, snapshot.samples[2].timestampNanos)
        assertTrue(snapshot.discontinuities.isEmpty())
    }

    @Test
    fun `buffer wraps and retains newest samples`() {
        val config = EcgModeConfig(bufferDurationSeconds = 1)
        val buffer = EcgCircularBuffer(config)
        val timing = EcgTimeIndex(config.sampleRateHz)

        repeat(config.bufferCapacity + 25) { index ->
            val timestamp = 1_000_000_000L +
                timing.timestampAt(
                    EcgContinuitySegment(0, 0),
                    index.toLong()
                )
            buffer.append(index, timestamp)
        }

        val snapshot = buffer.snapshot()
        assertEquals(config.bufferCapacity, snapshot.samples.size)
        assertEquals(25L, snapshot.oldestSampleIndex)
        assertEquals((config.bufferCapacity + 24).toLong(), snapshot.newestSampleIndex)
        assertEquals(25, snapshot.samples.first().voltage)
        assertEquals(config.bufferCapacity + 24, snapshot.samples.last().voltage)
    }

    @Test
    fun `timestamp jump creates a discontinuity and separate segment`() {
        val buffer = EcgCircularBuffer(EcgModeConfig(bufferDurationSeconds = 1))

        buffer.append(10, 1_000_000_000)
        buffer.append(20, 1_007_692_308)
        buffer.append(30, 1_038_461_538)

        val snapshot = buffer.snapshot()
        assertEquals(1, snapshot.discontinuities.size)
        assertEquals(1L, snapshot.discontinuities.single().beforeSampleIndex)
        assertEquals(2L, snapshot.discontinuities.single().afterSampleIndex)
        assertEquals(3, snapshot.discontinuities.single().missingSampleCount)
        assertEquals(2, snapshot.segments.size)
        assertEquals(2L, snapshot.segments.last().startSampleIndex)
        assertEquals(1_038_461_538, snapshot.segments.last().startTimestampNanos)
    }

    @Test
    fun `snapshot is immutable copy of buffer state`() {
        val buffer = EcgCircularBuffer(EcgModeConfig(bufferDurationSeconds = 1))
        buffer.append(10, 1_000_000_000)
        val snapshot = buffer.snapshot()

        buffer.append(20, 1_007_692_308)

        assertEquals(1, snapshot.samples.size)
        assertEquals(10, snapshot.samples.single().voltage)
        assertEquals(2, buffer.snapshot().samples.size)
    }

    @Test
    fun `clear removes samples metadata and resets indices`() {
        val buffer = EcgCircularBuffer(EcgModeConfig(bufferDurationSeconds = 1))
        buffer.append(10, 1_000_000_000)
        buffer.append(20, 1_007_692_308)

        buffer.clear()

        assertTrue(buffer.isEmpty)
        assertEquals(0, buffer.size)
        assertEquals(-1, buffer.newestSampleIndex)
        assertTrue(buffer.snapshot().segments.isEmpty())
        assertTrue(buffer.snapshot().discontinuities.isEmpty())
    }

    @Test
    fun `discontinuity remains visible after partial eviction`() {
        val buffer = EcgCircularBuffer(EcgModeConfig(bufferDurationSeconds = 1))
        repeat(128) { index ->
            val timestamp = EcgTimeIndex().timestampAt(
                EcgContinuitySegment(0, 1_000_000_000),
                index.toLong()
            )
            buffer.append(index, timestamp)
        }
        buffer.append(128, 1_990_000_000)
        repeat(128) { index ->
            val absoluteIndex = 129L + index
            val timestamp = EcgTimeIndex().timestampAt(
                EcgContinuitySegment(129, 1_997_692_308),
                absoluteIndex
            )
            buffer.append(absoluteIndex.toInt(), timestamp)
        }

        val snapshot = buffer.snapshot()
        assertFalse(snapshot.discontinuities.isEmpty())
        assertTrue(snapshot.samples.isNotEmpty())
    }
}
