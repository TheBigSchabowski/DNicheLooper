package com.example.dnichelooper.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.dnichelooper.MainActivity

/**
 * Foreground service that pins the process while the engine runs, so audio
 * survives screen-off and app switches. The engine itself lives in native
 * code as a singleton; this service only holds the mediaPlayback|microphone
 * foreground types — the microphone type is what lets Android keep feeding
 * us USB input while the app is in the background.
 */
class EngineService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Audio engine", NotificationManager.IMPORTANCE_LOW)
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("DNicheLooper")
            .setContentText("Audio engine running")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()

        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "engine"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            // Starting a foreground service from the background is forbidden
            // on Android 12+; the engine keeps running either way as long as
            // the process lives, so a refused start is not fatal.
            runCatching {
                context.startForegroundService(Intent(context, EngineService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EngineService::class.java))
        }
    }
}
