package com.hrv.biofeedback.domain.ecg

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EcgRecordingResult(
    val file: File,
    val fileName: String,
    val firstSampleTimestampNanos: Long,
    val writtenSampleCount: Long
)

class EcgRecordingFileWriter(
    private val ecgDataDirectory: File
) {
    private val timing = EcgTimeIndex(EcgModeConfig.DEFAULT_SAMPLE_RATE_HZ)

    private var output: FileOutputStream? = null
    private var file: File? = null
    private var lastWrittenSampleIndex: Long? = null
    private var lastWrittenTimestampNanos: Long? = null
    private var writtenSampleCount = 0L
    private var recordingActive = false

    val isRecording: Boolean
        get() = recordingActive

    fun start(snapshot: EcgBufferSnapshot, recordStartMillis: Long): EcgRecordingResult {
        check(!recordingActive) { "ECG recording is already active" }
        if (!ecgDataDirectory.exists() &&
            !ecgDataDirectory.mkdirs()
        ) {
            throw IllegalStateException("Unable to create ECG data directory")
        }
        if (!ecgDataDirectory.isDirectory) {
            throw IllegalStateException("ECG data path is not a directory")
        }

        val fileName = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(Date(recordStartMillis)) + ".bin"
        val recordingFile = File(ecgDataDirectory, fileName)
        if (recordingFile.exists()) {
            throw IllegalStateException("ECG recording file already exists: $fileName")
        }

        val stream = try {
            FileOutputStream(recordingFile, false)
        } catch (t: Throwable) {
            throw IllegalStateException("Unable to create ECG recording file", t)
        }

        try {
            val oldest = snapshot.samples.firstOrNull()
                ?: throw IllegalStateException("ECG buffer is empty")
            writeHeader(stream, oldest.timestampNanos)
            writeRetroactiveSnapshot(stream, snapshot)

            val newest = snapshot.samples.last()
            output = stream
            file = recordingFile
            lastWrittenSampleIndex = newest.sampleIndex
            lastWrittenTimestampNanos = newest.timestampNanos
            recordingActive = true

            return EcgRecordingResult(
                file = recordingFile,
                fileName = fileName,
                firstSampleTimestampNanos = oldest.timestampNanos,
                writtenSampleCount = writtenSampleCount
            )
        } catch (t: Throwable) {
            runCatching { stream.close() }
            runCatching { recordingFile.delete() }
            throw IllegalStateException("Unable to initialize ECG recording", t)
        }
    }

    fun appendLive(sample: EcgBufferSample) {
        val stream = checkNotNull(output) { "ECG recording is not active" }
        check(recordingActive) { "ECG recording is not active" }

        val previousIndex = lastWrittenSampleIndex
        val previousTimestamp = lastWrittenTimestampNanos
        if (previousIndex != null &&
            previousTimestamp != null &&
            sample.sampleIndex == previousIndex + 1
        ) {
            val discontinuity = timing.discontinuityBetween(
                previousSampleIndex = previousIndex,
                previousTimestampNanos = previousTimestamp,
                nextTimestampNanos = sample.timestampNanos
            )
            if (discontinuity != null) {
                writeZeroSamples(stream, discontinuity.missingSampleCount)
            }
        } else if (previousIndex != null && sample.sampleIndex > previousIndex + 1) {
            val indexGap = (sample.sampleIndex - previousIndex - 1).toInt()
            val timingGap = previousTimestamp?.let {
                timing.missingSamplesBetween(it, sample.timestampNanos)
            } ?: 0
            writeZeroSamples(stream, maxOf(indexGap, timingGap))
        }

        writeSample(stream, sample.voltage)
        lastWrittenSampleIndex = sample.sampleIndex
        lastWrittenTimestampNanos = sample.timestampNanos
        stream.flush()
    }

    fun stop(): EcgRecordingResult {
        check(recordingActive) { "ECG recording is not active" }
        val stream = checkNotNull(output)
        val recordingFile = checkNotNull(file)
        val firstTimestamp = checkNotNull(lastFirstTimestamp)

        return try {
            stream.flush()
            stream.fd.sync()
            stream.close()
            EcgRecordingResult(
                file = recordingFile,
                fileName = recordingFile.name,
                firstSampleTimestampNanos = firstTimestamp,
                writtenSampleCount = writtenSampleCount
            )
        } catch (t: Throwable) {
            throw IllegalStateException("Unable to finalize ECG recording", t)
        } finally {
            output = null
            file = null
            lastWrittenSampleIndex = null
            lastWrittenTimestampNanos = null
            lastFirstTimestamp = null
            writtenSampleCount = 0
            recordingActive = false
        }
    }

    private var lastFirstTimestamp: Long? = null

    private fun writeHeader(stream: FileOutputStream, polarTimestampNanos: Long) {
        val unixTimestampMillis =
            EcgTimeIndex.polarTimestampNanosToUnixMillis(polarTimestampNanos)
        val bytes = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(unixTimestampMillis)
            .array()
        stream.write(bytes)
        lastFirstTimestamp = polarTimestampNanos
        writtenSampleCount = 0
    }

    private fun writeRetroactiveSnapshot(
        stream: FileOutputStream,
        snapshot: EcgBufferSnapshot
    ) {
        val gapsByAfterIndex = snapshot.discontinuities.associateBy {
            it.afterSampleIndex
        }

        for (sample in snapshot.samples) {
            gapsByAfterIndex[sample.sampleIndex]?.let { gap ->
                writeZeroSamples(stream, gap.missingSampleCount)
            }
            writeSample(stream, sample.voltage)
        }
    }

    private fun writeZeroSamples(stream: FileOutputStream, count: Int) {
        if (count <= 0) return
        val chunk = Ecg24Bit.encodeLittleEndian(0)
        val blockSize = minOf(count, 4096)
        val block = ByteArray(blockSize * Ecg24Bit.BYTE_COUNT)
        repeat(blockSize) { offset ->
            val position = offset * Ecg24Bit.BYTE_COUNT
            block[position] = chunk[0]
            block[position + 1] = chunk[1]
            block[position + 2] = chunk[2]
        }
        var remaining = count
        while (remaining > 0) {
            val bytesToWrite = minOf(blockSize, remaining)
            stream.write(block, 0, bytesToWrite * Ecg24Bit.BYTE_COUNT)
            writtenSampleCount += bytesToWrite
            remaining -= bytesToWrite
        }
    }

    private fun writeSample(stream: FileOutputStream, voltage: Int) {
        stream.write(Ecg24Bit.encodeLittleEndian(voltage))
        writtenSampleCount++
    }
}
