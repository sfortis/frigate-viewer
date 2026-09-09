package com.asksakis.freegate.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Restarts [FrigateAlertService] after device boot and after the app is updated, but only
 * when the user actually has notifications enabled. Gating here rather than in the manifest
 * means the receiver does literally nothing when the feature is off, with no service start
 * and no wake locks.
 */
class FrigateBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Both actions are exemptions from the background foreground-service restriction,
        // which is what makes starting the listener from here legal at all.
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val notificationsEnabled = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("notifications_enabled", false)
        if (!notificationsEnabled) {
            Log.d(TAG, "Boot received but notifications disabled; skipping service start")
            return
        }
        Log.d(TAG, "${intent.action} received, starting FrigateAlertService")
        FrigateAlertService.updateForContext(context)
    }

    companion object {
        private const val TAG = "FrigateBootReceiver"
    }
}
