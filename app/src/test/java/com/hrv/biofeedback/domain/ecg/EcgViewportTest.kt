package com.hrv.biofeedback.domain.ecg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EcgViewportTest {

    @Test
    fun `live viewport starts with oldest retained timestamp`() {
        val planner = EcgViewportPlanner(liveWindowSeconds = 8.0)
        val snapshot = snapshotFrom(
            startIndex = 0,
            startTimestamp = 1_000_000_000,
            count = 130
        )

        val viewport = planner.liveViewport(snapshot)

        assertEquals(1_000_000_000, viewport.startTimestampNanos)
        assertEquals(
            1_000_000_000 + 8 * EcgTimeIndex.NANOS_PER_SECOND,
            viewport.endTimestampNanos
        )
    }

    @Test
    fun `viewport scrolls by heartbeat when eighty percent threshold is reached`() {
        val planner = EcgViewportPlanner(liveWindowSeconds = 8.0)
        var snapshot = snapshotFrom(
            startIndex = 0,
            startTimestamp = 1_000_000_000,
            count = 130
        )
        val initial = planner.liveViewport(snapshot)

        snapshot = snapshotFrom(
            startIndex = 0,
            startTimestamp = 1_000_000_000,
            count = 1040
        )
        val shifted = planner.liveViewport(snapshot, 70, 857)

        assertTrue(shifted.startTimestampNanos > initial.startTimestampNanos)
        assertEquals(
            8 * EcgTimeIndex.NANOS_PER_SECOND,
            shifted.durationNanos
        )
    }

    @Test
    fun `paused viewport preserves selected time range`() {
        val planner = EcgViewportPlanner()

        val viewport = planner.pausedViewport(
            start = 10_000_000_000,
            end = 18_000_000_000
        )

        assertEquals(10_000_000_000, viewport.startTimestampNanos)
        assertEquals(18_000_000_000, viewport.endTimestampNanos)
    }

    @Test
    fun `portrait and landscape viewport durations use ratio scaling and clamp`() {
        val planner = EcgViewportPlanner(
            liveWindowSeconds = EcgViewportPlanner.PORTRAIT_LIVE_WINDOW_SECONDS
        )
        planner.setPlotSize(plotWidthPx = 438f, plotHeightPx = 696f)
        val portraitSnapshot = snapshotFrom(0, 1_000_000_000, 130)
        assertEquals(
            12 * EcgTimeIndex.NANOS_PER_SECOND,
            planner.liveViewport(portraitSnapshot).durationNanos
        )

        planner.reset()
        planner.setPlotSize(plotWidthPx = 1148f, plotHeightPx = 472f)
        val landscapeSnapshot = snapshotFrom(0, 1_000_000_000, 130)
        assertEquals(
            30 * EcgTimeIndex.NANOS_PER_SECOND,
            planner.liveViewport(landscapeSnapshot).durationNanos
        )
    }

    @Test
    fun `aspect ratio scaling clamps minimum and maximum durations`() {
        val planner = EcgViewportPlanner(
            liveWindowSeconds = EcgViewportPlanner.PORTRAIT_LIVE_WINDOW_SECONDS
        )

        planner.setPlotSize(plotWidthPx = 100f, plotHeightPx = 1000f)
        assertEquals(
            12 * EcgTimeIndex.NANOS_PER_SECOND,
            planner.liveViewport(snapshotFrom(0, 1_000_000_000, 130)).durationNanos
        )

        planner.reset()
        planner.setPlotSize(plotWidthPx = 4000f, plotHeightPx = 100f)
        assertEquals(
            30 * EcgTimeIndex.NANOS_PER_SECOND,
            planner.liveViewport(snapshotFrom(0, 1_000_000_000, 130)).durationNanos
        )
    }

    @Test
    fun `pan remains bounded to retained buffer`() {
        val planner = EcgViewportPlanner(liveWindowSeconds = 8.0)
        val snapshot = snapshotFrom(
            startIndex = 0,
            startTimestamp = 1_000_000_000,
            count = 1040
        )
        planner.beginInspection(snapshot)

        val tooFarLeft = planner.panBy(-10 * EcgTimeIndex.NANOS_PER_SECOND, snapshot)
        assertEquals(
            snapshot.samples.first().timestampNanos,
            tooFarLeft.startTimestampNanos
        )

        val tooFarRight = planner.panBy(
            20 * EcgTimeIndex.NANOS_PER_SECOND,
            snapshot
        )
        assertEquals(
            snapshot.samples.last().timestampNanos,
            tooFarRight.endTimestampNanos
        )
    }

    @Test
    fun `pinch zoom changes duration and is clamped`() {
        val planner = EcgViewportPlanner(liveWindowSeconds = 8.0)
        val snapshot = snapshotFrom(
            startIndex = 0,
            startTimestamp = 1_000_000_000,
            count = 1040
        )
        planner.beginInspection(snapshot)

        val zoomedIn = planner.pinchZoom(
            scale = 4f,
            anchorFraction = 0.5f,
            snapshot = snapshot
        )
        assertEquals(
            (2.0 * EcgTimeIndex.NANOS_PER_SECOND).toLong(),
            zoomedIn.durationNanos
        )

        val zoomedOut = planner.pinchZoom(
            scale = 0.01f,
            anchorFraction = 0.5f,
            snapshot = snapshot
        )
        assertEquals(
            (60.0 * EcgTimeIndex.NANOS_PER_SECOND).toLong(),
            zoomedOut.durationNanos
        )
    }

    private fun snapshotFrom(
        startIndex: Long,
        startTimestamp: Long,
        count: Int
    ): EcgBufferSnapshot {
        val timing = EcgTimeIndex()
        val samples = (0 until count).map { offset ->
            val index = startIndex + offset
            EcgBufferSample(
                sampleIndex = index,
                timestampNanos = timing.timestampAt(
                    EcgContinuitySegment(startIndex, startTimestamp),
                    index
                ),
                voltage = 100
            )
        }
        return EcgBufferSnapshot(
            samples = samples,
            discontinuities = emptyList(),
            segments = listOf(
                EcgContinuitySegment(startIndex, startTimestamp)
            ),
            oldestSampleIndex = startIndex,
            newestSampleIndex = startIndex + count - 1
        )
    }
}
