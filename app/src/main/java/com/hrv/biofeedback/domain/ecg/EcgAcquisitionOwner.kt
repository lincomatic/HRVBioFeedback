package com.hrv.biofeedback.domain.ecg

import com.hrv.biofeedback.domain.model.ConnectionState
import com.hrv.biofeedback.domain.repository.HrDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.File

class EcgAcquisitionOwner(
    private val hrDataSource: HrDataSource,
    scope: CoroutineScope? = null,
    recordingDirectory: File? = null
) {
    private val ownerScope = scope ?: CoroutineScope(SupervisorJob())
    private val ownsScope = scope == null
    private val buffer = EcgCircularBuffer()

    private val _sessionState = MutableStateFlow(EcgSessionState())
    val sessionState: StateFlow<EcgSessionState> = _sessionState.asStateFlow()

    private val _snapshot = MutableStateFlow(buffer.snapshot())
    val snapshot: StateFlow<EcgBufferSnapshot> = _snapshot.asStateFlow()

    private var ecgJob: Job? = null
    private var hrJob: Job? = null
    private var connectionJob: Job? = null
    private var isStarted = false
    private var firstEcgSampleInSubscription = true
    private var recordingWriter: EcgRecordingFileWriter? =
        recordingDirectory?.let(::EcgRecordingFileWriter)

    fun start() {
        if (isStarted) return
        isStarted = true
        firstEcgSampleInSubscription = true

        startConnectionObserver()
        startHrCollection()
        startEcgCollection()
        _sessionState.value = _sessionState.value.copy(
            acquisition = EcgAcquisitionState.Starting,
            plot = EcgPlotState.LIVE
        )
    }

    fun stop() {
        stopRecording()
        isStarted = false
        ecgJob?.cancel()
        hrJob?.cancel()
        connectionJob?.cancel()
        ecgJob = null
        hrJob = null
        connectionJob = null
        publishSnapshot()
        _sessionState.value = _sessionState.value.copy(
            acquisition = EcgAcquisitionState.Stopped,
            plot = EcgPlotState.STOPPED
        )
    }

    fun pausePlot() {
        _sessionState.value = _sessionState.value.copy(plot = EcgPlotState.PAUSED)
    }

    fun resumePlot() {
        if (_sessionState.value.acquisition is EcgAcquisitionState.Stopped) return
        _sessionState.value = _sessionState.value.copy(plot = EcgPlotState.LIVE)
        publishSnapshot()
    }

    fun pausePlotFromGesture() {
        if (_sessionState.value.acquisition is EcgAcquisitionState.Stopped) return
        _sessionState.value = _sessionState.value.copy(plot = EcgPlotState.PAUSED)
    }

    fun clearBuffer() {
        buffer.clear()
        publishSnapshot()
    }

    fun startRecording(recordStartMillis: Long = System.currentTimeMillis()) {
        val writer = recordingWriter
            ?: throw IllegalStateException("ECG recording directory is not configured")
        try {
            val result = writer.start(snapshot.value, recordStartMillis)
            _sessionState.value = _sessionState.value.copy(
                recording = EcgRecordingState.Recording(
                    fileName = result.fileName,
                    firstSampleTimestampNanos = result.firstSampleTimestampNanos
                )
            )
        } catch (t: Throwable) {
            _sessionState.value = _sessionState.value.copy(
                recording = EcgRecordingState.Error(
                    t.message ?: "Unable to start ECG recording"
                )
            )
        }
    }

    fun stopRecording() {
        val writer = recordingWriter ?: return
        if (!writer.isRecording) return
        try {
            writer.stop()
            _sessionState.value = _sessionState.value.copy(
                recording = EcgRecordingState.Idle
            )
        } catch (t: Throwable) {
            _sessionState.value = _sessionState.value.copy(
                recording = EcgRecordingState.Error(
                    t.message ?: "Unable to finalize ECG recording"
                )
            )
        }
    }

    fun toggleRecording(recordStartMillis: Long = System.currentTimeMillis()) {
        if (recordingWriter?.isRecording == true) {
            stopRecording()
        } else {
            startRecording(recordStartMillis)
        }
    }

    fun close() {
        stop()
        if (ownsScope) ownerScope.cancel()
    }

    private fun startConnectionObserver() {
        connectionJob?.cancel()
        connectionJob = ownerScope.launch {
            hrDataSource.connectionState.collect { connection ->
                when (connection) {
                    is ConnectionState.Error -> setAcquisitionError(connection.message)
                    ConnectionState.Disconnected -> {
                        if (_sessionState.value.acquisition is EcgAcquisitionState.Streaming) {
                            setAcquisitionError("Polar H10 disconnected")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun startHrCollection() {
        hrJob?.cancel()
        hrJob = ownerScope.launch {
            hrDataSource.streamHr()
                .catch {
                    // Native HR is optional to ECG acquisition. Keep ECG streaming alive.
                }
                .collect { sample ->
                    _sessionState.value = _sessionState.value.copy(
                        heartRateBpm = sample.hr,
                        latestRrIntervalMs = sample.rrsMs.lastOrNull()
                    )
                }
        }
    }

    private fun startEcgCollection() {
        ecgJob?.cancel()
        ecgJob = ownerScope.launch {
            try {
                hrDataSource.streamEcg()
                    .catch { throwable ->
                        setAcquisitionError(
                            throwable.message ?: "ECG stream stopped unexpectedly"
                        )
                    }
                    .collect { sample ->
                        if (firstEcgSampleInSubscription) {
                            buffer.startNewSegment(sample.timestamp)
                            firstEcgSampleInSubscription = false
                        }
                        buffer.append(sample.voltage, sample.timestamp)
                        if (_sessionState.value.acquisition !is EcgAcquisitionState.Streaming) {
                            _sessionState.value = _sessionState.value.copy(
                                acquisition = EcgAcquisitionState.Streaming
                            )
                        }
                        recordingWriter?.takeIf { it.isRecording }?.let { writer ->
                            try {
                                writer.appendLive(
                                    EcgBufferSample(
                                        sampleIndex = buffer.newestSampleIndex,
                                        timestampNanos = sample.timestamp,
                                        voltage = sample.voltage
                                    )
                                )
                            } catch (t: Throwable) {
                                _sessionState.value = _sessionState.value.copy(
                                    recording = EcgRecordingState.Error(
                                        t.message ?: "Unable to append ECG recording"
                                    )
                                )
                            }
                        }
                        publishSnapshotIfNeeded()
                    }
            } catch (throwable: Throwable) {
                setAcquisitionError(
                    throwable.message ?: "Unable to start ECG stream"
                )
            }
        }
    }

    private fun setAcquisitionError(message: String) {
        publishSnapshot()
        _sessionState.value = _sessionState.value.copy(
            acquisition = EcgAcquisitionState.Error(message)
        )
    }

    private fun publishSnapshotIfNeeded() {
        publishSnapshot()
    }

    private fun publishSnapshot() {
        _snapshot.value = buffer.snapshot()
    }
}
