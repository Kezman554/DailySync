package com.personalmorningalarm.data.remote

import android.content.Context
import com.personalmorningalarm.util.ThemeManager

/**
 * The Alfred Vault API's address on the local network, stored as "host:port".
 * Shared by every Alfred-backed content screen — the address is configured once
 * in settings, not per screen.
 */
class AlfredSettings(context: Context) {

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
        /** Alfred serves the API on port 8200. */
        const val DEFAULT_PORT = 8200
        val DEFAULT_HOST = HostAddress.defaultHost(DEFAULT_PORT)

        private const val KEY_HOST = "alfred_host"

        /** See [HostAddress.isValid]. */
        fun isValid(input: String): Boolean = HostAddress.isValid(input, DEFAULT_PORT)

        /** See [HostAddress.normalise]; a bare host gets [DEFAULT_PORT]. */
        fun normalise(input: String): String = HostAddress.normalise(input, DEFAULT_PORT)
    }
}
