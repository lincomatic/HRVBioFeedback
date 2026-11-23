package com.hrv.biofeedback.presentation.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrv.biofeedback.domain.ecg.EcgBufferSnapshot
import com.hrv.biofeedback.domain.ecg.EcgViewport

@Composable
fun EcgChart(
    snapshot: EcgBufferSnapshot,
    viewport: EcgViewport,
    heartRateBpm: Int?,
    modifier: Modifier = Modifier,
    isLive: Boolean = true,
    onGestureStart: (() -> Unit)? = null,
    onHorizontalPan: ((Float) -> Unit)? = null,
    onPinch: ((Float, Float) -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()

    val gestureModifier = modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            detectTransformGestures { centroid, _, zoom, _ ->
                onGestureStart?.invoke()
                onPinch?.invoke(zoom, centroid.x / size.width)
            }
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { onGestureStart?.invoke() },
                onHorizontalDrag = { _, dragAmount ->
                    onHorizontalPan?.invoke(dragAmount)
                }
            )
        }

    Canvas(modifier = gestureModifier) {
        val leftLabelWidth = 42.dp.toPx()
        val rightLabelWidth = 8.dp.toPx()
        val top = 14.dp.toPx()
        val bottom = 28.dp.toPx()
        val chartLeft = leftLabelWidth
        val chartRight = size.width - rightLabelWidth
        val chartWidth = (chartRight - chartLeft).coerceAtLeast(1f)
        val chartHeight = (size.height - top - bottom).coerceAtLeast(1f)
        val chartBottom = top + chartHeight

        val gridColor = Color(0xFFB7CCD4)
        val minorGridColor = Color(0xFFDCE8ED)
        val blue = Color(0xFF1687E8)
        val red = Color(0xFFE5394F)

        val voltageMin = -1_000_000
        val voltageMax = 1_000_000
        val voltageRange = (voltageMax - voltageMin).toFloat()

        val majorX = (chartWidth / 8f)
        val majorY = (chartHeight / 8f)
        for (i in 0..8) {
            val x = chartLeft + majorX * i
            val y = top + majorY * i
            drawLine(
                gridColor,
                Offset(x, top),
                Offset(x, chartBottom),
                1.dp.toPx()
            )
            drawLine(
                gridColor,
                Offset(chartLeft, y),
                Offset(chartRight, y),
                1.dp.toPx()
            )
        }

        for (i in 0 until 40) {
            val x = chartLeft + chartWidth * i / 40f
            drawLine(
                minorGridColor,
                Offset(x, top),
                Offset(x, chartBottom),
                0.5.dp.toPx()
            )
        }
        for (i in 0 until 40) {
            val y = top + chartHeight * i / 40f
            drawLine(
                minorGridColor,
                Offset(chartLeft, y),
                Offset(chartRight, y),
                0.5.dp.toPx()
            )
        }

        val labelStyle = TextStyle(
            fontSize = 11.sp,
            color = Color(0xFF788790)
        )
        val axisLabels = listOf("0.9", "0.6", "0.3", "0", "-0.3", "-0.6", "-0.9")
        axisLabels.forEachIndexed { index, label ->
            val y = top + chartHeight * index / (axisLabels.size - 1)
            val result = textMeasurer.measure(label, style = labelStyle)
            drawText(
                result,
                topLeft = Offset(
                    chartLeft - result.size.width - 5.dp.toPx(),
                    y - result.size.height / 2
                )
            )
        }

        heartRateBpm?.let { bpm ->
            val hrText = textMeasurer.measure(
                "$bpm",
                style = TextStyle(
                    fontSize = 46.sp,
                    color = red
                )
            )
            val bpmText = textMeasurer.measure(
                "BPM",
                style = TextStyle(
                    fontSize = 14.sp,
                    color = red
                )
            )
            drawText(
                hrText,
                topLeft = Offset(chartLeft + 12.dp.toPx(), top + 8.dp.toPx())
            )
            drawText(
                bpmText,
                topLeft = Offset(
                    chartLeft + 12.dp.toPx() + hrText.size.width + 6.dp.toPx(),
                    top + 16.dp.toPx()
                )
            )
        }

        if (snapshot.isEmpty) return@Canvas

        val viewportDuration = viewport.durationNanos.toDouble()
        val discontinuityIndices = snapshot.discontinuities
            .associate { it.beforeSampleIndex to it.afterSampleIndex }
        val segmentStartIndices = snapshot.segments
            .map { it.startSampleIndex }
            .toSet()
        var path = Path()
        var pathStarted = false

        snapshot.samples.forEachIndexed { _, sample ->
            val gapAfter = discontinuityIndices[sample.sampleIndex]
            val reconnectAtSample = segmentStartIndices.contains(sample.sampleIndex)
            if (gapAfter != null || reconnectAtSample) {
                if (pathStarted) {
                    drawPath(
                        path = path,
                        color = blue,
                        style = Stroke(
                            width = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
                path = Path()
                pathStarted = false
            }

            val timestampOffset =
                sample.timestampNanos - viewport.startTimestampNanos
            if (timestampOffset < 0 || timestampOffset > viewportDuration) return@forEachIndexed

            val xFraction = timestampOffset.toDouble() / viewportDuration
            val x = chartLeft + chartWidth * xFraction.toFloat()
            val normalized = (
                (sample.voltage.toFloat() - voltageMin) / voltageRange
                ).coerceIn(0f, 1f)
            val y = top + chartHeight * (1f - normalized)

            if (!pathStarted) {
                path.moveTo(x, y)
                pathStarted = true
            } else {
                path.lineTo(x, y)
            }
        }

        if (pathStarted) {
            drawPath(
                path = path,
                color = blue,
                style = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}
