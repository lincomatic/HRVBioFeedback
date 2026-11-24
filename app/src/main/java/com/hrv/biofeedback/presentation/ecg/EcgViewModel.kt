package com.hrv.biofeedback.presentation.ecg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hrv.biofeedback.domain.ecg.EcgPlotState
import com.hrv.biofeedback.domain.ecg.EcgSessionCoordinator
import com.hrv.biofeedback.domain.ecg.EcgSessionState
import com.hrv.biofeedback.domain.ecg.EcgBufferSnapshot
import com.hrv.biofeedback.domain.ecg.EcgViewport
import com.hrv.biofeedback.domain.model.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class EcgViewModel @Inject constructor(
    private val coordinator: EcgSessionCoordinator
) : ViewModel() {
    val sessionState: StateFlow<EcgSessionState>
        get() = coordinator.sessionState

    val snapshot: StateFlow<EcgBufferSnapshot>
        get() = coordinator.snapshot

    val connectionState: StateFlow<ConnectionState>
        get() = coordinator.connectionState

    private val _viewport = MutableStateFlow(
        EcgViewport(
            startTimestampNanos = 0,
            endTimestampNanos = 8_000_000_000
        )
    )
    val viewport: StateFlow<EcgViewport>
        get() = _viewport

    fun start() = coordinator.start()

    fun stop() = coordinator.stop()

    fun togglePlot() {
        val state = sessionState.value.plot
        if (state == EcgPlotState.PAUSED) {
            coordinator.resumePlot()
        } else if (state == EcgPlotState.LIVE) {
            coordinator.pausePlot()
        }
    }

    fun pauseFromGesture() = coordinator.pausePlotFromGesture()

    fun toggleRecording() = coordinator.toggleRecording()

    fun updateViewport(viewport: EcgViewport) {
        _viewport.value = viewport
    }
}
