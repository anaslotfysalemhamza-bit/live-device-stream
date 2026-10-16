package com.livedevicestream.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.livedevicestream.LiveStreamApp
import com.livedevicestream.MainActivity
import com.livedevicestream.R
import com.livedevicestream.util.PermissionHelper

class StreamForegroundService : Service() {

    private val TAG = "StreamForegroundService"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopStreamingService()
            return START_NOT_STICKY
        }

        val hasScreen = intent?.getBooleanExtra(EXTRA_HAS_SCREEN, false) ?: false
        val hasCamera = intent?.getBooleanExtra(EXTRA_HAS_CAMERA, false) ?: false
        val hasAudio = intent?.getBooleanExtra(EXTRA_HAS_AUDIO, false) ?: false

        startOrUpdateForeground(hasScreen, hasCamera, hasAudio)
        return START_STICKY
    }

    private fun startOrUpdateForeground(hasScreen: Boolean, hasCamera: Boolean, hasAudio: Boolean) {
        val stopIntent = Intent(this, StreamForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activeStreams = mutableListOf<String>()
        if (hasScreen) activeStreams.add("Screen")
        if (hasCamera) activeStreams.add("Camera")
        if (hasAudio) activeStreams.add("Microphone")

        val subtext = if (activeStreams.isEmpty()) {
            getString(R.string.notification_text)
        } else {
            "Active streams: ${activeStreams.joinToString(", ")}"
        }

        val notification: Notification = NotificationCompat.Builder(this, LiveStreamApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(subtext)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openActivityPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = 0

            if (hasScreen) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            if (hasCamera && PermissionHelper.hasCameraPermission(this)) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (hasAudio && PermissionHelper.hasAudioPermission(this)) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (serviceType == 0) {
                serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }

            Log.d(TAG, "Starting foreground service with serviceType bitmask: $serviceType (screen=$hasScreen, cam=$hasCamera, mic=$hasAudio)")
            try {
                startForeground(LiveStreamApp.NOTIFICATION_ID, notification, serviceType)
            } catch (e: Exception) {
                Log.e(TAG, "Failed startForeground with type $serviceType, falling back", e)
                try {
                    startForeground(LiveStreamApp.NOTIFICATION_ID, notification)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed fallback startForeground", e2)
                }
            }
        } else {
            startForeground(LiveStreamApp.NOTIFICATION_ID, notification)
        }

        isRunning = true
        onServiceStateChanged?.invoke(true)
    }

    private fun stopStreamingService() {
        isRunning = false
        onServiceStateChanged?.invoke(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        onServiceStateChanged?.invoke(false)
    }

    companion object {
        const val ACTION_START = "com.livedevicestream.action.START"
        const val ACTION_STOP = "com.livedevicestream.action.STOP"
        const val ACTION_UPDATE = "com.livedevicestream.action.UPDATE"

        const val EXTRA_HAS_SCREEN = "extra_has_screen"
        const val EXTRA_HAS_CAMERA = "extra_has_camera"
        const val EXTRA_HAS_AUDIO = "extra_has_audio"

        var isRunning = false
            private set

        var onServiceStateChanged: ((Boolean) -> Unit)? = null

        fun start(context: Context, hasScreen: Boolean = false, hasCamera: Boolean = false, hasAudio: Boolean = false) {
            val intent = Intent(context, StreamForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HAS_SCREEN, hasScreen)
                putExtra(EXTRA_HAS_CAMERA, hasCamera)
                putExtra(EXTRA_HAS_AUDIO, hasAudio)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun update(context: Context, hasScreen: Boolean, hasCamera: Boolean, hasAudio: Boolean) {
            val intent = Intent(context, StreamForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_HAS_SCREEN, hasScreen)
                putExtra(EXTRA_HAS_CAMERA, hasCamera)
                putExtra(EXTRA_HAS_AUDIO, hasAudio)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, StreamForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
