package com.personalmorningalarm.data.remote

import androidx.annotation.VisibleForTesting
import com.personalmorningalarm.data.profile.Profile
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.BufferedSink
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

    /**
     * A create is never sent twice by the client. OkHttp quietly resends a request
     * whose connection dropped after it went out; for a create that makes a second
     * copy: another to-do line, shopping item, inbox file or calendar event, or a
     * "-2" shared list. A one-shot body is never resent, so a lost answer reaches the
     * caller, which works out whether it landed.
     *
     * A create is any request carrying a [SendMarker] (every create in
     * [AlfredApiService] takes one), plus the shopping upload, which reconciles by
     * its own means (ShoppingShare).
     */
    private val uploadSentOnce = Interceptor { chain ->
        val request = chain.request()
        val body = request.body()
        val create = request.tag(SendMarker::class.java) != null || request.url().pathSegments() in SEND_ONCE_PATHS
        if (request.method() != "POST" || body == null || !create) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().method("POST", OneShotBody(body)).build())
        }
    }

    /** [delegate], marked as a body OkHttp must not send a second time. */
    private class OneShotBody(private val delegate: RequestBody) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
        override fun isOneShot(): Boolean = true
    }

    private val SEND_ONCE_PATHS = setOf(listOf("shopping", "upload"))

    /**
     * Marks a request's [SendMarker] as it goes onto a connection. A network
     * interceptor runs only once a connection is up, so a failure with the marker
     * still clear never left the phone.
     */
    @VisibleForTesting
    internal val markSent = Interceptor { chain ->
        chain.request().tag(SendMarker::class.java)?.sent = true
        chain.proceed(chain.request())
    }

    @VisibleForTesting
    internal val http = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(writeTimeout)
        .addInterceptor(uploadSentOnce)
        .addNetworkInterceptor(markSent)
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
