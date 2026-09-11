package com.instadirect.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val hasCookies = context.getSharedPreferences(PollingService.PREFS, Context.MODE_PRIVATE)
            .getString(PollingService.KEY_COOKIES, null) != null
        if (hasCookies) {
            Log.d("BootReceiver", "Boot complete, restarting PollingService")
            ContextCompat.startForegroundService(context, Intent(context, PollingService::class.java))
        }
    }
}
