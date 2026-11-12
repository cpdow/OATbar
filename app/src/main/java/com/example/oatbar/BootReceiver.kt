package com.example.oatbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (prefs.enabled && prefs.address != null && Settings.canDrawOverlays(context)) {
            context.startForegroundService(
                Intent(context, OatBarService::class.java).setAction(OatBarService.ACTION_START)
            )
        }
    }
}
