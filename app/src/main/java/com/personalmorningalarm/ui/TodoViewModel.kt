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
 * The To-do screen: two lists in one, Family and Mine, under an All | Family | Mine
 * filter, with a picker choosing which list an add goes to.
 *
 * Family is always the hub's family chalkboard. Mine is the person's own list, kept
 * on the hub (Nick's rolling to-do) or on the phone, as [mineHome] says. This class
 * builds the two [TodoListSource]s and from then on only calls them.
 */
class TodoViewModel(
    alfred: AlfredRepository,
    private val sync: ChalkboardSync,
    phone: PhoneListStore,
    val mineHome: ListHome
) : ViewModel() {

    private val _events = MutableSharedFlow<TodoEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<TodoEvent> = _events.asSharedFlow()

    private val family: TodoListSource =
        HubTodoSource(alfred, sync, ChalkboardList.FAMILY, viewModelScope, _events)

    private val mine: TodoListSource = when (mineHome) {
        ListHome.HUB -> HubTodoSource(alfred, sync, ChalkboardList.NICK, viewModelScope, _events)
        ListHome.PHONE -> PhoneTodoSource(phone, viewModelScope)
    }

    data class TodoState(
        val family: SourceState = SourceState(),
        val mine: SourceState = SourceState(),
        val filter: TodoFilter = TodoFilter.ALL,
        /** Where the next add goes. Follows the filter, until the picker changes it. */
        val addTarget: TodoSource = TodoBoard.addTarget(TodoFilter.ALL)
    ) {
        /** Both lists merged, whatever the filter. */
        val rows: List<TodoRow> get() = TodoBoard.merge(family.items, mine.items)

        /** What the filter lets through. */
        val shown: List<TodoRow> get() = TodoBoard.filter(rows, filter)

        fun source(which: TodoSource): SourceState = if (which == TodoSource.FAMILY) family else mine

        /** The lists the filter shows. */
        val shownSources: List<SourceState>
            get() = TodoSource.entries.filter(filter::shows).map(::source)
    }

    private val choice = MutableStateFlow(TodoFilter.ALL to TodoBoard.addTarget(TodoFilter.ALL))

    val state: StateFlow<TodoState> = combine(family.state, mine.state, choice) { f, m, (filter, target) ->
        TodoState(family = f, mine = m, filter = filter, addTarget = target)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TodoState())

    init {
        refresh()
    }

    fun refresh(quiet: Boolean = false) {
        family.refresh(quiet)
        mine.refresh(quiet)
    }

    /** Changing the filter puts the picker back on the list in view (Mine for All). */
    fun setFilter(filter: TodoFilter) {
        choice.value = filter to TodoBoard.addTarget(filter)
    }

    fun setAddTarget(target: TodoSource) {
        choice.value = choice.value.first to target
    }

    /** Adds to the list the picker is on. */
    fun add(text: String) {
        sourceFor(state.value.addTarget).add(text)
    }

    fun tick(row: TodoRow) = sourceFor(row.source).tick(row.item)

    fun drop(row: TodoRow) = sourceFor(row.source).drop(row.item)

    /** The user has read the conflict notice; the failed entries are done with. */
    fun dismissFailed() {
        viewModelScope.launch { sync.dismissFailed() }
    }

    private fun sourceFor(which: TodoSource): TodoListSource = if (which == TodoSource.FAMILY) family else mine
}
