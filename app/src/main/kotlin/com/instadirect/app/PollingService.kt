package com.instadirect.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PollingService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: kotlinx.coroutines.Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand (pollingJob active=${pollingJob?.isActive})")
        startForeground(FOREGROUND_NOTIF_ID, buildForegroundNotification())
        if (pollingJob?.isActive != true) {
            pollingJob = scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            checkDms()
            delay(POLL_MS)
        }
    }

    private fun checkDms() {
        try {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cookies = prefs.getString(KEY_COOKIES, null)
            if (cookies == null) {
                Log.d(TAG, "No cookies saved yet — waiting for user to log in via WebView")
                return
            }

            val csrfToken = cookies.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("csrftoken=") }
                ?.substringAfter("csrftoken=")
            if (csrfToken == null) {
                Log.w(TAG, "No csrftoken found in cookies: ${cookies.take(200)}")
                return
            }

            Log.d(TAG, "Polling inbox API…")
            val conn = URL(INBOX_API).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Cookie", cookies)
                setRequestProperty("X-CSRFToken", csrfToken)
                setRequestProperty("X-IG-App-ID", IG_APP_ID)
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Referer", "https://www.instagram.com/direct/inbox/")
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }

            val code = conn.responseCode
            Log.d(TAG, "HTTP $code")
            if (code == 401 || code == 403) {
                Log.w(TAG, "Session expired ($code), notifying user to reopen app")
                showSessionExpiredNotification()
                return
            }
            if (code != 200) {
                val error = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                Log.w(TAG, "Non-200 response ($code): ${error?.take(300)}")
                return
            }

            val body = conn.inputStream.bufferedReader().readText()
            Log.d(TAG, "Response body (first 500): ${body.take(500)}")

            val json = JSONObject(body)
            val inbox = json.optJSONObject("data")?.optJSONObject("inbox")
                ?: json.optJSONObject("inbox")

            if (inbox == null) {
                Log.w(TAG, "No inbox in response. Top-level keys: ${json.keys().asSequence().joinToString()}")
                return
            }

            val unseenCount = inbox.optInt("unseen_count", 0)
            Log.d(TAG, "unseen_count: $unseenCount")

            // Track latest message timestamp across all threads to detect new messages
            // even when unseen_count stays 0 (WebView marks messages as read)
            val threads = inbox.optJSONArray("threads") ?: JSONArray()
            var latestTs = 0L
            for (i in 0 until threads.length()) {
                val ts = threads.optJSONObject(i)
                    ?.optJSONObject("last_permanent_item")
                    ?.optLong("timestamp", 0L) ?: 0L
                if (ts > latestTs) latestTs = ts
            }
            Log.d(TAG, "Latest thread timestamp: $latestTs, threads checked: ${threads.length()}")

            val lastCount = prefs.getInt(KEY_LAST_UNSEEN, 0)
            val lastTs = prefs.getLong(KEY_LAST_TS, 0L)

            val newByCount = unseenCount > lastCount
            val newByTs = latestTs > 0 && lastTs > 0 && latestTs > lastTs

            if (newByCount || newByTs) {
                Log.d(TAG, "New DM detected (byCount=$newByCount byTs=$newByTs), firing notification")
                showDmNotification(unseenCount.coerceAtLeast(1))
            }

            prefs.edit()
                .putInt(KEY_LAST_UNSEEN, unseenCount)
                .putLong(KEY_LAST_TS, latestTs)
                .apply()

        } catch (e: Exception) {
            Log.e(TAG, "Poll failed", e)
        }
    }

    private fun showSessionExpiredNotification() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastShown = prefs.getLong(KEY_LAST_AUTH_NOTIF, 0L)
        if (System.currentTimeMillis() - lastShown < 6 * 60 * 60_000L) return // once per 6h max
        prefs.edit().putLong(KEY_LAST_AUTH_NOTIF, System.currentTimeMillis()).apply()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pending = PendingIntent.getActivity(
            this, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("InstaMsg: Session expired")
            .setContentText("Tap to reopen the app and refresh your login")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(SESSION_NOTIF_ID, notification)
    }

    private fun buildForegroundNotification() = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("InstaMsg")
        .setContentText("Watching for new DMs…")
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setSilent(true)
        .setOngoing(true)
        .build()

    private fun showDmNotification(count: Int) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (count == 1) "1 unread message" else "$count unread messages"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Instagram DM")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(DM_NOTIF_ID, notification)
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PollingService"
        private const val POLL_MS = 5 * 60_000L
        private const val FOREGROUND_NOTIF_ID = 1000
        private const val DM_NOTIF_ID = 1001
        private const val SESSION_NOTIF_ID = 1002
        const val KEY_LAST_AUTH_NOTIF = "last_auth_notif"
        const val CHANNEL_ID = "dm_channel"
        const val SERVICE_CHANNEL_ID = "service_channel"
        const val PREFS = "instamsg"
        const val KEY_COOKIES = "cookies"
        const val KEY_LAST_UNSEEN = "last_unseen"
        const val KEY_LAST_TS = "last_ts"
        const val IG_APP_ID = "936619743392459"
        const val INBOX_API =
            "https://www.instagram.com/api/v1/direct_v2/inbox/?visual_message_return_type=unseen&thread_message_limit=1&persistentBadging=true&limit=20"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Mobile Safari/537.36"
    }
}
