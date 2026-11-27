package com.personalmorningalarm.data.remote

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.personalmorningalarm.data.model.GroceryItemDto
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.net.InetAddress
import java.util.Collections

/**
 * A stand-in for KitchenSync's grocery list over real HTTP, following
 * app/routes/shopping.py in the KitchenSync repo: the list in the order added; an
 * add is always a new row (no Idempotency-Key); a purchase takes the item off the
 * list and answers what it did with it; a delete takes it off, 204; an unknown id
 * is a 404. [down] drops every request unanswered, doing nothing.
 */
class FakeKitchenSync {

    private val server = MockWebServer()
    private val gson = Gson()
    private var nextId = 1L

    val items: MutableList<GroceryItemDto> = Collections.synchronizedList(mutableListOf())

    /** What a tick did, by name: KitchenSync's stock and tray stand in as this list. */
    val purchased: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Every request, as "METHOD /path". */
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile var down = false

    val baseUrl: String get() = "http://${server.hostName}:${server.port}/"

    fun start() {
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        })
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    fun shutdown() = server.shutdown()

    fun add(name: String, quantity: Double = 1.0, unit: String? = null, source: String = "manual"): GroceryItemDto =
        GroceryItemDto(nextId++, name, quantity, unit, source).also { items += it }

    private fun handle(request: RecordedRequest): MockResponse {
        // Dropped without an answer and nothing done. Not DISCONNECT_AT_START: returned
        // from dispatch, MockWebServer sends that as a plain 200.
        if (down) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        val path = request.requestUrl.encodedPath()
        requests += "${request.method} $path"
        val itemPath = Regex("/api/shopping-list/items/(\\d+)(/purchase)?").matchEntire(path)
        return when {
            request.method == "GET" && path == "/api/shopping-list" -> json(200, items.toList())
            request.method == "POST" && path == "/api/shopping-list/items" -> {
                val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                val unit = body["unit"]?.takeUnless { it.isJsonNull }?.asString
                json(201, add(body["item_name"].asString, body["quantity"].asDouble, unit))
            }
            itemPath != null -> {
                val id = itemPath.groupValues[1].toLong()
                val item = synchronized(items) { items.firstOrNull { it.id == id }?.also { items.remove(it) } }
                    ?: return json(404, mapOf("detail" to "Shopping list item not found"))
                if (itemPath.groupValues[2].isNotEmpty() && request.method == "POST") {
                    purchased += item.itemName.orEmpty()
                    json(200, mapOf("outcome" to "tray", "item_name" to item.itemName))
                } else {
                    MockResponse().setResponseCode(204)
                }
            }
            else -> MockResponse().setResponseCode(405)
        }
    }

    private fun json(code: Int, body: Any) = MockResponse().setResponseCode(code).setBody(gson.toJson(body))
}
