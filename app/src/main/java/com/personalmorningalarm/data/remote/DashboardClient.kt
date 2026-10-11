package com.personalmorningalarm.data.remote

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds the [DashboardApiService] against the address saved in
 * [DashboardSettings], rebuilding only when that address changes. The sibling of
 * [AlfredClient], with the same short timeouts: the dashboard is on the home
 * network, so it either answers fast or it isn't there.
 *
 * Connection-failure retries are OFF. A sign-in is sent once, now, or not at all:
 * there is no queue for it (see [PresenceRepository]), and the HTTP client
 * shouldn't quietly resend a PIN on the user's behalf either.
 *
 * And so connections are NOT POOLED. With retries off, a pooled connection the
 * dashboard has already closed (uvicorn drops idle keep-alives after ~5s) fails
 * the next request outright. OkHttp only health-checks a pooled connection
 * thoroughly before a POST, so it was the status GET that broke: the tile's
 * re-read at expiry, or reopening it after half a minute, said "dashboard
 * unreachable" while the Pi was up. A fresh connection per call has nothing
 * stale to reuse, and on the home LAN costs a few milliseconds.
 */
object DashboardClient {

    private const val TIMEOUT_SECONDS = 3L

    private val http = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    private var cachedBaseUrl: String? = null
    private var cachedService: DashboardApiService? = null

    @Synchronized
    fun service(settings: DashboardSettings): DashboardApiService {
        val baseUrl = settings.baseUrl()
        cachedService?.let { if (baseUrl == cachedBaseUrl) return it }
        val service = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DashboardApiService::class.java)
        cachedBaseUrl = baseUrl
        cachedService = service
        return service
    }
}
