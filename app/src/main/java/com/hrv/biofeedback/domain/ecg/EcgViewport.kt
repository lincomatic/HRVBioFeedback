package com.hrv.biofeedback.domain.ecg

data class EcgViewport(
    val startTimestampNanos: Long,
    val endTimestampNanos: Long,
    val zoomSamples: Int = EcgModeConfig.DEFAULT_SAMPLE_RATE_HZ * 8
) {
    val durationNanos: Long
        get() = endTimestampNanos - startTimestampNanos

    init {
        require(endTimestampNanos > startTimestampNanos)
        require(zoomSamples > 0)
    }
}

class EcgViewportPlanner(
    liveWindowSeconds: Double = PORTRAIT_LIVE_WINDOW_SECONDS,
    val scrollThreshold: Double = 0.80
) {
    private var basePortraitSeconds = liveWindowSeconds
    private var liveWindowNanos =
        (liveWindowSeconds * EcgTimeIndex.NANOS_PER_SECOND).toLong()
    private var thresholdNanos =
        (liveWindowNanos * scrollThreshold).toLong()
    private var viewport: EcgViewport? = null

    fun liveViewport(snapshot: EcgBufferSnapshot): EcgViewport {
        if (snapshot.isEmpty) {
            val now = System.nanoTime()
            return EcgViewport(
                startTimestampNanos = now,
                endTimestampNanos = now + liveWindowNanos
            )
        }

        val newest = snapshot.samples.last().timestampNanos
        val oldest = snapshot.samples.first().timestampNanos
        val current = viewport
        if (current == null) {
            val start = oldest
            val end = start + liveWindowNanos
            viewport = EcgViewport(start, end)
            return viewport!!
        }

        val thresholdTime = current.startTimestampNanos + thresholdNanos
        if (newest >= thresholdTime) {
            val heartbeatNanos = heartbeatIntervalNanos(null)
            val shiftedEnd = current.endTimestampNanos + heartbeatNanos
            viewport = EcgViewport(
                startTimestampNanos = current.startTimestampNanos + heartbeatNanos,
                endTimestampNanos = shiftedEnd
            )
        }

        return viewport!!
    }

    fun liveViewport(
        snapshot: EcgBufferSnapshot,
        heartRateBpm: Int?,
        rrIntervalMs: Int?
    ): EcgViewport {
        val newest = snapshot.samples.lastOrNull()?.timestampNanos ?: System.nanoTime()
        val oldest = snapshot.samples.firstOrNull()?.timestampNanos
        val heartbeatNanos = heartbeatIntervalNanos(
            rrIntervalMs ?: heartRateToRrIntervalMs(heartRateBpm)
        )

        val current = viewport
        if (snapshot.isEmpty) {
            val start = current?.startTimestampNanos ?: newest
            return EcgViewport(start, start + liveWindowNanos)
        }
        val oldestTimestamp = oldest ?: newest
        if (current == null ||
            current.endTimestampNanos < oldestTimestamp ||
            current.startTimestampNanos > newest
        ) {
            val start = (newest - liveWindowNanos).coerceAtLeast(oldestTimestamp)
            viewport = EcgViewport(start, start + liveWindowNanos)
            return viewport!!
        }

        val thresholdTime = current.startTimestampNanos + thresholdNanos
        if (newest >= thresholdTime) {
            viewport = EcgViewport(
                startTimestampNanos = current.startTimestampNanos + heartbeatNanos,
                endTimestampNanos = current.endTimestampNanos + heartbeatNanos
            )
        }
        return viewport!!
    }

    fun pausedViewport(start: Long, end: Long): EcgViewport {
        val boundedStart = start
        val boundedEnd = end
        require(boundedEnd > boundedStart)
        viewport = EcgViewport(
            startTimestampNanos = boundedStart,
            endTimestampNanos = boundedEnd
        )
        return viewport!!
    }

    fun beginInspection(snapshot: EcgBufferSnapshot): EcgViewport {
        val current = viewport
        if (snapshot.isEmpty) {
            val start = current?.startTimestampNanos ?: System.nanoTime()
            val end = current?.endTimestampNanos ?: start + liveWindowNanos
            viewport = EcgViewport(start, end)
            return viewport!!
        }

        val existing = current ?: liveViewport(snapshot)
        val oldest = snapshot.samples.first().timestampNanos
        val newest = snapshot.samples.last().timestampNanos
        val duration = existing.durationNanos
        val start = existing.startTimestampNanos.coerceIn(
            oldest,
            (newest - duration).coerceAtLeast(oldest)
        )
        viewport = EcgViewport(start, start + duration)
        return viewport!!
    }

    fun panBy(
        deltaTimestampNanos: Long,
        snapshot: EcgBufferSnapshot
    ): EcgViewport {
        val inspected = beginInspection(snapshot)
        if (snapshot.isEmpty) return inspected

        val oldest = snapshot.samples.first().timestampNanos
        val newest = snapshot.samples.last().timestampNanos
        val duration = inspected.durationNanos
        val latestStart = (newest - duration).coerceAtLeast(oldest)
        val requestedStart = inspected.startTimestampNanos + deltaTimestampNanos
        var boundedStart = requestedStart.coerceIn(oldest, latestStart)
        var boundedEnd = boundedStart + duration
        if (boundedEnd > newest) {
            boundedEnd = newest
            boundedStart = (boundedEnd - duration).coerceAtLeast(oldest)
        }
        viewport = EcgViewport(
            startTimestampNanos = boundedStart,
            endTimestampNanos = boundedEnd
        )
        return viewport!!
    }

    fun pinchZoom(
        scale: Float,
        anchorFraction: Float,
        snapshot: EcgBufferSnapshot
    ): EcgViewport {
        require(scale > 0f) { "Pinch scale must be positive" }
        val inspected = beginInspection(snapshot)
        val safeAnchor = anchorFraction.coerceIn(0f, 1f)
        val oldDuration = inspected.durationNanos
        val scaledDuration = (oldDuration.toDouble() / scale.toDouble()).toLong()
        val newDuration = scaledDuration.coerceIn(
            MIN_PINCH_WINDOW_NANOS,
            MAX_PINCH_WINDOW_NANOS
        )

        val anchorTimestamp = inspected.startTimestampNanos +
            (oldDuration * safeAnchor).toLong()
        var start = anchorTimestamp - (newDuration * safeAnchor).toLong()
        var end = start + newDuration

        if (!snapshot.isEmpty) {
            val oldest = snapshot.samples.first().timestampNanos
            val newest = snapshot.samples.last().timestampNanos
            if (end > newest) {
                end = newest
                start = end - newDuration
            }
            if (start < oldest) {
                start = oldest
                end = start + newDuration
            }
        }

        viewport = EcgViewport(start, end)
        return viewport!!
    }

    fun reset() {
        viewport = null
    }

    fun viewportDurationSecondsFor(isLandscape: Boolean): Double =
        if (isLandscape) {
            clampedLandscapeSeconds()
        } else {
            basePortraitSeconds
        }

    fun setOrientation(isLandscape: Boolean) {
        val windowSeconds = viewportDurationSecondsFor(isLandscape)
        setLiveWindowSeconds(windowSeconds)
    }

    fun setPlotAspectRatio(plotWidthPx: Float, plotHeightPx: Float) {
        require(plotWidthPx > 0f) { "ECG plot width must be positive" }
        require(plotHeightPx > 0f) { "ECG plot height must be positive" }

        val aspectRatio = plotWidthPx / plotHeightPx
        val scaledSeconds = basePortraitSeconds * (
            aspectRatio / PORTRAIT_REFERENCE_ASPECT_RATIO
            )
        setLiveWindowSeconds(
            scaledSeconds.coerceIn(MIN_LIVE_WINDOW_SECONDS, MAX_LIVE_WINDOW_SECONDS)
        )
    }

    fun setPlotSize(plotWidthPx: Float, plotHeightPx: Float) {
        setPlotAspectRatio(plotWidthPx, plotHeightPx)
    }

    private fun setLiveWindowSeconds(windowSeconds: Double) {
        liveWindowNanos =
            (windowSeconds * EcgTimeIndex.NANOS_PER_SECOND).toLong()
        thresholdNanos =
            (liveWindowNanos * scrollThreshold).toLong()
        val current = viewport
        if (current != null) {
            viewport = EcgViewport(
                startTimestampNanos = current.startTimestampNanos,
                endTimestampNanos = current.startTimestampNanos + liveWindowNanos,
                zoomSamples = (windowSeconds * EcgModeConfig.DEFAULT_SAMPLE_RATE_HZ).toInt()
            )
        }
    }

    private fun clampedLandscapeSeconds(): Double =
        (basePortraitSeconds * LANDSCAPE_WIDTH_SCALE)
            .coerceIn(MIN_LIVE_WINDOW_SECONDS, MAX_LIVE_WINDOW_SECONDS)

    private fun heartbeatIntervalNanos(rrMs: Int?): Long {
        val intervalMs = rrMs?.takeIf { it in 300..2000 } ?: 857
        return intervalMs * EcgTimeIndex.NANOS_PER_SECOND / 1000
    }

    private fun heartRateToRrIntervalMs(heartRateBpm: Int?): Int? {
        val bpm = heartRateBpm ?: return null
        if (bpm !in 30..220) return null
        return 60_000 / bpm
    }

    companion object {
        const val PORTRAIT_LIVE_WINDOW_SECONDS = 8.0
        const val LANDSCAPE_LIVE_WINDOW_SECONDS = 16.0
        const val MIN_LIVE_WINDOW_SECONDS = 12.0
        const val MAX_LIVE_WINDOW_SECONDS = 30.0
        const val MIN_PINCH_WINDOW_SECONDS = 2.0
        const val MAX_PINCH_WINDOW_SECONDS = 60.0
        val MIN_PINCH_WINDOW_NANOS =
            (MIN_PINCH_WINDOW_SECONDS * EcgTimeIndex.NANOS_PER_SECOND).toLong()
        val MAX_PINCH_WINDOW_NANOS =
            (MAX_PINCH_WINDOW_SECONDS * EcgTimeIndex.NANOS_PER_SECOND).toLong()

        // Reference plot ratio from the portrait ECG mockup.
        const val PORTRAIT_REFERENCE_ASPECT_RATIO = 438.0 / 696.0
        private const val LANDSCAPE_WIDTH_SCALE = 2.0
    }
}
