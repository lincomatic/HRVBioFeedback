package com.hrv.biofeedback.domain.ecg

data class EcgBufferSample(
    val sampleIndex: Long,
    val timestampNanos: Long,
    val voltage: Int
)

data class EcgBufferSnapshot(
    val samples: List<EcgBufferSample>,
    val discontinuities: List<EcgDiscontinuity>,
    val segments: List<EcgContinuitySegment>,
    val oldestSampleIndex: Long,
    val newestSampleIndex: Long
) {
    val isEmpty: Boolean
        get() = samples.isEmpty()
}

class EcgCircularBuffer(
    config: EcgModeConfig = EcgModeConfig()
) {
    private val capacity: Int = config.bufferCapacity
    private val values = IntArray(capacity)
    private val timing = EcgTimeIndex(config.sampleRateHz)
    private val segments = mutableListOf<EcgContinuitySegment>()
    private val discontinuities = mutableListOf<EcgDiscontinuity>()

    private var count = 0
    private var nextSampleIndex = 0L
    private var previousTimestampNanos: Long? = null

    val size: Int
        get() = count

    val isEmpty: Boolean
        get() = count == 0

    val oldestSampleIndex: Long
        get() = if (count == 0) nextSampleIndex else oldestRetainedIndex()

    val newestSampleIndex: Long
        get() = if (count == 0) -1L else nextSampleIndex - 1

    fun append(voltage: Int, timestampNanos: Long) {
        if (segments.isEmpty()) {
            segments.add(
                EcgContinuitySegment(
                    startSampleIndex = nextSampleIndex,
                    startTimestampNanos = timestampNanos
                )
            )
        } else {
            val previousTimestamp = previousTimestampNanos
            if (previousTimestamp != null) {
                val gap = timing.discontinuityBetween(
                    previousSampleIndex = nextSampleIndex - 1,
                    previousTimestampNanos = previousTimestamp,
                    nextTimestampNanos = timestampNanos
                )
                if (gap != null) {
                    discontinuities.add(gap)
                    segments.add(
                        EcgContinuitySegment(
                            startSampleIndex = nextSampleIndex,
                            startTimestampNanos = timestampNanos
                        )
                    )
                }
            }
        }

        values[(nextSampleIndex % capacity).toInt()] = voltage
        count = minOf(count + 1, capacity)
        previousTimestampNanos = timestampNanos
        nextSampleIndex++

        pruneMetadata()
    }

    fun appendSamples(samples: List<EcgBufferSample>) {
        samples.forEach { append(it.voltage, it.timestampNanos) }
    }

    fun startNewSegment(timestampNanos: Long) {
        val nextIndex = nextSampleIndex
        if (segments.isEmpty()) {
            segments.add(
                EcgContinuitySegment(
                    startSampleIndex = nextIndex,
                    startTimestampNanos = timestampNanos
                )
            )
        } else {
            val currentSegment = segments.last()
            if (currentSegment.startTimestampNanos != timestampNanos ||
                currentSegment.startSampleIndex != nextIndex
            ) {
                segments.add(
                    EcgContinuitySegment(
                        startSampleIndex = nextIndex,
                        startTimestampNanos = timestampNanos
                    )
                )
            }
        }
        previousTimestampNanos = null
    }

    fun clear() {
        count = 0
        nextSampleIndex = 0
        previousTimestampNanos = null
        segments.clear()
        discontinuities.clear()
    }

    fun snapshot(): EcgBufferSnapshot {
        if (count == 0) {
            return EcgBufferSnapshot(
                samples = emptyList(),
                discontinuities = emptyList(),
                segments = emptyList(),
                oldestSampleIndex = 0,
                newestSampleIndex = -1
            )
        }

        val oldestIndex = oldestRetainedIndex()
        val samples = ArrayList<EcgBufferSample>(count)
        for (sampleIndex in oldestIndex until nextSampleIndex) {
            val position = (sampleIndex % capacity).toInt()
            val segment = segmentAt(sampleIndex)
            samples.add(
                EcgBufferSample(
                    sampleIndex = sampleIndex,
                    timestampNanos = timing.timestampAt(segment, sampleIndex),
                    voltage = values[position]
                )
            )
        }

        return EcgBufferSnapshot(
            samples = samples,
            discontinuities = discontinuities
                .filter { it.beforeSampleIndex >= oldestIndex || it.afterSampleIndex >= oldestIndex }
                .toList(),
            segments = segments
                .filter { it.startSampleIndex >= oldestIndex || it.startSampleIndex < nextSampleIndex }
                .toList(),
            oldestSampleIndex = oldestIndex,
            newestSampleIndex = nextSampleIndex - 1
        )
    }

    private fun oldestRetainedIndex(): Long {
        return if (count < capacity) 0 else nextSampleIndex - capacity
    }

    private fun segmentAt(sampleIndex: Long): EcgContinuitySegment {
        var result = segments.first()
        for (segment in segments) {
            if (segment.startSampleIndex <= sampleIndex) {
                result = segment
            } else {
                break
            }
        }
        return result
    }

    private fun pruneMetadata() {
        val oldestIndex = oldestRetainedIndex()
        while (segments.size > 1 && segments[1].startSampleIndex <= oldestIndex) {
            segments.removeAt(0)
        }
        while (discontinuities.isNotEmpty() &&
            discontinuities.first().afterSampleIndex < oldestIndex
        ) {
            discontinuities.removeAt(0)
        }
    }
}
