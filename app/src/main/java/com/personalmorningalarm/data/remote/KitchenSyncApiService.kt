package com.personalmorningalarm.data.remote

import android.content.Context
import com.personalmorningalarm.data.model.GroceryItemDto
import com.personalmorningalarm.util.ThemeManager
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/**
 * KitchenSync's grocery list (app/routes/shopping.py in the KitchenSync repo), on
 * the Pi beside vault-api. Only the calls Groceries needs: KitchenSync owns this
 * list outright, and the vault's /shopping API never sees it.
 */
interface KitchenSyncApiService {

    /** Every item on the list, in the order added. Nothing ticked: a tick removes. */
    @GET("api/shopping-list")
    suspend fun getGroceries(): List<GroceryItemDto>

    /** Adds one item; always a new row, even with the same name. No Idempotency-Key. */
    @POST("api/shopping-list/items")
    suspend fun addGrocery(@Body body: GroceryAddRequest): Response<GroceryItemDto>

    /**
     * Ticks it: it leaves the list, and KitchenSync puts it in stock or in its "to put
     * away" tray itself. Takes no body. 404 if it's already gone.
     */
    @POST("api/shopping-list/items/{id}/purchase")
    suspend fun purchaseGrocery(@Path("id") id: Long): Response<Unit>

    /** Removes it, not bought. 404 if it's already gone. */
    @DELETE("api/shopping-list/items/{id}")
    suspend fun deleteGrocery(@Path("id") id: Long): Response<Unit>
}

/** KitchenSync's add: a name and how many (1 unless said); the unit is optional. */
data class GroceryAddRequest(
    @com.google.gson.annotations.SerializedName("item_name") val itemName: String,
    val quantity: Double = 1.0,
    val unit: String? = null
)

/**
 * KitchenSync's address. It runs on the same Pi as vault-api, on [DEFAULT_PORT], so
 * unless one is saved it's Alfred's host on that port: a phone whose hub moved
 * follows it there without a second setting.
 */
class KitchenSyncSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(ThemeManager.PREFS_NAME, Context.MODE_PRIVATE)
    private val alfred = AlfredSettings(context)

    fun getHost(): String =
        prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: besideAlfred(alfred.getHost())

    fun setHost(host: String) {
        prefs.edit().putString(KEY_HOST, HostAddress.normalise(host, DEFAULT_PORT)).apply()
    }

    fun baseUrl(): String = "http://${getHost()}/"

    companion object {
        const val DEFAULT_PORT = 8400
        private const val KEY_HOST = "kitchensync_host"

        /** "192.168.1.100:8200" -> "192.168.1.100:8400". */
        fun besideAlfred(alfredHost: String): String = "${alfredHost.substringBeforeLast(':')}:$DEFAULT_PORT"
    }
}

/**
 * Builds the [KitchenSyncApiService], rebuilding only when the address changes. The
 * same short timeouts as Alfred's: on the home network it answers fast or isn't
 * there. Like [DashboardClient], connection-failure retries are off and nothing is
 * pooled: an add can't be resent safely (KitchenSync takes no key), so OkHttp mustn't
 * quietly send one twice, and with retries off a stale pooled connection would fail.
 */
object KitchenSyncClient {

    private const val TIMEOUT_SECONDS = 3L

    /** A tick moves stock and logs it, so it gets longer to be answered once connected. */
    private const val WRITE_READ_TIMEOUT_SECONDS = 10L

    private val http = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.method() == "GET") chain.proceed(request)
            else chain.withReadTimeout(WRITE_READ_TIMEOUT_SECONDS.toInt(), TimeUnit.SECONDS).proceed(request)
        }
        .retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    private var cachedBaseUrl: String? = null
    private var cachedService: KitchenSyncApiService? = null

    @Synchronized
    fun service(baseUrl: String): KitchenSyncApiService {
        cachedService?.let { if (baseUrl == cachedBaseUrl) return it }
        val service = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(KitchenSyncApiService::class.java)
        cachedBaseUrl = baseUrl
        cachedService = service
        return service
    }
}
