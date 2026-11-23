package com.hrv.biofeedback.presentation.ecg

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hrv.biofeedback.data.ble.SessionForegroundService
import com.hrv.biofeedback.domain.ecg.EcgAcquisitionState
import com.hrv.biofeedback.domain.ecg.EcgPlotState
import com.hrv.biofeedback.domain.ecg.EcgRecordingState
import com.hrv.biofeedback.domain.ecg.EcgViewportPlanner
import com.hrv.biofeedback.presentation.common.EcgChart

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun EcgScreen(
    onBack: () -> Unit,
    viewModel: EcgViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val plotViewport by viewModel.viewport.collectAsStateWithLifecycle()
    val viewportPlanner = remember { EcgViewportPlanner() }
    val configuration = LocalConfiguration.current
    val isLandscape =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(isLandscape) {
        viewportPlanner.setOrientation(isLandscape)
    }

    LaunchedEffect(sessionState.plot, snapshot) {
        when (sessionState.plot) {
            EcgPlotState.LIVE ->
                viewModel.updateViewport(
                    viewportPlanner.liveViewport(
                        snapshot,
                        sessionState.heartRateBpm,
                        sessionState.latestRrIntervalMs
                    )
                )
            EcgPlotState.PAUSED,
            EcgPlotState.STOPPED ->
                viewModel.updateViewport(
                    viewportPlanner.beginInspection(snapshot)
                )
        }
    }

    DisposableEffect(Unit) {
        startEcgForegroundService(context)
        viewModel.start()
        onDispose {
            // Acquisition and recording deliberately remain owned by the singleton
            // service/coordinator until Stop or process shutdown.
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ECG") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    val streaming =
                        sessionState.acquisition is EcgAcquisitionState.Streaming
                    Text(
                        text = if (streaming) "LIVE" else "STOPPED",
                        color = if (streaming) Color(0xFF64DB9B) else Color(0xFFB8878D),
                        modifier = Modifier.padding(end = 16.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            )
        }
    ) { padding ->
        if (isLandscape) {
            LandscapeEcgContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                sessionState = sessionState,
                snapshot = snapshot,
                viewport = plotViewport,
                onPauseResume = viewModel::togglePlot,
                onStop = {
                    viewModel.stop()
                    stopEcgForegroundService(context)
                },
                onRecord = viewModel::toggleRecording,
                onGestureStart = viewModel::pauseFromGesture,
                onPan = { pixels ->
                    val density = 1f
                    val viewportDuration = plotViewport.durationNanos
                    val delta = (-pixels / 1000f * density *
                        viewportDuration).toLong()
                    viewModel.updateViewport(
                        viewportPlanner.panBy(delta, snapshot)
                    )
                },
                onPinch = { scale, anchor ->
                    viewModel.updateViewport(
                        viewportPlanner.pinchZoom(scale, anchor, snapshot)
                    )
                }
            )
        } else {
            PortraitEcgContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                sessionState = sessionState,
                snapshot = snapshot,
                viewport = plotViewport,
                onPauseResume = viewModel::togglePlot,
                onStop = {
                    viewModel.stop()
                    stopEcgForegroundService(context)
                },
                onRecord = viewModel::toggleRecording,
                onGestureStart = viewModel::pauseFromGesture,
                onPan = { pixels ->
                    val viewportDuration = plotViewport.durationNanos
                    val delta = (-pixels / 1000f * viewportDuration).toLong()
                    viewModel.updateViewport(
                        viewportPlanner.panBy(delta, snapshot)
                    )
                },
                onPinch = { scale, anchor ->
                    viewModel.updateViewport(
                        viewportPlanner.pinchZoom(scale, anchor, snapshot)
                    )
                }
            )
        }
    }
}

@Composable
private fun PortraitEcgContent(
    modifier: Modifier,
    sessionState: com.hrv.biofeedback.domain.ecg.EcgSessionState,
    snapshot: com.hrv.biofeedback.domain.ecg.EcgBufferSnapshot,
    viewport: com.hrv.biofeedback.domain.ecg.EcgViewport,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onRecord: () -> Unit,
    onGestureStart: () -> Unit,
    onPan: (Float) -> Unit,
    onPinch: (Float, Float) -> Unit
) {
    Column(
        modifier = modifier
            .background(Color(0xFF121920))
            .padding(12.dp)
    ) {
        EcgChart(
            snapshot = snapshot,
            viewport = viewport,
            heartRateBpm = sessionState.heartRateBpm,
            isLive = sessionState.plot == EcgPlotState.LIVE,
            onGestureStart = onGestureStart,
            onHorizontalPan = onPan,
            onPinch = onPinch,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFFF4FAFB))
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EcgControl(
                label = if (sessionState.plot == EcgPlotState.PAUSED) "Resume" else "Pause",
                icon = if (sessionState.plot == EcgPlotState.PAUSED)
                    Icons.Filled.PlayArrow else Icons.Filled.Pause,
                color = Color(0xFF1B3E52),
                modifier = Modifier.weight(1f),
                onClick = onPauseResume,
                enabled = sessionState.acquisition !is EcgAcquisitionState.Stopped
            )
            EcgControl(
                label = "Stop",
                icon = Icons.Filled.Stop,
                color = Color(0xFF46262D),
                modifier = Modifier.weight(1f),
                onClick = onStop
            )
            EcgControl(
                label = if (sessionState.recording is EcgRecordingState.Recording)
                    "Stop Rec" else "Record",
                icon = Icons.Filled.Stop,
                color = Color(0xFF183F34),
                modifier = Modifier.weight(1f),
                onClick = onRecord,
                enabled = sessionState.acquisition !is EcgAcquisitionState.Stopped
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        EcgInfoText(sessionState)
    }
}

@Composable
private fun LandscapeEcgContent(
    modifier: Modifier,
    sessionState: com.hrv.biofeedback.domain.ecg.EcgSessionState,
    snapshot: com.hrv.biofeedback.domain.ecg.EcgBufferSnapshot,
    viewport: com.hrv.biofeedback.domain.ecg.EcgViewport,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onRecord: () -> Unit,
    onGestureStart: () -> Unit,
    onPan: (Float) -> Unit,
    onPinch: (Float, Float) -> Unit
) {
    Row(
        modifier = modifier
            .background(Color(0xFF121920))
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.width(180.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EcgControl(
                label = if (sessionState.plot == EcgPlotState.PAUSED) "Resume" else "Pause",
                icon = if (sessionState.plot == EcgPlotState.PAUSED)
                    Icons.Filled.PlayArrow else Icons.Filled.Pause,
                color = Color(0xFF1B3E52),
                modifier = Modifier.fillMaxWidth(),
                onClick = onPauseResume,
                enabled = sessionState.acquisition !is EcgAcquisitionState.Stopped
            )
            EcgControl(
                label = "Stop",
                icon = Icons.Filled.Stop,
                color = Color(0xFF46262D),
                modifier = Modifier.fillMaxWidth(),
                onClick = onStop
            )
            EcgControl(
                label = if (sessionState.recording is EcgRecordingState.Recording)
                    "Stop Rec" else "Record",
                icon = Icons.Filled.Stop,
                color = Color(0xFF183F34),
                modifier = Modifier.fillMaxWidth(),
                onClick = onRecord,
                enabled = sessionState.acquisition !is EcgAcquisitionState.Stopped
            )
            EcgInfoText(sessionState)
        }

        Spacer(modifier = Modifier.width(12.dp))

        EcgChart(
            snapshot = snapshot,
            viewport = viewport,
            heartRateBpm = sessionState.heartRateBpm,
            isLive = sessionState.plot == EcgPlotState.LIVE,
            onGestureStart = onGestureStart,
            onHorizontalPan = onPan,
            onPinch = onPinch,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xFFF4FAFB))
        )
    }
}

@Composable
private fun EcgControl(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(72.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            disabledContainerColor = color.copy(alpha = 0.45f)
        )
    ) {
        Icon(icon, contentDescription = label)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun EcgInfoText(sessionState: com.hrv.biofeedback.domain.ecg.EcgSessionState) {
    val recording = sessionState.recording is EcgRecordingState.Recording
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "130 samples/sec",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFE8EDF2)
        )
        Text(
            text = if (recording) "Buffer: 60 seconds • Recording" else "Buffer: 60 seconds",
            style = MaterialTheme.typography.labelSmall,
            color = if (recording) Color(0xFF64DB9B) else Color(0xFF8F9CA8)
        )
    }
}

private fun startEcgForegroundService(context: Context) {
    val intent = Intent(context, SessionForegroundService::class.java).apply {
        action = SessionForegroundService.ACTION_START_ECG
    }
    context.startForegroundService(intent)
}

private fun stopEcgForegroundService(context: Context) {
    val intent = Intent(context, SessionForegroundService::class.java).apply {
        action = SessionForegroundService.ACTION_STOP_ECG
    }
    context.startService(intent)
}
