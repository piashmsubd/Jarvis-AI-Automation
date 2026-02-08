package com.jarvis.ai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.ai.util.PreferenceManager
import com.jarvis.ai.voice.WakeWordService

/**
 * BootReceiver — Restarts the wake word service when the device boots.
 *
 * Only starts the service if:
 *   1. A Picovoice access key is configured
 *   2. Wake word was enabled before the reboot
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i("BootReceiver", "Device boot completed — checking wake word config")

        val prefManager = PreferenceManager(context)
        if (prefManager.wakeWordEnabled && prefManager.picovoiceAccessKey.isNotBlank()) {
            Log.i("BootReceiver", "Starting wake word service after boot")
            WakeWordService.start(context)
        }
    }
}
