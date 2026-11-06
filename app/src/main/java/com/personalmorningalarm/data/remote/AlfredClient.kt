package com.personalmorningalarm.data.remote

import androidx.annotation.VisibleForTesting
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds the [AlfredApiService] against the address saved in [AlfredSettings],
 * rebuilding only when that address changes.
 *
 * Timeouts are deliberately short: Alfred lives on the home network, so it either
 * answers fast or it isn't there. An unreachable Alfred must never leave a content
 * screen hanging mid-morning — callers fall back to cache well inside the screen's
 * dismiss timer.
 *
 * Writes are the one exception, and only once connected. Each is a git transaction
 * on the Pi (pull, commit, push; about 2.7s), run one at a time, so a write can
 * legitimately wait behind others. Giving up on one doesn't stop it: the Pi still
 * finishes, and the queue's replay then adds a second copy. So a write gets
 * [WRITE_READ_TIMEOUT_SECONDS] to be answered, which covers OkHttp's limit of five
 * calls in flight to a host. Its connect timeout stays short, so an unreachable
 * Alfred still fails fast and the write queues as ever.
 */
object AlfredClient {

    private const val TIMEOUT_SECONDS = 3L
    private const val WRITE_READ_TIMEOUT_SECONDS = 15L

    /** Everything in [AlfredApiService] that isn't a GET is a vault write. */
    private val writeTimeout = Interceptor { chain ->
        val request = chain.request()
        if (request.method() == "GET") {
            chain.proceed(request)
        } else {
            chain.withReadTimeout(WRITE_READ_TIMEOUT_SECONDS.toInt(), TimeUnit.SECONDS).proceed(request)
        }
    }

    @VisibleForTesting
    internal val http = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(writeTimeout)
        .build()

    private var cachedBaseUrl: String? = null
    private var cachedService: AlfredApiService? = null

    @Synchronized
    fun service(settings: AlfredSettings): AlfredApiService {
        val baseUrl = settings.baseUrl()
        cachedService?.let { if (baseUrl == cachedBaseUrl) return it }
        val service = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AlfredApiService::class.java)
        cachedBaseUrl = baseUrl
        cachedService = service
        return service
    }
}
