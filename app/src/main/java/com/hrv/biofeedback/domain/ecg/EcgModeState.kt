package com.hrv.biofeedback.domain.ecg

sealed interface EcgAcquisitionState {
    data object Idle : EcgAcquisitionState
    data object Starting : EcgAcquisitionState
    data object Streaming : EcgAcquisitionState
    data class Error(val message: String) : EcgAcquisitionState
    data object Stopped : EcgAcquisitionState
}

enum class EcgPlotState {
    LIVE,
    PAUSED,
    STOPPED
}

sealed interface EcgRecordingState {
    data object Idle : EcgRecordingState
    data class Recording(
        val fileName: String,
        val firstSampleTimestampNanos: Long
    ) : EcgRecordingState

    data class Error(val message: String) : EcgRecordingState
}

data class EcgSessionState(
    val acquisition: EcgAcquisitionState = EcgAcquisitionState.Idle,
    val plot: EcgPlotState = EcgPlotState.LIVE,
    val recording: EcgRecordingState = EcgRecordingState.Idle,
    val heartRateBpm: Int? = null,
    val latestRrIntervalMs: Int? = null
)
