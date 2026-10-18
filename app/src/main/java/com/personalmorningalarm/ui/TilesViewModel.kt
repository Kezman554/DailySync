package com.personalmorningalarm.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.DayAgenda
import com.personalmorningalarm.data.model.FamilyCalendar
import com.personalmorningalarm.data.model.RollingTodo
import com.personalmorningalarm.data.model.RollingTodoItem
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
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
 */
class TilesViewModel(
    private val alfred: AlfredRepository,
    private val sync: ChalkboardSync,
    private val presence: PresenceRepository,
    private val today: () -> LocalDate = LocalDate::now
) : ViewModel() {

    data class TilesState(
        val schedule: AlfredResult<DayAgenda>? = null,
        /** The calendar layered into [schedule], kept for its age alone — the card shows the older of the two. */
        val calendar: AlfredResult<*>? = null,
        val todos: AlfredResult<List<RollingTodoItem>>? = null,
        val shopping: AlfredResult<List<ShoppingListSummaryDto>>? = null,
        val presence: PresenceResult? = null
    )

    private val fetched = MutableStateFlow(Fetched())

    val state: StateFlow<TilesState> = combine(fetched, sync.queue) { f, pending ->
        TilesState(
            schedule = f.schedule,
            calendar = f.calendar,
            todos = f.chalkboard?.map { RollingTodo.merged(it, pending) },
            shopping = f.shopping,
            presence = f.presence
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TilesState())

    private var inFlight: Job? = null

    fun refresh() {
        if (inFlight?.isActive == true) return
        inFlight = viewModelScope.launch {
            coroutineScope {
                val date = today()
                val schedule = async { alfred.getDailySchedule() }
                val calendar = async { alfred.getCalendarDay(date) }
                val chalkboard = async { alfred.getChalkboard() }
                val shopping = async { alfred.getShoppingLists() }
                val signIn = async { presence.status() }

                val calendarResult = calendar.await()
                val events = FamilyCalendar.byDate(calendarResult.dataOrNull())[date].orEmpty()
                fetched.value = Fetched(
                    // The calendar is optional: with Alfred's plan unavailable there's
                    // no schedule to show, but a missing calendar just means vault only.
                    schedule = schedule.await().map { FamilyCalendar.agenda(it, events) },
                    calendar = calendarResult,
                    chalkboard = chalkboard.await(),
                    shopping = shopping.await(),
                    presence = signIn.await()
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
        val presence: PresenceResult? = null
    )

    private companion object {
        const val TAG = "PMA"
    }
}
