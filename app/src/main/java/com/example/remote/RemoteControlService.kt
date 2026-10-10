package com.example.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.MainActivity
import com.example.R

/**
 * Keeps the localhost control server alive while the browser is in the background (for example
 * while Termux is in front). Android freezes background apps that have no foreground service.
 */
class RemoteControlService : Service() {

    private var server: RemoteControlServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RemoteControl.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        val port = RemoteControl.port(this)
        goForeground(port)
        if (server?.port != port) {
            server?.stop()
            startServer(port)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        RemoteControl.status = RemoteServerStatus(running = false)
        super.onDestroy()
    }

    private fun startServer(port: Int) {
        val appContext = applicationContext
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val api = RemoteApi(tokenProvider = { RemoteControl.token(appContext) }, appVersion = version)
        lateinit var created: RemoteControlServer
        created = RemoteControlServer(
            port = port,
            handler = api::handle,
            onStarted = {
                MainThread.post {
                    if (server === created) RemoteControl.status = RemoteServerStatus(running = true, port = port)
                }
            },
            onStopped = { error ->
                MainThread.post {
                    if (server === created) {
                        RemoteControl.status = RemoteServerStatus(running = false, port = port, error = error)
                        if (error != null) updateNotification("Not running: $error")
                    }
                }
            },
        )
        server = created
        RemoteControl.status = RemoteServerStatus(running = false, port = port)
        created.start()
    }

    private fun goForeground(port: Int) {
        val notification = buildNotification("Listening on http://127.0.0.1:$port")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RemoteControlService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_remote_control)
            .setContentTitle("Browser remote control is on")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Turn off", stop).build())
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Remote control", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while scripts on this device can control the browser"
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "remote_control"
        const val NOTIFICATION_ID = 7001
        const val ACTION_STOP = "com.example.remote.STOP"
    }
}
