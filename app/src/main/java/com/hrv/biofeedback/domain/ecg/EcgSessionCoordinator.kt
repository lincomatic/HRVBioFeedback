package com.hrv.biofeedback.domain.ecg

import android.content.Context
import com.hrv.biofeedback.domain.model.ConnectionState
import com.hrv.biofeedback.domain.repository.HrDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EcgSessionCoordinator @Inject constructor(
    @ApplicationContext context: Context,
    hrDataSource: HrDataSource
) {
    private val source = hrDataSource
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val externalFilesDirectory =
        context.getExternalFilesDir(null) ?: context.filesDir
    private val recordingDirectory = File(externalFilesDirectory, "ecg_data")

    private val owner = EcgAcquisitionOwner(
        hrDataSource = hrDataSource,
        scope = scope,
        recordingDirectory = recordingDirectory
    )

    val sessionState: StateFlow<EcgSessionState>
        get() = owner.sessionState

    val snapshot: StateFlow<EcgBufferSnapshot>
        get() = owner.snapshot

    val connectionState: StateFlow<ConnectionState>
        get() = source.connectionState

    fun start() = owner.start()

    fun stop() = owner.stop()

    fun pausePlot() = owner.pausePlot()

    fun resumePlot() = owner.resumePlot()

    fun pausePlotFromGesture() = owner.pausePlotFromGesture()

    fun toggleRecording(recordStartMillis: Long = System.currentTimeMillis()) =
        owner.toggleRecording(recordStartMillis)

    fun stopRecording() = owner.stopRecording()

    fun clearBuffer() = owner.clearBuffer()
}
