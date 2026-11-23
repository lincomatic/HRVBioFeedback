package com.hrv.biofeedback.domain.ecg

data class EcgModeConfig(
    val bufferDurationSeconds: Int = 60,
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ
) {
    init {
        require(bufferDurationSeconds > 0) {
            "ECG buffer duration must be positive"
        }
        require(sampleRateHz > 0) {
            "ECG sample rate must be positive"
        }
    }

    val bufferCapacity: Int
        get() = Math.multiplyExact(bufferDurationSeconds, sampleRateHz)

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 130
        const val DEFAULT_BUFFER_DURATION_SECONDS = 60
    }
}

data class EcgContinuitySegment(
    val startSampleIndex: Long,
    val startTimestampNanos: Long
) {
    init {
        require(startSampleIndex >= 0) { "Segment sample index cannot be negative" }
    }
}

data class EcgDiscontinuity(
    val beforeSampleIndex: Long,
    val afterSampleIndex: Long,
    val missingSampleCount: Int,
    val elapsedNanos: Long
) {
    init {
        require(beforeSampleIndex >= 0) { "Discontinuity sample index cannot be negative" }
        require(afterSampleIndex == beforeSampleIndex + 1) {
            "Discontinuity must describe adjacent received samples"
        }
        require(missingSampleCount > 0) { "A discontinuity must omit at least one sample" }
        require(elapsedNanos > 0) { "Discontinuity elapsed time must be positive" }
    }
}

class EcgTimeIndex(private val sampleRateHz: Int = EcgModeConfig.DEFAULT_SAMPLE_RATE_HZ) {
    init {
        require(sampleRateHz > 0) { "ECG sample rate must be positive" }
    }

    fun timestampAt(
        segment: EcgContinuitySegment,
        sampleIndex: Long
    ): Long {
        require(sampleIndex >= segment.startSampleIndex) {
            "Sample index precedes segment start"
        }
        return timestampAt(
            startTimestampNanos = segment.startTimestampNanos,
            sampleOffset = Math.subtractExact(sampleIndex, segment.startSampleIndex)
        )
    }

    fun sampleOffsetAt(
        segment: EcgContinuitySegment,
        timestampNanos: Long
    ): Long {
        require(timestampNanos >= segment.startTimestampNanos) {
            "Timestamp precedes segment start"
        }
        val elapsedNanos = Math.subtractExact(
            timestampNanos,
            segment.startTimestampNanos
        )
        return Math.floorDiv(
            Math.multiplyExact(elapsedNanos, sampleRateHz.toLong()),
            NANOS_PER_SECOND
        )
    }

    fun missingSamplesBetween(
        previousTimestampNanos: Long,
        nextTimestampNanos: Long
    ): Int {
        require(nextTimestampNanos >= previousTimestampNanos) {
            "ECG timestamps cannot move backwards"
        }
        val elapsedNanos = Math.subtractExact(nextTimestampNanos, previousTimestampNanos)
        val sampleIntervals = roundDiv(
            numerator = elapsedNanos,
            denominator = samplePeriodNanos()
        )
        return (sampleIntervals - 1).toInt()
    }

    fun discontinuityBetween(
        previousSampleIndex: Long,
        previousTimestampNanos: Long,
        nextTimestampNanos: Long
    ): EcgDiscontinuity? {
        val missing = missingSamplesBetween(previousTimestampNanos, nextTimestampNanos)
        if (missing == 0) return null
        return EcgDiscontinuity(
            beforeSampleIndex = previousSampleIndex,
            afterSampleIndex = Math.addExact(previousSampleIndex, 1),
            missingSampleCount = missing,
            elapsedNanos = Math.subtractExact(nextTimestampNanos, previousTimestampNanos)
        )
    }

    private fun timestampAt(startTimestampNanos: Long, sampleOffset: Long): Long {
        val offsetNanos = roundDiv(
            numerator = Math.multiplyExact(sampleOffset, NANOS_PER_SECOND),
            denominator = sampleRateHz.toLong()
        )
        return Math.addExact(startTimestampNanos, offsetNanos)
    }

    private fun samplePeriodNanos(): Long = roundDiv(
        numerator = NANOS_PER_SECOND,
        denominator = sampleRateHz.toLong()
    )

    private fun roundDiv(numerator: Long, denominator: Long): Long {
        return Math.floorDiv(numerator + denominator / 2, denominator)
    }

    companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L

        // Polar ECG timestamps use nanoseconds relative to 2000-01-01T00:00:00Z.
        const val POLAR_EPOCH_UNIX_MILLIS = 946_684_800_000L

        fun polarTimestampNanosToUnixMillis(polarTimestampNanos: Long): Long {
            return Math.addExact(
                polarTimestampNanos / 1_000_000L,
                POLAR_EPOCH_UNIX_MILLIS
            )
        }

        fun unixMillisToPolarTimestampNanos(unixMillis: Long): Long {
            val polarMillis = Math.subtractExact(unixMillis, POLAR_EPOCH_UNIX_MILLIS)
            return Math.multiplyExact(polarMillis, 1_000_000L)
        }
    }
}
