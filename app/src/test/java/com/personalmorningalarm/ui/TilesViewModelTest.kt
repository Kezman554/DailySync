package com.personalmorningalarm.ui

import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.phone.ListHome
import com.personalmorningalarm.data.model.TodoFilter
import com.personalmorningalarm.data.model.TodoSource
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.remote.AlfredClient
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResponseCache
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.AlfredSettings
import com.personalmorningalarm.data.remote.ChalkboardSync
import com.personalmorningalarm.data.remote.DashboardSettings
import com.personalmorningalarm.data.remote.PresenceRepository
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
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Home fetches only what the profile's tiles need, read afresh on each refresh,
 * since the ViewModel outlives the recreate that follows choosing a profile.
 */
@RunWith(RobolectricTestRunner::class)
class TilesViewModelTest : ViewModelTestSupport() {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val server = MockWebServer()
    private val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private var profile: Profile? = null
    private var mineHome = ListHome.HUB
    private var savedFilter: TodoFilter? = null

    /** Whether GET /calendar/events answers; false fails it, as an unreachable hub would. */
    @Volatile
    private var calendarAnswers = true

    @Before
    fun setUp() {
        server.setDispatcher(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path
                val path = request.requestUrl.encodedPath()
                return MockResponse().setBody(
                    when {
                        path == "/api/presence" -> """{"open":false}"""
                        path.startsWith("/calendar") && !calendarAnswers -> return MockResponse().setResponseCode(503)
                        path.startsWith("/calendar") -> """{"events":[]}"""
                        else -> "[]"
                    }
                )
            }
        })
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        AlfredSettings(context).setHost("127.0.0.1:${server.port}")
        DashboardSettings(context).setHost("127.0.0.1:${server.port}")
    }

    @After
    fun tearDown() {
        server.shutdown()
        AlfredReachability.reset()
    }

    private fun viewModel(): TilesViewModel {
        val alfred = AlfredRepository(
            AlfredSettings(context),
            AlfredResponseCache(context),
            { AlfredClient.service(it) { profile } }
        )
        return track(
            TilesViewModel(
                alfred,
                ChalkboardSync(alfred, db.pendingChalkboardWriteDao()),
                PresenceRepository(DashboardSettings(context)),
                phoneStore,
                tiles = { ProfileTiles.tilesFor(profile) },
                mineHome = { mineHome },
                // The repository above names Nick whatever [profile] is, so these tests
                // see what the ViewModel itself asks for, not the repository's guard.
                profile = { profile },
                savedFilter = { savedFilter }
            )
        )
    }

    private fun drainRequests(): List<String> {
        while (server.takeRequest(300, TimeUnit.MILLISECONDS) != null) Unit
        return paths.sorted().also { paths.clear() }
    }

    @Test
    fun `before the choice nothing is fetched, and once Jess is chosen only her lists are`() {
        val vm = viewModel()
        keepSubscribed(vm.state)
        vm.refresh()
        awaitValue(vm.state) { true }
        assertTrue(drainRequests().isEmpty())

        // Chosen at first run: the same ViewModel, recreated around it.
        profile = Profile.JESS
        mineHome = ListHome.PHONE
        vm.refresh()
        awaitValue(vm.state) { it.shopping != null && it.familyTodos != null && it.today != null }

        // The family chalkboard, by name, her lists, and today's calendar for the
        // Today card; never Nick's rolling to-do, the plan or the wall.
        val today = LocalDate.now()
        assertEquals(
            listOf(
                "/calendar/events?start=$today&end=${today.plusDays(1)}",
                "/chalkboard?list=family&include_ticked=true",
                "/shopping?for=jess"
            ),
            drainRequests()
        )
    }

    @Test
    fun `Jess's Today card says the hub is out of reach rather than going blank`() {
        profile = Profile.JESS
        mineHome = ListHome.PHONE
        calendarAnswers = false
        val vm = viewModel()
        keepSubscribed(vm.state)
        vm.refresh()

        val state = awaitValue(vm.state) { it.today != null }

        assertEquals(AlfredResult.Unavailable, state.today)
        assertEquals(HomeTiles.Today.Unreachable, HomeTiles.today(state.today, LocalDate.now()))
        // Her lists still load: the calendar failing blanks nothing else.
        awaitValue(vm.state) { it.shopping is AlfredResult.Fresh }
    }

    @Test
    fun `Jess's Today card lists today's events from the hub`() {
        profile = Profile.JESS
        mineHome = ListHome.PHONE
        val vm = viewModel()
        keepSubscribed(vm.state)
        vm.refresh()

        val state = awaitValue(vm.state) { it.today != null }

        assertEquals(HomeTiles.Today.Events(emptyList()), HomeTiles.today(state.today, LocalDate.now()))
        assertEquals(null, state.schedule)
    }

    @Test
    fun `Nick's Home fetches everything it always did, his lists by name`() {
        profile = Profile.NICK
        val vm = viewModel()
        keepSubscribed(vm.state)
        vm.refresh()
        awaitValue(vm.state) {
            it.shopping != null && it.presence != null && it.familyTodos != null && it.mineTodos != null &&
                it.schedule != null
        }

        val requests = drainRequests()
        assertTrue(requests.toString(), "/shopping?for=nick" in requests)
        assertTrue(requests.toString(), "/chalkboard?list=nick&include_ticked=true" in requests)
        assertTrue(requests.toString(), "/chalkboard?list=family&include_ticked=true" in requests)
        assertTrue(requests.toString(), "/chalkboard?list=work&include_ticked=true" in requests)
        assertTrue(requests.toString(), "/daily-schedule" in requests)
        assertTrue(requests.toString(), "/api/presence" in requests)
        assertTrue(requests.toString(), requests.any { it.startsWith("/calendar/events") })
    }

    // --- the To-do tile follows the To-do screen's filter ---

    @Test
    fun `the tile follows the saved filter, and Work shows the work list alone`() {
        profile = Profile.NICK
        savedFilter = TodoFilter.WORK
        val vm = viewModel()
        keepSubscribed(vm.state)
        vm.refresh()
        val state = awaitValue(vm.state) { it.workTodos != null && it.familyTodos != null && it.mineTodos != null }

        assertEquals(TodoFilter.WORK, state.todoFilter)
        assertEquals(listOf(TodoSource.WORK), state.shownTodoSources)
        assertTrue(state.shownTodoRows.all { it.source == TodoSource.WORK })
    }

    @Test
    fun `a filter changed on the To-do screen reaches the tile at the next refresh, before any answer`() {
        profile = Profile.NICK
        val vm = viewModel()
        keepSubscribed(vm.state)
        assertEquals(TodoFilter.ALL, vm.state.value.todoFilter)

        savedFilter = TodoFilter.WORK
        vm.refresh()

        awaitValue(vm.state) { it.todoFilter == TodoFilter.WORK }
    }

    @Test
    fun `Jess's tile never asks for list=work, even with Work saved from Nick's days`() {
        profile = Profile.JESS
        mineHome = ListHome.PHONE
        savedFilter = TodoFilter.WORK
        val vm = viewModel()
        keepSubscribed(vm.state)
        vm.refresh()
        val state = awaitValue(vm.state) { it.familyTodos != null && it.shopping != null }

        assertEquals(TodoFilter.ALL, state.todoFilter)
        assertEquals(listOf(TodoSource.FAMILY, TodoSource.MINE), state.shownTodoSources)
        assertEquals(null, state.workTodos)
        val requests = drainRequests()
        assertTrue(requests.toString(), requests.none { "list=work" in it })
    }
}
