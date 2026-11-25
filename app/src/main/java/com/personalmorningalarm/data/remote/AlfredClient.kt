package com.personalmorningalarm.data.remote

import androidx.annotation.VisibleForTesting
import com.personalmorningalarm.data.profile.Profile
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
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
 *
 * Every shopping request names whose phone this is ([shoppingOwner]): Alfred's
 * `/shopping` without `for=` answers with everyone's lists, so that default is
 * never relied on.
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

    /**
     * Adds `for=<profile>` to every request under `/shopping` (the discovery GET,
     * create, each list's read and writes), read per request so a profile change
     * applies at once. With no profile chosen yet the request is refused rather than
     * sent without one: it fails as unreachable, and nothing is shown.
     */
    @VisibleForTesting
    internal fun shoppingOwner(owner: () -> Profile?) = Interceptor { chain ->
        val request = chain.request()
        if (request.url().pathSegments().firstOrNull() != SHOPPING_SEGMENT) {
            chain.proceed(request)
        } else {
            val profile = owner() ?: throw IOException("No profile chosen: shopping request not sent")
            val url = request.url().newBuilder()
                .removeAllQueryParameters(OWNER_PARAM)
                .addQueryParameter(OWNER_PARAM, profile.wireName)
                .build()
            chain.proceed(request.newBuilder().url(url).build())
        }
    }

    private const val SHOPPING_SEGMENT = "shopping"
    private const val OWNER_PARAM = "for"

    private var cachedBaseUrl: String? = null
    private var cachedService: AlfredApiService? = null

    /**
     * Where the cached service reads the profile from: the last caller's. Every
     * caller reads the same saved setting, so which one doesn't matter; keeping the
     * latest just avoids holding on to a context that has gone.
     */
    @Volatile
    private var owner: () -> Profile? = { null }

    /** The Alfred API at [settings]' address, naming [owner] on shopping requests. */
    @Synchronized
    fun service(settings: AlfredSettings, owner: () -> Profile?): AlfredApiService {
        this.owner = owner
        val baseUrl = settings.baseUrl()
        cachedService?.let { if (baseUrl == cachedBaseUrl) return it }
        val service = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(http.newBuilder().addInterceptor(shoppingOwner { this.owner() }).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AlfredApiService::class.java)
        cachedBaseUrl = baseUrl
        cachedService = service
        return service
    }
}
