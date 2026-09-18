package com.example.ipixelclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.ipixelclock.settings.SettingsStore

/**
 * Brings the clock back after a reboot, when the setting says to.
 *
 * A panel on a shelf should not need someone to unlock the phone and tap an
 * icon every time the power blinks. Off by default all the same: an app that
 * launches itself on boot without being asked is a rude app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) return

        val settings = SettingsStore(context).current
        if (!settings.startOnBoot) return

        Log.i(TAG, "boot: starting the clock service")
        try {
            ClockService.start(context)
        } catch (e: Exception) {
            // Android 12+ blocks some background service starts; nothing to be
            // done here beyond not crashing the boot broadcast.
            Log.w(TAG, "could not start on boot", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
