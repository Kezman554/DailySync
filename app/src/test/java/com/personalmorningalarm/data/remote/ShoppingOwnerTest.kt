package com.personalmorningalarm.data.remote

import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.profile.Profile
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Every shopping request names its profile (`for=nick` / `for=jess`) over a real
 * socket, so the hub's no-parameter answer (everyone's lists) is never asked for.
 */
@RunWith(RobolectricTestRunner::class)
class ShoppingOwnerTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val settings = AlfredSettings(context)
    private val server = MockWebServer()
    private var profile: Profile? = Profile.NICK

    private val repository = AlfredRepository(
        settings,
        AlfredResponseCache(context),
        { AlfredClient.service(it) { profile } }
    )

    @Before
    fun setUp() {
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path.startsWith("/shopping?") -> MockResponse().setBody("[]")
                request.method == "GET" -> MockResponse().setBody("""{"id":"a.md","title":"A","items":[]}""")
                else -> MockResponse().setBody("{}")
            }
        })
        server.start(InetAddress.getByName(LOCALHOST), 0)
        settings.setHost("$LOCALHOST:${server.port}")
    }

    @After
    fun tearDown() {
        server.shutdown()
        AlfredReachability.reset()
    }

    private fun nextPath(): String = server.takeRequest(5, TimeUnit.SECONDS)!!.path

    @Test
    fun `Nick's list discovery asks for Nick's lists`() = runBlocking {
        repository.getShoppingLists()

        assertEquals("/shopping?for=nick", nextPath())
    }

    @Test
    fun `Jess's list discovery asks for Jess's lists`() = runBlocking {
        profile = Profile.JESS
        repository.getShoppingLists()

        assertEquals("/shopping?for=jess", nextPath())
    }

    @Test
    fun `every other shopping request names the profile too`() = runBlocking {
        profile = Profile.JESS
        repository.getShoppingList("a.md")
        repository.addShoppingItem("a.md", "Milk", AddKey.new())
        repository.tickShoppingItem("a.md", "- [ ] Milk")
        repository.dropShoppingItem("a.md", "- [ ] Milk")
        repository.createShoppingList("Big shop")
        repository.deleteShoppingList("a.md")

        val paths = List(6) { nextPath() }
        assertEquals(
            listOf(
                "/shopping/a.md?for=jess",
                "/shopping/a.md?for=jess",
                "/shopping/a.md/tick?for=jess",
                "/shopping/a.md/drop?for=jess",
                "/shopping?for=jess",
                "/shopping/a.md?for=jess"
            ),
            paths
        )
    }

    @Test
    fun `a switch of profile applies to the very next request`() = runBlocking {
        repository.getShoppingLists()
        profile = Profile.JESS
        repository.getShoppingLists()

        assertEquals(listOf("/shopping?for=nick", "/shopping?for=jess"), List(2) { nextPath() })
    }

    @Test
    fun `other endpoints carry no profile`() = runBlocking {
        repository.getChalkboard()
        repository.getNotes()

        assertEquals(listOf("/chalkboard?list=nick&include_ticked=true", "/notes"), List(2) { nextPath() })
    }

    @Test
    fun `with no profile chosen, no shopping request is sent at all`() = runBlocking {
        profile = null
        val result = repository.getShoppingLists()

        assertTrue(result is AlfredResult.Unavailable)
        assertEquals(0, server.requestCount)
    }

    private companion object {
        const val LOCALHOST = "127.0.0.1"
    }
}
