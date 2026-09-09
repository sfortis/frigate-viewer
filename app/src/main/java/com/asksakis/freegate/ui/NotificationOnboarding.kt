package com.asksakis.freegate.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.asksakis.freegate.notifications.BatteryOptHelper
import com.asksakis.freegate.notifications.OemSettingsIntents

/**
 * The reliability grants the background alert listener needs, asked one at a time.
 *
 * Turning alerts on is only the first of several permissions, and the rest live on three
 * different system screens. Asking for them in one pass stacked modal dialogs on top of
 * each other and left the most important one at the bottom, so this walks the user through
 * them in the order they matter and pauses whenever a step hands the user to the system.
 *
 * Both places that can turn alerts on share this class, so they cannot drift apart on which
 * grants they ask for. The POST_NOTIFICATIONS request itself stays with the host, because an
 * ActivityResultLauncher has to be registered by the fragment that owns it.
 *
 * [includeDnd] is false for the first-run offer on Home. Do Not Disturb access is not needed
 * for alerts to arrive, only for them to ring while Do Not Disturb is on, so the first-run
 * sequence leaves it to the dedicated row in Settings.
 */
class NotificationOnboarding(
    private val fragment: Fragment,
    private val includeDnd: Boolean,
) {
    private enum class Step { BATTERY, OEM, DND }

    private val pending = mutableListOf<Step>()

    /** True while the user is on a system screen that one of the steps opened. */
    private var waitingForSystemScreen = false

    private val prefs
        get() = fragment.context?.let { PreferenceManager.getDefaultSharedPreferences(it) }

    /**
     * Queue every step the user has not already answered and show the first one that still
     * applies. Each step carries its own flag, so turning alerts off and on again does not
     * walk the user back through prompts they have answered.
     */
    fun start() {
        val prefs = prefs ?: return
        pending.clear()
        if (!prefs.getBoolean(PREF_BATTERY_PROMPTED, false)) {
            pending += Step.BATTERY
        }
        if (OemSettingsIntents.hasCustomBackgroundSettings() &&
            !prefs.getBoolean(PREF_OEM_PROMPTED, false)
        ) {
            pending += Step.OEM
        }
        if (includeDnd) {
            pending += Step.DND
        }
        runNext()
    }

    /** Drop a queued walkthrough, for a host that has decided alerts stay off after all. */
    fun cancel() {
        pending.clear()
    }

    /**
     * Continue a walkthrough that is waiting for the user to come back from a system screen.
     * Hosts call this from their own `onResume`, which is what keeps the next prompt from
     * being created behind that screen.
     */
    fun onResume() {
        if (!waitingForSystemScreen) return
        waitingForSystemScreen = false
        runNext()
    }

    /**
     * Show the next queued step that still applies. A step whose grant is already in place
     * shows nothing and reports back false, so the walkthrough moves on without a dead
     * dialog rather than stopping there.
     */
    private fun runNext() {
        if (waitingForSystemScreen) return
        while (pending.isNotEmpty()) {
            val shown = when (pending.removeAt(0)) {
                Step.BATTERY -> promptBatteryOptimization(autoPrompt = true, onDismiss = ::runNext)
                Step.OEM -> promptOemBackgroundRestrictions(onDismiss = ::runNext)
                Step.DND -> promptDndAccess(autoPrompt = true, onDismiss = ::runNext)
            }
            if (shown) return
        }
    }

    /**
     * Offer the battery-optimisation exemption, which is what stops Android killing the
     * listener in the background. Returns false when the exemption is already in place.
     *
     * [autoPrompt] is true inside the walkthrough and false when the user taps the Settings
     * row, which only changes the wording: the row tap needs no explanation of why.
     */
    fun promptBatteryOptimization(
        autoPrompt: Boolean,
        onDismiss: (() -> Unit)? = null,
    ): Boolean {
        val ctx = fragment.context ?: return false
        if (BatteryOptHelper.isIgnoringOptimizations(ctx)) {
            markPrompted(PREF_BATTERY_PROMPTED)
            // The exemption alone is not enough on Samsung, MIUI or ColorOS. Inside the
            // walkthrough the OEM tip is a step of its own, so only a standalone call
            // chains to it from here.
            if (autoPrompt && onDismiss == null &&
                OemSettingsIntents.hasCustomBackgroundSettings()
            ) {
                promptOemBackgroundRestrictions()
            }
            return false
        }
        FreegateDialogs.builder(ctx)
            .setTitle("Keep notifications reliable")
            .setMessage(
                if (autoPrompt)
                    "Android may silently kill the background listener after a while. " +
                        "Allow Phylax to bypass battery optimization so alerts arrive " +
                        "reliably?"
                else
                    "Allow Phylax to bypass battery optimization?"
            )
            .setPositiveButton("Allow") { _, _ ->
                if (onDismiss != null) waitingForSystemScreen = true
                markPrompted(PREF_BATTERY_PROMPTED)
                BatteryOptHelper.requestIgnore(ctx)
                if (onDismiss == null && OemSettingsIntents.hasCustomBackgroundSettings()) {
                    promptOemBackgroundRestrictions()
                }
            }
            .setNegativeButton("Not now") { _, _ -> markPrompted(PREF_BATTERY_PROMPTED) }
            .apply { onDismiss?.let { resume -> setOnDismissListener { resume() } } }
            .show()
        return true
    }

    /**
     * Show the OEM background-restrictions instructions for this manufacturer. Only queued
     * on devices that have such a screen, so it always shows and always returns true.
     */
    fun promptOemBackgroundRestrictions(onDismiss: (() -> Unit)? = null): Boolean {
        val ctx = fragment.context ?: return false
        markPrompted(PREF_OEM_PROMPTED)
        val oem = OemSettingsIntents.current()
        FreegateDialogs.builder(ctx)
            .setTitle("One more step on ${oem.displayName}")
            .setMessage(OemSettingsIntents.instructionsFor(oem))
            .setPositiveButton("Open settings") { _, _ ->
                if (onDismiss != null) waitingForSystemScreen = true
                OemSettingsIntents.openBackgroundRestrictions(ctx)
            }
            .setNegativeButton("Later", null)
            .apply { onDismiss?.let { resume -> setOnDismissListener { resume() } } }
            .show()
        return true
    }

    /**
     * Offer Do Not Disturb access, which is what lets the alert channel ring at alarm volume
     * while Do Not Disturb is on. Returns false when access is already granted, and when
     * [autoPrompt] is true and the user has already been asked once.
     */
    fun promptDndAccess(
        autoPrompt: Boolean,
        onDismiss: (() -> Unit)? = null,
    ): Boolean {
        val ctx = fragment.context ?: return false
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) return false
        if (autoPrompt && prefs?.getBoolean(PREF_DND_PROMPTED, false) == true) return false

        FreegateDialogs.builder(ctx)
            .setTitle("Override Do Not Disturb")
            .setMessage(
                if (autoPrompt)
                    "Phylax can ring alerts at alarm volume even while Do Not " +
                        "Disturb is on, so you don't miss a real event overnight. " +
                        "Android opens a list of every app that can ask for this, so find " +
                        "Phylax there and turn it on."
                else
                    "Grant Phylax permission to override Do Not Disturb so alerts " +
                        "ring at alarm volume? Android opens a list of every app that " +
                        "can ask for this, so find Phylax there and turn it on."
            )
            .setPositiveButton("Open settings") { _, _ ->
                markPrompted(PREF_DND_PROMPTED)
                if (onDismiss != null) waitingForSystemScreen = true
                openDndAccessSettings()
            }
            .setNegativeButton("Not now") { _, _ -> markPrompted(PREF_DND_PROMPTED) }
            .apply { onDismiss?.let { resume -> setOnDismissListener { resume() } } }
            .show()
        return true
    }

    /**
     * Open the Do Not Disturb access list with this app's row scrolled to and highlighted.
     *
     * There is no public per-app screen for this permission, only the list of every app that
     * has asked for it. The two extras below are the long-standing AOSP Settings convention
     * for pointing a list screen at one row, and a Settings build that does not understand
     * them shows the plain list instead.
     */
    fun openDndAccessSettings() {
        val ctx = fragment.context ?: return
        val pkg = ctx.packageName
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            putExtra(SETTINGS_ARG_KEY, pkg)
            putExtra(
                SETTINGS_SHOW_ARGS,
                Bundle().apply { putString(SETTINGS_ARG_KEY, pkg) },
            )
        }
        runCatching { fragment.startActivity(intent) }
            .onFailure {
                Toast.makeText(ctx, "Unable to open DND access settings", Toast.LENGTH_SHORT)
                    .show()
            }
    }

    private fun markPrompted(key: String) {
        prefs?.edit()?.putBoolean(key, true)?.apply()
    }

    companion object {
        /** Set once the battery-optimisation exemption has been offered, either answer. */
        const val PREF_BATTERY_PROMPTED = "battery_opt_prompted"

        /** Set once Do Not Disturb access has been offered, either answer. */
        const val PREF_DND_PROMPTED = "dnd_prompted"

        /** Set once the OEM background-restrictions tip has been shown. */
        const val PREF_OEM_PROMPTED = "oem_reliability_prompted"

        /** AOSP Settings extras that scroll a list screen to one row and highlight it. */
        private const val SETTINGS_ARG_KEY = ":settings:fragment_args_key"
        private const val SETTINGS_SHOW_ARGS = ":settings:show_fragment_args"
    }
}
