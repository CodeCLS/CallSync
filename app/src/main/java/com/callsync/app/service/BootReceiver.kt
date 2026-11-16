package com.callsync.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-arms the call watcher after a reboot so the app is "always on" without the
 * user having to open it. The service starts as a non-microphone (specialUse)
 * foreground service here — mic-type foreground services may not be started from
 * a boot broadcast on Android 12+, so it only upgrades to the microphone type
 * once a call is actually being recorded.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> {
                Log.i("BootReceiver", "boot completed -> starting watcher")
                runCatching { CallRecordingService.start(context) }
                    .onFailure { Log.e("BootReceiver", "could not start watcher on boot", it) }
            }
        }
    }
}
