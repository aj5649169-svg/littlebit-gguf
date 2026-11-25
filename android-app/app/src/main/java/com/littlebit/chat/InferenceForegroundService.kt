package com.littlebit.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

class InferenceForegroundService : Service() {
    companion object {
        private const val CHANNEL = "local_inference"
        private const val ID = 11
        @Volatile private var operation: (() -> Unit)? = null
        private var revision = 0

        @Synchronized fun begin(context: Context, stage: String, cancel: () -> Unit) {
            revision++
            operation = cancel
            val request = Intent(context, InferenceForegroundService::class.java)
                .putExtra("stage", stage).putExtra("revision", revision)
            try {
                context.startForegroundService(request)
            } catch (e: RuntimeException) {
                android.util.Log.w("LittleBitChat", "Foreground notification unavailable", e)
            }
        }

        @Synchronized fun finish(context: Context) {
            operation = null
            context.stopService(Intent(context, InferenceForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "On-device inference", NotificationManager.IMPORTANCE_LOW))
        val version = intent?.getIntExtra("revision", -1) ?: -1
        if (intent?.action == "STOP") {
            if (version == revision) operation?.invoke()
            if (operation == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val stop = Intent(this, InferenceForegroundService::class.java).setAction("STOP").putExtra("revision", version)
        val action = PendingIntent.getService(this, version, stop, PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("LittleBit Chat")
            .setContentText(intent?.getStringExtra("stage")).setOnlyAlertOnce(true)
            .setOngoing(true).setContentIntent(open).addAction(Notification.Action.Builder(null, "Stop", action).build()).build())
        if (operation == null) stopSelfResult(startId)
        return START_NOT_STICKY
    }
}
