package com.personalmorningalarm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalmorningalarm.data.model.ChalkboardList
import com.personalmorningalarm.data.model.TodoBoard
import com.personalmorningalarm.data.model.TodoFilter
import com.personalmorningalarm.data.model.TodoRow
import com.personalmorningalarm.data.model.TodoSource
import com.personalmorningalarm.data.phone.ListHome
import com.personalmorningalarm.data.phone.PhoneListStore
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.ChalkboardSync
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The To-do screen: its lists in one, Family and Mine (and, on Nick's phone, Work),
 * under an All | Family | Mine | Work filter, with a picker choosing which list an
 * add goes to.
 *
 * Family is always the hub's family chalkboard. Mine is the person's own list, kept
 * on the hub (Nick's rolling to-do) or on the phone, as [mineHome] says. Work is
 * Nick's day-job list on the hub; a profile without it ([Profile.todoSources]) gets
 * no Work source at all, so nothing here can ask for it. This class builds the
 * [TodoListSource]s and from then on only calls them.
 *
 * The filter opens where it was last left ([savedFilter], resolved for [profile])
 * and every change is handed to [saveFilter], so it survives a restart.
 */
class TodoViewModel(
    alfred: AlfredRepository,
    private val sync: ChalkboardSync,
    phone: PhoneListStore,
    val mineHome: ListHome,
    val profile: Profile = Profile.NICK,
    savedFilter: TodoFilter? = null,
    private val saveFilter: (TodoFilter) -> Unit = {}
) : ViewModel() {

    private val _events = MutableSharedFlow<TodoEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<TodoEvent> = _events.asSharedFlow()

    /** The lists this profile has, in the filter's order. */
    val sources: List<TodoSource> = profile.todoSources

    /** The filter's positions for this profile. */
    val filters: List<TodoFilter> = profile.todoFilters

    private val lists: Map<TodoSource, TodoListSource> = sources.associateWith { source ->
        when (source) {
            TodoSource.FAMILY -> HubTodoSource(alfred, sync, ChalkboardList.FAMILY, viewModelScope, _events)
            TodoSource.MINE -> when (mineHome) {
                ListHome.HUB -> HubTodoSource(alfred, sync, ChalkboardList.NICK, viewModelScope, _events)
                ListHome.PHONE -> PhoneTodoSource(phone, viewModelScope)
            }
            TodoSource.WORK -> HubTodoSource(alfred, sync, ChalkboardList.WORK, viewModelScope, _events)
        }
    }

    data class TodoState(
        val family: SourceState = SourceState(),
        val mine: SourceState = SourceState(),
        /** Nick's work list; an empty, never-shown state on a profile without it. */
        val work: SourceState = SourceState(),
        val filter: TodoFilter = TodoFilter.ALL,
        /** Where the next add goes. Follows the filter, until the picker changes it. */
        val addTarget: TodoSource = TodoBoard.addTarget(TodoFilter.ALL),
        /** The lists this profile has. */
        val sources: List<TodoSource> = Profile.NICK.todoSources
    ) {
        /** Every list merged, whatever the filter. */
        val rows: List<TodoRow>
            get() = TodoBoard.merge(family.items, mine.items, work.items.takeIf { TodoSource.WORK in sources })

        /** What the filter lets through. */
        val shown: List<TodoRow> get() = TodoBoard.filter(rows, filter)

        fun source(which: TodoSource): SourceState = when (which) {
            TodoSource.FAMILY -> family
            TodoSource.MINE -> mine
            TodoSource.WORK -> work
        }

        /** The lists the filter shows. */
        val shownSources: List<SourceState>
            get() = sources.filter(filter::shows).map(::source)

        /** Every list this profile has, shown or not: the sync line and notice count them all. */
        val allSources: List<SourceState> get() = sources.map(::source)
    }

    private val initialFilter = profile.todoFilter(savedFilter)

    private val choice = MutableStateFlow(initialFilter to TodoBoard.addTarget(initialFilter))

    private fun stateOf(which: TodoSource): StateFlow<SourceState> =
        lists[which]?.state ?: MutableStateFlow(SourceState())

    val state: StateFlow<TodoState> = combine(
        stateOf(TodoSource.FAMILY),
        stateOf(TodoSource.MINE),
        stateOf(TodoSource.WORK),
        choice
    ) { f, m, w, (filter, target) ->
        TodoState(family = f, mine = m, work = w, filter = filter, addTarget = target, sources = sources)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        TodoState(filter = initialFilter, addTarget = TodoBoard.addTarget(initialFilter), sources = sources)
    )

    init {
        refresh()
    }

    fun refresh(quiet: Boolean = false) {
        lists.values.forEach { it.refresh(quiet) }
    }

    /** Changing the filter puts the picker back on the list in view (Mine for All), and is saved. */
    fun setFilter(filter: TodoFilter) {
        if (filter !in filters) return
        choice.value = filter to TodoBoard.addTarget(filter)
        saveFilter(filter)
    }

    fun setAddTarget(target: TodoSource) {
        if (target !in sources) return
        choice.value = choice.value.first to target
    }

    /** Adds to the list the picker is on. */
    fun add(text: String) {
        sourceFor(state.value.addTarget)?.add(text)
    }

    fun tick(row: TodoRow) {
        sourceFor(row.source)?.tick(row.item)
    }

    fun drop(row: TodoRow) {
        sourceFor(row.source)?.drop(row.item)
    }

    /** The user has read the conflict notice; the failed entries are done with. */
    fun dismissFailed() {
        viewModelScope.launch { sync.dismissFailed() }
    }

    private fun sourceFor(which: TodoSource): TodoListSource? = lists[which]
}
