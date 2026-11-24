package com.personalmorningalarm.data.remote

import android.content.Context

/**
 * Last successful response body per Alfred endpoint, as raw JSON, so a screen can
 * still show something when Alfred is unreachable. One entry per endpoint —
 * a new response replaces the old one — each with its own fetch time, so every
 * surface can say how old its saved copy is.
 */
class AlfredResponseCache(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun put(endpoint: String, json: String, fetchedAtMillis: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(bodyKey(endpoint), json)
            .putLong(timeKey(endpoint), fetchedAtMillis)
            .apply()
    }

    /** The cached body and when it was stored, or null if this endpoint has none. */
    fun get(endpoint: String): CachedResponse? {
        val json = prefs.getString(bodyKey(endpoint), null) ?: return null
        return CachedResponse(json, prefs.getLong(timeKey(endpoint), 0L))
    }

    /** Whether Alfred has ever answered this phone: only a successful response is saved. */
    fun hasAny(): Boolean = prefs.all.keys.any { it.startsWith(BODY_PREFIX) }

    fun clear(endpoint: String) {
        prefs.edit().remove(bodyKey(endpoint)).remove(timeKey(endpoint)).apply()
    }

    data class CachedResponse(val json: String, val cachedAtMillis: Long)

    private companion object {
        const val PREFS_NAME = "alfred_cache"

        const val BODY_PREFIX = "body_"

        fun bodyKey(endpoint: String) = "$BODY_PREFIX$endpoint"
        fun timeKey(endpoint: String) = "time_$endpoint"
    }
}
