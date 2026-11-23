package com.hrv.biofeedback.domain.ecg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class EcgRecordingFileWriterTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `start writes retroactive buffer and unix millisecond header`() {
        val directory = temporaryFolder.newFolder("ecg_data")
        val writer = EcgRecordingFileWriter(directory)
        val snapshot = snapshot(
            listOf(
                EcgBufferSample(10, 1_000_000_000, 100),
                EcgBufferSample(11, 1_007_692_308, -200),
                EcgBufferSample(12, 1_015_384_616, 300)
            )
        )

        val result = writer.start(snapshot, 1_735_689_123_456)

        assertTrue(writer.isRecording)
        assertTrue(Regex("\\d{8}-\\d{6}\\.bin").matches(result.fileName))
        assertEquals(1_000_000_000, result.firstSampleTimestampNanos)
        assertEquals(3, result.writtenSampleCount)

        val bytes = Files.readAllBytes(result.file.toPath())
        assertEquals(8 + 3 * 3, bytes.size)
        assertEquals(
            EcgTimeIndex.polarTimestampNanosToUnixMillis(1_000_000_000),
            readHeader(bytes)
        )
        assertEquals(100, Ecg24Bit.decodeLittleEndian(bytes, 8))
        assertEquals(-200, Ecg24Bit.decodeLittleEndian(bytes, 11))
        assertEquals(300, Ecg24Bit.decodeLittleEndian(bytes, 14))

        writer.stop()
    }

    @Test
    fun `retroactive gaps are zero filled`() {
        val directory = temporaryFolder.newFolder("ecg_data")
        val writer = EcgRecordingFileWriter(directory)
        val gap = EcgDiscontinuity(
            beforeSampleIndex = 1,
            afterSampleIndex = 2,
            missingSampleCount = 3,
            elapsedNanos = 30_769_230
        )
        val snapshot = snapshot(
            listOf(
                EcgBufferSample(0, 1_000_000_000, 100),
                EcgBufferSample(1, 1_007_692_308, 200),
                EcgBufferSample(2, 1_038_461_538, 300)
            ),
            listOf(gap)
        )

        val result = writer.start(snapshot, 1_735_689_123_456)
        val bytes = Files.readAllBytes(result.file.toPath())

        assertEquals(100, Ecg24Bit.decodeLittleEndian(bytes, 8))
        assertEquals(200, Ecg24Bit.decodeLittleEndian(bytes, 11))
        assertEquals(0, Ecg24Bit.decodeLittleEndian(bytes, 14))
        assertEquals(0, Ecg24Bit.decodeLittleEndian(bytes, 17))
        assertEquals(0, Ecg24Bit.decodeLittleEndian(bytes, 20))
        assertEquals(300, Ecg24Bit.decodeLittleEndian(bytes, 23))
        assertEquals(6, result.writtenSampleCount)

        writer.stop()
    }

    @Test
    fun `live append follows retroactive content without duplication`() {
        val directory = temporaryFolder.newFolder("ecg_data")
        val writer = EcgRecordingFileWriter(directory)
        val snapshot = snapshot(
            listOf(
                EcgBufferSample(10, 1_000_000_000, 100),
                EcgBufferSample(11, 1_007_692_308, 200)
            )
        )

        writer.start(snapshot, 1_735_689_123_456)
        writer.appendLive(EcgBufferSample(12, 1_015_384_616, 300))
        val stopped = writer.stop()
        val bytes = Files.readAllBytes(stopped.file.toPath())

        assertEquals(100, Ecg24Bit.decodeLittleEndian(bytes, 8))
        assertEquals(200, Ecg24Bit.decodeLittleEndian(bytes, 11))
        assertEquals(300, Ecg24Bit.decodeLittleEndian(bytes, 14))
        assertEquals(3, stopped.writtenSampleCount)
        assertFalse(writer.isRecording)
    }

    @Test
    fun `live gap writes zero samples`() {
        val directory = temporaryFolder.newFolder("ecg_data")
        val writer = EcgRecordingFileWriter(directory)
        val snapshot = snapshot(
            listOf(EcgBufferSample(10, 1_000_000_000, 100))
        )

        writer.start(snapshot, 1_735_689_123_456)
        writer.appendLive(EcgBufferSample(12, 1_038_461_538, 300))
        val stopped = writer.stop()
        val bytes = Files.readAllBytes(stopped.file.toPath())

        assertEquals(100, Ecg24Bit.decodeLittleEndian(bytes, 8))
        assertEquals(0, Ecg24Bit.decodeLittleEndian(bytes, 11))
        assertEquals(0, Ecg24Bit.decodeLittleEndian(bytes, 14))
        assertEquals(0, Ecg24Bit.decodeLittleEndian(bytes, 17))
        assertEquals(0, Ecg24Bit.decodeLittleEndian(bytes, 20))
        assertEquals(300, Ecg24Bit.decodeLittleEndian(bytes, 23))
        assertEquals(6, stopped.writtenSampleCount)
    }

    @Test
    fun `stop cannot be called twice and empty snapshot is rejected`() {
        val directory = temporaryFolder.newFolder("ecg_data")
        val writer = EcgRecordingFileWriter(directory)

        assertThrows(IllegalStateException::class.java) {
            writer.start(snapshot(emptyList()), 1_735_689_123_456)
        }

        assertThrows(IllegalStateException::class.java) {
            writer.stop()
        }
    }

    private fun snapshot(
        samples: List<EcgBufferSample>,
        gaps: List<EcgDiscontinuity> = emptyList()
    ): EcgBufferSnapshot {
        val oldest = samples.firstOrNull()?.sampleIndex ?: 0
        val newest = samples.lastOrNull()?.sampleIndex ?: -1
        return EcgBufferSnapshot(
            samples = samples,
            discontinuities = gaps,
            segments = emptyList(),
            oldestSampleIndex = oldest,
            newestSampleIndex = newest
        )
    }

    private fun readHeader(bytes: ByteArray): Long {
        return ByteBuffer.wrap(bytes, 0, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .long
    }
}
