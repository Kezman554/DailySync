package com.personalmorningalarm.data.remote

import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.net.InetAddress
import java.net.URLDecoder
import java.util.Collections

/**
 * A stand-in for vault-api's /shopping over real HTTP, following
 * src/vault_api/routers/shopping.py (Session BW): lists have an owner and a shared
 * flag; `?for=` shows that person's own lists and the other's shared ones; upload
 * makes a shared list ("-2" when the name is taken, never overwriting); pull-back
 * answers with the items and deletes the list in the same step; a missing list is a
 * 404 carrying the current lists.
 *
 * Failures to order: [down] refuses every connection; [loseAnswerTo] lets the named
 * call ("upload", "pull-back") do its work and then cuts the connection before
 * answering, so the phone never hears that it happened.
 */
class FakeShoppingHub {

    data class Item(val text: String, val ticked: Boolean)

    data class HubList(
        val id: String,
        val title: String,
        val owner: String,
        var shared: Boolean,
        val items: MutableList<Item>
    )

    private val server = MockWebServer()
    private val gson = Gson()

    /** The vault's lists, by id, in the order made. */
    val lists: MutableMap<String, HubList> = Collections.synchronizedMap(linkedMapOf())

    /** Every upload that reached the hub and made a list. */
    @Volatile var uploads = 0

    /** Every request, as "METHOD /path" with the list id decoded. */
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile var down = false

    /** Calls ("upload", "pull-back") that do their work and then lose the answer. */
    val loseAnswerTo: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** Calls after which the hub stops answering altogether, as if the WiFi dropped right then. */
    val goDownAfter: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** Uploads answer 400, as an owner the hub doesn't know would. */
    @Volatile var refuseUploads = false

    /** Item adds answer 503, so a queued add can't be delivered. */
    @Volatile var addsFail = false

    val host: String get() = "${server.hostName}:${server.port}"

    fun start() {
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        })
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    fun shutdown() = server.shutdown()

    fun add(title: String, owner: String, shared: Boolean, vararg items: Item): HubList {
        val list = HubList(idFor(title), title, owner, shared, items.toMutableList())
        lists[list.id] = list
        return list
    }

    private fun handle(request: RecordedRequest): MockResponse {
        if (down) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        val url = request.requestUrl
        val path = url.encodedPath()
        val method = request.method
        val rest = path.removePrefix("/shopping").removePrefix("/")
        val (listId, verb) = when {
            rest.isEmpty() -> null to null
            rest == "upload" -> null to "upload"
            rest.endsWith("/share") -> decode(rest.removeSuffix("/share")) to "share"
            rest.endsWith("/pull-back") -> decode(rest.removeSuffix("/pull-back")) to "pull-back"
            else -> decode(rest) to null
        }
        requests += "$method /shopping${if (listId != null) "/$listId" else ""}${verb?.let { "/$it" } ?: ""}"
        val forName = url.queryParameter("for")
        return when {
            method == "GET" && listId == null -> json(200, lists.values.filter {
                forName == null || it.owner == forName || it.shared
            }.map(::summary))
            method == "GET" -> lists[listId]?.let { json(200, detail(it)) } ?: notFound()
            verb == "upload" -> upload(request)
            verb == "share" -> lists[listId]?.let {
                it.shared = JsonParser.parseString(request.body.readUtf8()).asJsonObject["shared"].asBoolean
                json(200, mapOf("list" to summary(it)))
            } ?: notFound()
            verb == "pull-back" -> {
                val list = lists.remove(listId) ?: return notFound()
                answer("pull-back", json(200, mapOf("pulled_back" to list.id, "list" to summary(list), "items" to items(list))))
            }
            method == "POST" && listId != null -> {
                if (addsFail) return MockResponse().setResponseCode(503)
                val list = lists[listId] ?: return notFound()
                list.items += Item(JsonParser.parseString(request.body.readUtf8()).asJsonObject["text"].asString, false)
                json(200, emptyMap<String, Any>())
            }
            else -> MockResponse().setResponseCode(405)
        }
    }

    private fun upload(request: RecordedRequest): MockResponse {
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        val title = body["title"].asString
        val owner = body["owner"].asString
        if (refuseUploads || owner !in setOf("nick", "jess")) return MockResponse().setResponseCode(400)
        val items = body["items"].asJsonArray.map {
            val item = it.asJsonObject
            Item(item["text"].asString, item["ticked"].asBoolean)
        }
        val list = HubList(idFor(title), title, owner, shared = true, items = items.toMutableList())
        lists[list.id] = list
        uploads++
        return answer("upload", json(201, mapOf("id" to list.id, "list" to summary(list), "items" to items(list))))
    }

    private fun answer(call: String, response: MockResponse): MockResponse {
        if (call in goDownAfter) down = true
        return if (call in loseAnswerTo) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else response
    }

    /** "6-life/shopping/big-shop.md", then "-2", "-3"... as the hub names a taken one. */
    private fun idFor(title: String): String {
        val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        var id = "6-life/shopping/$slug.md"
        var n = 2
        while (id in lists) id = "6-life/shopping/$slug-${n++}.md"
        return id
    }

    private fun summary(list: HubList) = mapOf(
        "id" to list.id,
        "title" to list.title,
        "total" to list.items.size,
        "unticked" to list.items.count { !it.ticked },
        "owner" to list.owner,
        "shared" to list.shared
    )

    private fun items(list: HubList) = list.items.map {
        mapOf("text" to it.text, "line" to "- [${if (it.ticked) "x" else " "}] ${it.text}", "ticked" to it.ticked)
    }

    // GET /shopping/{id} answers with the four fields only: no owner or shared.
    private fun detail(list: HubList) = mapOf(
        "list" to mapOf("id" to list.id, "title" to list.title, "total" to list.items.size, "unticked" to list.items.count { !it.ticked }),
        "items" to items(list)
    )

    private fun notFound() = json(404, mapOf("detail" to mapOf("error" to "shopping list not found", "lists" to lists.values.map(::summary))))

    private fun json(code: Int, body: Any) = MockResponse().setResponseCode(code).setBody(gson.toJson(body))

    private fun decode(segment: String): String = URLDecoder.decode(segment, "UTF-8")
}
