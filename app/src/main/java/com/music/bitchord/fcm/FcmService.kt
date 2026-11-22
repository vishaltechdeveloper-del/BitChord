package com.music.bitchord.fcm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * Push notifications from the SHORTY PLAY backend (Firebase project
 * shorty-play). Sends this device's FCM token to the backend on every app
 * open and shows the backend's announcements (data-only messages) on the
 * high-importance channel, so they appear on the lock screen.
 *
 * Port of the 1.9.43 FcmService. Silent: no Toasts, no UI, no Settings
 * entry. All network runs off the main thread, and every failure is
 * swallowed — push must never crash the app or delay startup.
 */
class FcmService : FirebaseMessagingService() {

    override fun onCreate() {
        runCatching { FirebaseApp.initializeApp(this) }
        runCatching { super.onCreate() }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        try {
            var title = msg.notification?.title
            var body = msg.notification?.body
            if (title == null) title = msg.data["title"]
            if (body == null) body = msg.data["body"]
            if (title.isNullOrEmpty() && body.isNullOrEmpty()) return
            showNotification(title, body)
        } catch (_: Throwable) {
        }
    }

    override fun onNewToken(token: String) {
        try {
            sendToken(this, token)
        } catch (_: Throwable) {
        }
    }

    private fun showNotification(title: String?, body: String?) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Announcements", NotificationManager.IMPORTANCE_HIGH)
                .apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC },
        )
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(ctx.applicationInfo.icon)
            .setContentTitle(title?.takeIf { it.isNotEmpty() } ?: "SHORTY PLAY")
            .setContentText(body ?: "")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { launch ->
            launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            builder.setContentIntent(
                PendingIntent.getActivity(
                    ctx, 0, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), builder.build())
    }

    companion object {
        private const val CHANNEL_ID = "sp_announce_high"
        private const val BASE_URL = "https://zeuvia.vishaltech.site"
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 3000L

        /**
         * Call once from the Application's onCreate. Returns immediately;
         * the token fetch runs on its own callbacks. Does not ask for the
         * notification permission — MainActivity already does that.
         */
        fun init(context: Context) {
            try {
                fetchTokenWithRetry(context.applicationContext, 0)
            } catch (_: Throwable) {
            }
        }

        private fun fetchTokenWithRetry(ctx: Context, attempt: Int) {
            try {
                // No FirebaseInitProvider in the manifest (it crashed the
                // 1.9.43 launch), so init Firebase here, lazily.
                if (FirebaseApp.getApps(ctx).isEmpty()) {
                    FirebaseApp.initializeApp(ctx)
                }
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    try {
                        if (task.isSuccessful && task.result != null) {
                            sendToken(ctx, task.result)
                        } else if (attempt < MAX_ATTEMPTS) {
                            retryLater(ctx, attempt)
                        }
                    } catch (_: Throwable) {
                        if (attempt < MAX_ATTEMPTS) retryLater(ctx, attempt)
                    }
                }
            } catch (_: Throwable) {
                if (attempt < MAX_ATTEMPTS) retryLater(ctx, attempt)
            }
        }

        private fun retryLater(ctx: Context, attempt: Int) {
            try {
                Handler(Looper.getMainLooper()).postDelayed(
                    { fetchTokenWithRetry(ctx, attempt + 1) },
                    RETRY_DELAY_MS,
                )
            } catch (_: Throwable) {
            }
        }

        private fun sendToken(ctx: Context, token: String) {
            if (token.isEmpty()) return
            Thread {
                try {
                    val deviceId = Settings.Secure.getString(
                        ctx.contentResolver,
                        Settings.Secure.ANDROID_ID,
                    ) ?: ""
                    val body = JSONObject()
                        .put("deviceId", deviceId)
                        .put("token", token)
                        .toString()
                        .toByteArray(StandardCharsets.UTF_8)
                    val conn = URL("$BASE_URL/api/fcm-token").openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 10000
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body) }
                    conn.responseCode
                    conn.disconnect()
                } catch (_: Throwable) {
                }
            }.start()
        }
    }
}
