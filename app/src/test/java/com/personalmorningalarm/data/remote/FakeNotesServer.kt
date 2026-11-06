package com.personalmorningalarm.data.remote

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.personalmorningalarm.data.model.Notes
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A stand-in for vault-api's /notes over real HTTP, following src/vault_api/routers/
 * notes.py: PUT takes {"text": ...} and refuses blank or over-long text with a 422,
 * identical text changes nothing, and a DELETE of an unknown note still succeeds.
 */
class FakeNotesServer {

    private val server = MockWebServer()
    private val gson = Gson()

    /** The vault, by id. Newest save last. */
    val notes: MutableMap<String, String> = Collections.synchronizedMap(linkedMapOf())

    /** Every PUT and DELETE that reached the server, as "PUT id" or "DELETE id". */
    val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** How many saves or deletes really changed a note, which is what a commit would be. */
    @Volatile var commits = 0

    /** Connections die with nothing answered and nothing written. */
    @Volatile var down = false

    /** Reads fail while writes still land: a delivery followed by Alfred going away. */
    @Volatile var readsDown = false

    /** When set, reads wait on it, so a test can act while a note is loading. */
    @Volatile var readGate: CountDownLatch? = null

    /** Writes land, then the connection dies before the answer: a timeout, as the phone sees it. */
    @Volatile var loseAnswers = false

    /** Answered with this status instead of being handled. */
    @Volatile var writeStatus: Int? = null

    /** When set, writes wait on it, so a test can act while one is with Alfred. */
    @Volatile var writeGate: CountDownLatch? = null

    /** When set, only writes to this note wait at the gate. */
    @Volatile var gateOnlyFor: String? = null

    /** Counted down when a write reaches the gate. */
    @Volatile var writeArrived: CountDownLatch? = null

    fun start(settings: AlfredSettings) {
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        })
        server.start(InetAddress.getByName(LOCALHOST), 0)
        settings.setHost("$LOCALHOST:${server.port}")
    }

    fun shutdown() {
        writeGate?.countDown()
        readGate?.countDown()
        server.shutdown()
    }

    private fun answer(request: RecordedRequest): MockResponse {
        if (down) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        val id = request.path.removePrefix("/notes").removePrefix("/")
        return when (request.method) {
            "GET" -> read(id)
            "PUT" -> gated(request, id) { save(id, JsonParser.parseString(request.body.readUtf8())) }
            "DELETE" -> gated(request, id) { delete(id) }
            else -> MockResponse().setResponseCode(405)
        }
    }

    private fun read(id: String): MockResponse {
        readGate?.await(5, TimeUnit.SECONDS)
        if (readsDown) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        if (id.isEmpty()) {
            val list = notes.entries.toList().reversed().map { (noteId, text) -> Notes.summary(noteId, text) }
            return MockResponse().setBody(gson.toJson(list))
        }
        val text = notes[id] ?: return MockResponse().setResponseCode(404).setBody("""{"detail":"note not found"}""")
        return MockResponse().setBody(gson.toJson(mapOf("id" to id, "text" to text, "updated" to UPDATED)))
    }

    private fun gated(request: RecordedRequest, id: String, handle: () -> MockResponse): MockResponse {
        writes += "${request.method} $id"
        if (gateOnlyFor == null || gateOnlyFor == id) {
            writeArrived?.countDown()
            writeGate?.await(5, TimeUnit.SECONDS)
        }
        writeStatus?.let { return MockResponse().setResponseCode(it).setBody("""{"detail":"no"}""") }
        val response = handle()
        return if (loseAnswers) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else response
    }

    private fun save(id: String, body: com.google.gson.JsonElement): MockResponse {
        val text = body.asJsonObject.get("text").asString
        if (text.isBlank() || text.length > MAX_CHARS) {
            return MockResponse().setResponseCode(422).setBody("""{"detail":"note text refused"}""")
        }
        val changed = notes[id] != text
        if (changed) {
            notes.remove(id)
            notes[id] = text
            commits++
        }
        val summary = Notes.summary(id, text)
        return MockResponse().setBody(
            gson.toJson(
                mapOf(
                    "id" to id, "title" to summary.title, "updated" to UPDATED,
                    "preview" to summary.preview, "changed" to changed
                )
            )
        )
    }

    private fun delete(id: String): MockResponse {
        val removed = notes.remove(id) != null
        if (removed) commits++
        return MockResponse().setBody(gson.toJson(mapOf("deleted" to id, "removed" to removed)))
    }

    companion object {
        const val MAX_CHARS = 50_000
        private const val LOCALHOST = "127.0.0.1"
        private const val UPDATED = "2026-03-14T09:26:53Z"
    }
}
