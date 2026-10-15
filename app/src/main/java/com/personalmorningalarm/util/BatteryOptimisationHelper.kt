package com.personalmorningalarm.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.personalmorningalarm.R

/**
 * Helps the app stay off the battery optimiser so alarms fire reliably. Doze and
 * app standby can delay or kill the alarm service; whitelisting the app avoids that.
 *
 * The one-time launch prompt is tracked in shared prefs so we only nag once; the
 * Settings screen lets the user revisit it any time.
 */
object BatteryOptimisationHelper {

    private const val TAG = "PMA"
    private const val KEY_PROMPTED = "battery_prompted"

    /** True if the app is exempt from battery optimisation (alarms run freely). */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Show the launch prompt only if still optimised and not previously prompted. */
    fun shouldPromptOnLaunch(context: Context): Boolean =
        !isIgnoringBatteryOptimizations(context) && !prefs(context).getBoolean(KEY_PROMPTED, false)

    fun markPrompted(context: Context) {
        prefs(context).edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    /**
     * Explains why whitelisting helps, then offers to open the system prompt. Safe to
     * call from any themed [context] (an Activity).
     */
    fun showExplanationDialog(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.battery_dialog_title)
            .setMessage(R.string.battery_dialog_message)
            .setPositiveButton(R.string.battery_open_settings) { _, _ -> requestExemption(context) }
            .setNegativeButton(R.string.battery_not_now, null)
            .show()
    }

    /**
     * Opens the direct "ignore battery optimisations" request; falls back to the
     * battery-optimisation settings list if the direct action isn't available.
     */
    @SuppressLint("BatteryLife")
    fun requestExemption(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(direct) }.onFailure {
            Log.w(TAG, "Direct battery-optimisation request failed; opening settings list", it)
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(ThemeManager.PREFS_NAME, Context.MODE_PRIVATE)
}
