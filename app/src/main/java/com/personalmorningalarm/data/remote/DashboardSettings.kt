package com.personalmorningalarm.data.remote

import android.content.Context
import com.personalmorningalarm.util.ThemeManager

/**
 * The kitchen dashboard's address on the local network, stored as "host:port".
 *
 * A separate service from the vault-api in [AlfredSettings] — same Pi, different
 * port — so it gets its own setting rather than a path bolted onto Alfred's. Same
 * shape, and the same [HostAddress] rules for validation and normalisation.
 */
class DashboardSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(ThemeManager.PREFS_NAME, Context.MODE_PRIVATE)

    /** The saved "host:port", or [DEFAULT_HOST] if never configured. */
    fun getHost(): String =
        prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST

    fun setHost(host: String) {
        prefs.edit().putString(KEY_HOST, normalise(host)).apply()
    }

    /** Base URL for Retrofit — always absolute, always trailing-slashed. */
    fun baseUrl(): String = "http://${getHost()}/"

    companion object {
        /** The dashboard serves on port 8500. */
        const val DEFAULT_PORT = 8500
        val DEFAULT_HOST = HostAddress.defaultHost(DEFAULT_PORT)

        private const val KEY_HOST = "dashboard_host"

        /** See [HostAddress.isValid]. */
        fun isValid(input: String): Boolean = HostAddress.isValid(input, DEFAULT_PORT)

        /** See [HostAddress.normalise]; a bare host gets [DEFAULT_PORT]. */
        fun normalise(input: String): String = HostAddress.normalise(input, DEFAULT_PORT)
    }
}
