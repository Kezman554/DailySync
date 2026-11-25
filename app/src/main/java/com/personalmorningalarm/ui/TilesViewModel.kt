package com.personalmorningalarm.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.DayAgenda
import com.personalmorningalarm.data.model.FamilyCalendar
import com.personalmorningalarm.data.model.PhoneLists
import com.personalmorningalarm.data.model.RollingTodo
import com.personalmorningalarm.data.model.RollingTodoItem
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
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
 * Feeds the Home tiles: today's schedule (vault plan + family calendar), the
 * rolling to-do with the offline queue laid over it, the shopping lists and the
 * kitchen sign-in status. Read-only — every tile opens its own screen to act.
 *
 * Everything is fetched together on [refresh] (each screen start). Nothing here
 * throws: Alfred unreachable comes back as a cached [AlfredResult.Stale] or
 * [AlfredResult.Unavailable], and the dashboard as [PresenceResult.Unreachable],
 * so one feed failing never blanks the others. A null field means "not read yet".
 *
 * Lists kept on the phone are read from [phone] as they change. When Settings keeps
 * the to-do on the phone ([todoHome]), the hub's rolling to-do isn't fetched at all.
 *
 * Only the feeds the shown [tiles] need are fetched: a profile without the Daily
 * Schedule card or the sign-in tile never asks for the plan, calendar or wall. Read
 * on every refresh, like [todoHome]: this ViewModel outlives the recreate that
 * follows choosing a profile.
 */
class TilesViewModel(
    private val alfred: AlfredRepository,
    private val sync: ChalkboardSync,
    private val presence: PresenceRepository,
    private val phone: PhoneListStore,
    private val tiles: () -> List<HomeTile> = { ProfileTiles.tilesFor(Profile.NICK) },
    private val todoHome: () -> ListHome = { ListHome.HUB },
    private val today: () -> LocalDate = LocalDate::now
) : ViewModel() {

    data class TilesState(
        val schedule: AlfredResult<DayAgenda>? = null,
        /** The calendar layered into [schedule], kept for its age alone — the card shows the older of the two. */
        val calendar: AlfredResult<*>? = null,
        val todos: AlfredResult<List<RollingTodoItem>>? = null,
        val shopping: AlfredResult<List<ShoppingListSummaryDto>>? = null,
        val presence: PresenceResult? = null,
        /** Which to-do the tile shows: the hub's [todos], or the phone's [phoneTodos]. */
        val todoHome: ListHome = ListHome.HUB,
        val phoneTodos: List<RollingTodoItem>? = null,
        /** The phone's own shopping lists, after the hub's in every listing. */
        val phoneShopping: List<ShoppingListSummaryDto> = emptyList()
    )

    private val fetched = MutableStateFlow(Fetched(todoHome = todoHome()))

    val state: StateFlow<TilesState> = combine(
        fetched,
        sync.queue,
        phone.todoItems(),
        phone.shoppingLists()
    ) { f, pending, phoneTodos, phoneShopping ->
        TilesState(
            schedule = f.schedule,
            calendar = f.calendar,
            todos = f.chalkboard?.map { RollingTodo.merged(it, pending) },
            shopping = f.shopping,
            presence = f.presence,
            todoHome = f.todoHome,
            phoneTodos = PhoneLists.todoItems(phoneTodos),
            phoneShopping = phoneShopping.map(PhoneLists::summary)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TilesState(todoHome = todoHome()))

    private var inFlight: Job? = null

    fun refresh() {
        if (inFlight?.isActive == true) return
        inFlight = viewModelScope.launch {
            coroutineScope {
                val date = today()
                // Read on every refresh: Settings may have moved the to-do since.
                val home = todoHome()
                val shows = tiles().toSet()
                val withSchedule = HomeTile.SCHEDULE in shows
                val schedule = async { if (withSchedule) alfred.getDailySchedule() else null }
                val calendar = async { if (withSchedule) alfred.getCalendarDay(date) else null }
                val chalkboard = async {
                    if (HomeTile.TODO in shows && home == ListHome.HUB) alfred.getChalkboard() else null
                }
                val shopping = async { if (HomeTile.SHOPPING in shows) alfred.getShoppingLists() else null }
                val signIn = async { if (HomeTile.SIGN_IN in shows) presence.status() else null }

                val calendarResult = calendar.await()
                val events = FamilyCalendar.byDate(calendarResult?.dataOrNull())[date].orEmpty()
                fetched.value = Fetched(
                    // The calendar is optional: with Alfred's plan unavailable there's
                    // no schedule to show, but a missing calendar just means vault only.
                    schedule = schedule.await()?.map { FamilyCalendar.agenda(it, events) },
                    calendar = calendarResult,
                    chalkboard = chalkboard.await(),
                    shopping = shopping.await(),
                    presence = signIn.await(),
                    todoHome = home
                )
                Log.d(TAG, "Home tiles refreshed")
            }
        }
    }

    private data class Fetched(
        val schedule: AlfredResult<DayAgenda>? = null,
        val calendar: AlfredResult<*>? = null,
        val chalkboard: AlfredResult<List<ChalkboardTaskDto>>? = null,
        val shopping: AlfredResult<List<ShoppingListSummaryDto>>? = null,
        val presence: PresenceResult? = null,
        val todoHome: ListHome = ListHome.HUB
    )

    private companion object {
        const val TAG = "PMA"
    }
}
