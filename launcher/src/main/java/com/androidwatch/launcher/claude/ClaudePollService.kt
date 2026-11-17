package com.androidwatch.launcher.claude

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.androidwatch.launcher.R
import java.util.concurrent.Executors

/**
 * After you send a message, checks the GitHub inbox every minute (for up to an
 * hour) and vibrates with a notification when Claude replies. Stops on its own.
 */
class ClaudePollService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var nm: NotificationManager

    private val tick = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() > ClaudeInbox.pollUntil(this@ClaudePollService)) {
                stopSelf()
                return
            }
            // The chat screen polls by itself while open; don't double up (GitHub rate limit).
            if (!ClaudeActivity.isVisible) worker.execute { pollOnce(this@ClaudePollService) }
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        createChannels(this)
        startForeground(
            STATUS_ID,
            Notification.Builder(this, CH_STATUS)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("Waiting for Claude's reply")
                .setContentIntent(openChat(this))
                .setOngoing(true)
                .build(),
        )
        main.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onDestroy() {
        main.removeCallbacks(tick)
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val POLL_MS = 90_000L
        private const val STATUS_ID = 31
        private const val REPLY_ID = 32
        private const val CH_STATUS = "claude_status"
        private const val CH_REPLIES = "claude_replies"

        /** Called after a message is sent: poll for the next hour. */
        fun startPolling(context: Context) {
            ClaudeInbox.extendPolling(context, minutes = 60)
            context.startForegroundService(Intent(context, ClaudePollService::class.java))
        }

        /** Checks every project's inbox once; notifies about new replies. Blocking. */
        fun pollOnce(context: Context) {
            for (p in ClaudeProjects.all(context)) {
                val fresh = runCatching { ClaudeInbox.refresh(context, p) }.getOrNull().orEmpty()
                if (fresh.isNotEmpty() && !ClaudeActivity.isVisible) notify(context, p, fresh.last())
            }
        }

        private fun notify(context: Context, p: ClaudeProject, reply: ChatMessage) {
            createChannels(context)
            val n = Notification.Builder(context, CH_REPLIES)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("Claude · ${p.name}")
                .setContentText(reply.text)
                .setStyle(Notification.BigTextStyle().bigText(reply.text))
                .setContentIntent(openChat(context))
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(REPLY_ID, n)
        }

        private fun openChat(context: Context) = PendingIntent.getActivity(
            context, 0, Intent(context, ClaudeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_STATUS, "Waiting for Claude", NotificationManager.IMPORTANCE_MIN),
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_REPLIES, "Claude replies", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                },
            )
        }
    }
}
