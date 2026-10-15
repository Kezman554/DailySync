package com.personalmorningalarm.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.personalmorningalarm.R

/**
 * Notifications are load-bearing for this app, not a nicety.
 *
 * [com.personalmorningalarm.service.AlarmService] shows the dismissal screen through
 * its notification's full-screen intent — that is the *only* path that puts the
 * screen up. With notifications off, the alarm still sounds at full volume but
 * nothing can display the screen that stops it, and there is no crash to point at.
 * So this is treated as a broken state the app reports on every launch until fixed.
 *
 * On API 33+ POST_NOTIFICATIONS is a runtime permission, denied by default — a
 * fresh install (including one gradle makes when a signature conflicts) lands here.
 */
object NotificationPermissionHelper {

    private const val TAG = "PMA"
    private const val KEY_REQUESTED = "notifications_requested"

    const val PERMISSION = Manifest.permission.POST_NOTIFICATIONS

    /** The runtime permission only exists on API 33+; below that it's implicit. */
    val requiresRuntimePermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Whether the app can actually post notifications. Deliberately checks the
     * delivered state rather than the permission grant alone, so notifications the
     * user (or an OEM) turned off in system settings count as off too — the
     * permission can read as granted while delivery is still blocked.
     */
    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Whether to ask via the system permission dialog. Only useful once: after the
     * user denies twice, Android silently no-ops the request, so from then on the
     * only way back is system settings ([showBlockedDialog]).
     */
    fun shouldRequestPermission(context: Context): Boolean =
        requiresRuntimePermission && !prefs(context).getBoolean(KEY_REQUESTED, false)

    fun markRequested(context: Context) {
        prefs(context).edit().putBoolean(KEY_REQUESTED, true).apply()
    }

    /**
     * Explains that the alarm can't be dismissed without notifications, and offers to
     * open the app's notification settings. Not cancellable on the back button: this
     * isn't advice, the app can't do its one job in this state.
     */
    fun showBlockedDialog(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.notifications_dialog_title)
            .setMessage(R.string.notifications_dialog_message)
            .setPositiveButton(R.string.notifications_open_settings) { _, _ ->
                openNotificationSettings(context)
            }
            .setNegativeButton(R.string.notifications_not_now, null)
            .setCancelable(false)
            .show()
    }

    /** Opens this app's notification settings; falls back to its app-info page. */
    fun openNotificationSettings(context: Context) {
        val direct = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(direct) }.onFailure {
            Log.w(TAG, "App notification settings unavailable; opening app info", it)
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(ThemeManager.PREFS_NAME, Context.MODE_PRIVATE)
}
