package com.hrv.biofeedback.data.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hrv.biofeedback.R
import com.hrv.biofeedback.domain.ecg.EcgRecordingState
import com.hrv.biofeedback.domain.ecg.EcgSessionCoordinator
import com.hrv.biofeedback.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service to keep BLE streaming alive during active sessions
 * when the app is in the background or the screen is off.
 */
@AndroidEntryPoint
class SessionForegroundService : Service() {

    @Inject
    lateinit var ecgSessionCoordinator: EcgSessionCoordinator

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val CHANNEL_ID = "hrv_session_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "START_SESSION"
        const val ACTION_STOP = "STOP_SESSION"
        const val ACTION_START_ECG = "START_ECG"
        const val ACTION_STOP_ECG = "STOP_ECG"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ECG -> {
                ecgSessionCoordinator.start()
                startForeground(
                    NOTIFICATION_ID,
                    createEcgNotification(recording = false)
                )
                observeEcgState()
            }
            ACTION_STOP_ECG -> {
                ecgSessionCoordinator.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> {
                val notification = createNotification("HRV session active", "Recording heart rate data...")
                startForeground(NOTIFICATION_ID, notification)
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HRV Session",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active HRV biofeedback session"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun observeEcgState() {
        serviceScope.launch {
            ecgSessionCoordinator.sessionState.collect { state ->
                val recording = state.recording is EcgRecordingState.Recording
                val notification = createEcgNotification(recording)
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun createEcgNotification(recording: Boolean): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (recording) {
            "Streaming 130 Hz ECG and recording..."
        } else {
            "Streaming 130 Hz ECG..."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ECG streaming")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
