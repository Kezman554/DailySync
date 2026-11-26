package com.personalmorningalarm.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.model.CalendarEventsDto
import com.personalmorningalarm.data.model.ChalkboardList
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.DayAgenda
import com.personalmorningalarm.data.model.FamilyCalendar
import com.personalmorningalarm.data.model.PhoneLists
import com.personalmorningalarm.data.model.RollingTodo
import com.personalmorningalarm.data.model.RollingTodoItem
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.model.TodoBoard
import com.personalmorningalarm.data.model.TodoRow
import com.personalmorningalarm.data.phone.ListHome
import com.personalmorningalarm.data.phone.PhoneListStore
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.ChalkboardSync
import com.personalmorningalarm.data.remote.dataOrNull
import com.personalmorningalarm.data.remote.map
import com.personalmorningalarm.data.remote.PresenceRepository
import com.personalmorningalarm.data.remote.PresenceResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Feeds the Home tiles: today's schedule (vault plan + family calendar) or, for
 * the Today card, today's family calendar alone; the
 * rolling to-do with the offline queue laid over it, the shopping lists and the
 * kitchen sign-in status. Read-only — every tile opens its own screen to act.
 *
 * Everything is fetched together on [refresh] (each screen start). Nothing here
 * throws: Alfred unreachable comes back as a cached [AlfredResult.Stale] or
 * [AlfredResult.Unavailable], and the dashboard as [PresenceResult.Unreachable],
 * so one feed failing never blanks the others. A null field means "not read yet".
 *
 * The To-do tile shows two lists: the hub's family chalkboard, and the person's own
 * (Mine), which lives where [mineHome] says. Lists kept on the phone are read from
 * [phone] as they change; with Mine on the phone, Nick's rolling to-do isn't fetched.
 *
 * Only the feeds the shown [tiles] need are fetched: a profile without the Daily
 * Schedule card or the sign-in tile never asks for the plan, calendar or wall. Read
 * on every refresh, like [mineHome]: this ViewModel outlives the recreate that
 * follows choosing a profile.
 */
class TilesViewModel(
    private val alfred: AlfredRepository,
    private val sync: ChalkboardSync,
    private val presence: PresenceRepository,
    private val phone: PhoneListStore,
    private val tiles: () -> List<HomeTile> = { ProfileTiles.tilesFor(Profile.NICK) },
    private val mineHome: () -> ListHome = { ListHome.HUB },
    private val today: () -> LocalDate = LocalDate::now
) : ViewModel() {

    data class TilesState(
        val schedule: AlfredResult<DayAgenda>? = null,
        /** The calendar layered into [schedule], kept for its age alone — the card shows the older of the two. */
        val calendar: AlfredResult<*>? = null,
        /** Today's family calendar for the Today card; null until read, or when the card isn't shown. */
        val today: AlfredResult<CalendarEventsDto>? = null,
        /** The family chalkboard, queued edits laid over it. */
        val familyTodos: AlfredResult<List<RollingTodoItem>>? = null,
        /** Nick's rolling to-do as Mine, queued edits laid over it; null when Mine is on the phone. */
        val mineTodos: AlfredResult<List<RollingTodoItem>>? = null,
        val shopping: AlfredResult<List<ShoppingListSummaryDto>>? = null,
        val presence: PresenceResult? = null,
        /** Where Mine lives: the hub's [mineTodos], or the phone's [phoneTodos]. */
        val mineHome: ListHome = ListHome.HUB,
        val phoneTodos: List<RollingTodoItem>? = null,
        /** The phone's own shopping lists, after the hub's in every listing. */
        val phoneShopping: List<ShoppingListSummaryDto> = emptyList()
    ) {
        /** Mine's items wherever it lives; null until known. */
        val mineItems: List<RollingTodoItem>?
            get() = if (mineHome == ListHome.PHONE) phoneTodos else mineTodos?.dataOrNull()

        /** Both lists merged, as the To-do screen's All shows them. */
        val todoRows: List<TodoRow> get() = TodoBoard.merge(familyTodos?.dataOrNull(), mineItems)
    }

    private val fetched = MutableStateFlow(Fetched(mineHome = mineHome()))

    val state: StateFlow<TilesState> = combine(
        fetched,
        sync.queue,
        phone.todoItems(),
        phone.shoppingLists()
    ) { f, pending, phoneTodos, phoneShopping ->
        TilesState(
            schedule = f.schedule,
            calendar = f.calendar,
            today = f.today,
            familyTodos = f.family?.map { RollingTodo.merged(it, pending, ChalkboardList.FAMILY) },
            mineTodos = f.mine?.map { RollingTodo.merged(it, pending, ChalkboardList.NICK) },
            shopping = f.shopping,
            presence = f.presence,
            mineHome = f.mineHome,
            phoneTodos = PhoneLists.todoItems(phoneTodos),
            phoneShopping = phoneShopping.map(PhoneLists::summary)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TilesState(mineHome = mineHome()))

    private var inFlight: Job? = null

    fun refresh() {
        if (inFlight?.isActive == true) return
        inFlight = viewModelScope.launch {
            coroutineScope {
                val date = today()
                // Read on every refresh: Settings may have moved the to-do since.
                val home = mineHome()
                val shows = tiles().toSet()
                val withSchedule = HomeTile.SCHEDULE in shows
                val withToday = HomeTile.TODAY in shows
                val schedule = async { if (withSchedule) alfred.getDailySchedule() else null }
                // One call serves both cards: the schedule layers it in, Today lists it.
                val calendar = async { if (withSchedule || withToday) alfred.getCalendarDay(date) else null }
                val withTodo = HomeTile.TODO in shows
                val family = async { if (withTodo) alfred.getChalkboard(ChalkboardList.FAMILY) else null }
                val mine = async {
                    if (withTodo && home == ListHome.HUB) alfred.getChalkboard(ChalkboardList.NICK) else null
                }
                val shopping = async { if (HomeTile.SHOPPING in shows) alfred.getShoppingLists() else null }
                val signIn = async { if (HomeTile.SIGN_IN in shows) presence.status() else null }

                val calendarResult = calendar.await()
                val events = FamilyCalendar.byDate(calendarResult?.dataOrNull())[date].orEmpty()
                fetched.value = Fetched(
                    // The calendar is optional: with Alfred's plan unavailable there's
                    // no schedule to show, but a missing calendar just means vault only.
                    schedule = schedule.await()?.map { FamilyCalendar.agenda(it, events) },
                    calendar = calendarResult.takeIf { withSchedule },
                    today = calendarResult.takeIf { withToday },
                    family = family.await(),
                    mine = mine.await(),
                    shopping = shopping.await(),
                    presence = signIn.await(),
                    mineHome = home
                )
                Log.d(TAG, "Home tiles refreshed")
            }
        }
    }

    private data class Fetched(
        val schedule: AlfredResult<DayAgenda>? = null,
        val calendar: AlfredResult<*>? = null,
        val today: AlfredResult<CalendarEventsDto>? = null,
        val family: AlfredResult<List<ChalkboardTaskDto>>? = null,
        val mine: AlfredResult<List<ChalkboardTaskDto>>? = null,
        val shopping: AlfredResult<List<ShoppingListSummaryDto>>? = null,
        val presence: PresenceResult? = null,
        val mineHome: ListHome = ListHome.HUB
    )

    private companion object {
        const val TAG = "PMA"
    }
}
