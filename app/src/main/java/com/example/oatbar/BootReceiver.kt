package com.example.oatbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the service when the head unit powers up. Different head units announce power-up
 * differently, so several actions are accepted; all of them just call the same safe start.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> OatBarService.startIfEnabled(context)
        }
    }
}
