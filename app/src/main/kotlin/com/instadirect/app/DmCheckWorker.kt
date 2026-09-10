package com.instadirect.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class DmCheckWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cookies = prefs.getString(KEY_COOKIES, null) ?: return@withContext Result.success()

            val csrfToken = cookies.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("csrftoken=") }
                ?.substringAfter("csrftoken=")
                ?: return@withContext Result.success()

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
            }

            if (conn.responseCode != 200) return@withContext Result.success()

            val json = JSONObject(conn.inputStream.bufferedReader().readText())

            // Try both response shapes Instagram has used
            val unseenCount = json.optJSONObject("data")
                ?.optJSONObject("inbox")
                ?.optInt("unseen_count", 0)
                ?: json.optJSONObject("inbox")
                    ?.optInt("unseen_count", 0)
                ?: 0

            val lastCount = prefs.getInt(KEY_LAST_UNSEEN, 0)
            if (unseenCount > lastCount) showNotification(unseenCount)
            prefs.edit().putInt(KEY_LAST_UNSEEN, unseenCount).apply()

        } catch (_: Exception) {}
        Result.success()
    }

    private fun showNotification(count: Int) {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pending = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (count == 1) "1 unread message" else "$count unread messages"
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Instagram DM")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notification)
    }

    companion object {
        const val PREFS = "instamsg"
        const val KEY_COOKIES = "cookies"
        const val KEY_LAST_UNSEEN = "last_unseen"
        const val CHANNEL_ID = "dm_channel"
        const val NOTIF_ID = 1001
        const val IG_APP_ID = "936619743392459"
        const val INBOX_API =
            "https://www.instagram.com/api/v1/direct_v2/inbox/?visual_message_return_type=unseen&thread_message_limit=1&persistentBadging=true&limit=20"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Mobile Safari/537.36"
    }
}
