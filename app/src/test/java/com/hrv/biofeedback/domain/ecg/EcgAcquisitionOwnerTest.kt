package com.hrv.biofeedback.domain.ecg

import com.hrv.biofeedback.domain.model.AccSample
import com.hrv.biofeedback.domain.model.BleDevice
import com.hrv.biofeedback.domain.model.ConnectionState
import com.hrv.biofeedback.domain.model.EcgSample
import com.hrv.biofeedback.domain.model.HrSample
import com.hrv.biofeedback.domain.repository.HrDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class EcgAcquisitionOwnerTest {

    @Test
    fun `start publishes polar ECG and native HR without HRV processing`() = runTest {
        val dataSource = FakeHrDataSource(
            ecgSamples = listOf(
                EcgSample(1_000_000_000, 100),
                EcgSample(1_007_692_308, 120)
            ),
            hrSamples = listOf(HrSample(hr = 67, rrsMs = emptyList()))
        )
        val owner = EcgAcquisitionOwner(dataSource, this)

        owner.start()
        advanceUntilIdle()

        assertEquals(EcgAcquisitionState.Streaming, owner.sessionState.value.acquisition)
        assertEquals(67, owner.sessionState.value.heartRateBpm)
        assertEquals(2, owner.snapshot.value.samples.size)
        assertEquals(100, owner.snapshot.value.samples[0].voltage)
        assertEquals(120, owner.snapshot.value.samples[1].voltage)
        owner.close()
    }

    @Test
    fun `repeated start does not create duplicate ECG jobs`() = runTest {
        val dataSource = FakeHrDataSource(
            ecgSamples = listOf(EcgSample(1_000_000_000, 100)),
            hrSamples = emptyList()
        )
        val owner = EcgAcquisitionOwner(dataSource, this)

        owner.start()
        owner.start()
        advanceUntilIdle()

        assertEquals(1, dataSource.ecgSubscriptionCount)
        owner.close()
    }

    @Test
    fun `stop ends acquisition and preserves snapshot`() = runTest {
        val dataSource = FakeHrDataSource(
            ecgSamples = listOf(
                EcgSample(1_000_000_000, 100),
                EcgSample(1_007_692_308, 120)
            )
        )
        val owner = EcgAcquisitionOwner(dataSource, this)

        owner.start()
        advanceUntilIdle()
        owner.stop()

        assertEquals(EcgAcquisitionState.Stopped, owner.sessionState.value.acquisition)
        assertEquals(EcgPlotState.STOPPED, owner.sessionState.value.plot)
        assertEquals(2, owner.snapshot.value.samples.size)
        owner.close()
    }

    @Test
    fun `pause and resume only change plot state`() = runTest {
        val dataSource = FakeHrDataSource(
            ecgSamples = listOf(EcgSample(1_000_000_000, 100))
        )
        val owner = EcgAcquisitionOwner(dataSource, this)

        owner.start()
        advanceUntilIdle()
        owner.pausePlot()
        assertEquals(EcgPlotState.PAUSED, owner.sessionState.value.plot)
        assertTrue(owner.sessionState.value.acquisition is EcgAcquisitionState.Streaming)

        owner.resumePlot()
        assertEquals(EcgPlotState.LIVE, owner.sessionState.value.plot)
        owner.close()
    }

    @Test
    fun `gesture pauses live plot and stopped state stays stopped`() = runTest {
        val dataSource = FakeHrDataSource(
            ecgSamples = listOf(EcgSample(1_000_000_000, 100))
        )
        val owner = EcgAcquisitionOwner(dataSource, this)

        owner.start()
        advanceUntilIdle()
        owner.pausePlotFromGesture()
        assertEquals(EcgPlotState.PAUSED, owner.sessionState.value.plot)

        owner.stop()
        owner.pausePlotFromGesture()
        assertEquals(EcgPlotState.STOPPED, owner.sessionState.value.plot)
        owner.close()
    }

    @Test
    fun `ecg stream completion does not produce duplicate ownership`() = runTest {
        val dataSource = FakeHrDataSource(
            ecgSamples = listOf(EcgSample(1_000_000_000, 100))
        )
        val owner = EcgAcquisitionOwner(dataSource, this)

        owner.start()
        advanceUntilIdle()
        owner.start()

        assertEquals(1, dataSource.ecgSubscriptionCount)
        owner.close()
    }

    @Test
    fun `record toggle includes buffer and stops without stopping acquisition`() = runTest {
        val directory = Files.createTempDirectory("ecg-stage-5").toFile()
        val dataSource = FakeHrDataSource(
            ecgSamples = listOf(
                EcgSample(1_000_000_000, 100),
                EcgSample(1_007_692_308, 200)
            )
        )
        val owner = EcgAcquisitionOwner(dataSource, this, directory)

        owner.start()
        advanceUntilIdle()
        owner.toggleRecording(1_735_689_123_456)

        assertTrue(owner.sessionState.value.recording is EcgRecordingState.Recording)
        assertTrue(owner.sessionState.value.acquisition is EcgAcquisitionState.Streaming)

        owner.toggleRecording()

        assertEquals(EcgRecordingState.Idle, owner.sessionState.value.recording)
        assertTrue(owner.sessionState.value.acquisition is EcgAcquisitionState.Streaming)
        assertTrue(directory.listFiles()?.isNotEmpty() == true)
        directory.deleteRecursively()
        owner.close()
    }

    @Test
    fun `stop acquisition also finalizes active recording`() = runTest {
        val directory = Files.createTempDirectory("ecg-stage-5-stop").toFile()
        val dataSource = FakeHrDataSource(
            ecgSamples = listOf(EcgSample(1_000_000_000, 100))
        )
        val owner = EcgAcquisitionOwner(dataSource, this, directory)

        owner.start()
        advanceUntilIdle()
        owner.toggleRecording(1_735_689_123_456)
        owner.stop()

        assertEquals(EcgAcquisitionState.Stopped, owner.sessionState.value.acquisition)
        assertEquals(EcgRecordingState.Idle, owner.sessionState.value.recording)
        assertTrue(directory.listFiles()?.isNotEmpty() == true)
        directory.deleteRecursively()
        owner.close()
    }

    private class FakeHrDataSource(
        private val ecgSamples: List<EcgSample>,
        private val hrSamples: List<HrSample> = emptyList()
    ) : HrDataSource {
        override val connectionState = MutableStateFlow<ConnectionState>(
            ConnectionState.Connected("polar", "Polar H10")
        )
        override val batteryLevel = MutableStateFlow(100)
        var ecgSubscriptionCount = 0

        override fun scanForDevices(): Flow<BleDevice> = flowOf()
        override suspend fun stopScan() = Unit
        override suspend fun connect(deviceId: String) = Unit
        override suspend fun disconnect() = Unit
        override fun streamHr(): Flow<HrSample> = flowOf(*hrSamples.toTypedArray())
        override fun streamEcg(): Flow<EcgSample> {
            ecgSubscriptionCount++
            return flowOf(*ecgSamples.toTypedArray())
        }
        override fun streamAcc(): Flow<AccSample> = flowOf()
    }
}
