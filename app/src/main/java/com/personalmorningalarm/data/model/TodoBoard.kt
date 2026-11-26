package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.phone.ListHome

/**
 * The To-do tile's lists. [FAMILY] is the hub's family chalkboard, which is also
 * on the kitchen wall. [MINE] is the person's own: Nick's rolling to-do on the hub,
 * or a list kept on the phone. [WORK] is Nick's day-job list on the hub, and only
 * his profile has it ([com.personalmorningalarm.data.profile.Profile.todoSources]).
 * Where each lives is the ViewModel's business; nothing here knows.
 */
enum class TodoSource { FAMILY, MINE, WORK }

/** The filter at the top of the To-do screen: All, or one list. */
enum class TodoFilter(
    /** The one list this position shows, or null for All. */
    val source: TodoSource?
) {
    ALL(null), FAMILY(TodoSource.FAMILY), MINE(TodoSource.MINE), WORK(TodoSource.WORK);

    fun shows(source: TodoSource): Boolean = this.source == null || this.source == source

    companion object {
        /** A saved name this build doesn't know is null: the profile then opens on All. */
        fun fromName(name: String?): TodoFilter? = entries.firstOrNull { it.name == name }
    }
}

/** One row of the To-do screen: an item and the list it's on. */
data class TodoRow(val item: RollingTodoItem, val source: TodoSource)

/** The line under the add field saying where a new item will go. */
enum class AddHint {
    /** Family: the kitchen wall. */
    WALL,
    /** Mine, kept on the phone. */
    PHONE,
    /** Mine, Nick's rolling to-do on the hub. */
    YOUR_LIST,
    /** Work: Nick's day-job list. */
    WORK
}

/**
 * What the To-do screen and its Home tile show, from its lists, with no
 * Android in sight so it can be tested on the JVM.
 */
object TodoBoard {

    /**
     * The lists as one, each keeping its own order. They are interleaved by the day
     * each item went on its list: the oldest dated head goes first, a tie going to
     * the earlier list (family, then mine, then work), and undated items (waiting to
     * sync) wait behind every dated one. An unknown list (null: never loaded, or not
     * this profile's) adds nothing.
     */
    fun merge(
        family: List<RollingTodoItem>?,
        mine: List<RollingTodoItem>?,
        work: List<RollingTodoItem>? = null
    ): List<TodoRow> {
        val lists = listOf(
            TodoSource.FAMILY to family.orEmpty(),
            TodoSource.MINE to mine.orEmpty(),
            TodoSource.WORK to work.orEmpty()
        )
        val next = IntArray(lists.size)
        val rows = ArrayList<TodoRow>(lists.sumOf { it.second.size })
        while (true) {
            var pick = -1
            for (k in lists.indices) {
                val items = lists[k].second
                if (next[k] >= items.size) continue
                if (pick < 0) {
                    pick = k
                    continue
                }
                val date = items[next[k]].addedOn ?: continue
                val best = lists[pick].second[next[pick]].addedOn
                if (best == null || date.isBefore(best)) pick = k
            }
            if (pick < 0) return rows
            rows += TodoRow(lists[pick].second[next[pick]++], lists[pick].first)
        }
    }

    fun filter(rows: List<TodoRow>, filter: TodoFilter): List<TodoRow> = rows.filter { filter.shows(it.source) }

    /**
     * Where the add picker starts: the list in view, and Mine when the filter is All.
     * A wrong pick then stays private rather than reaching the kitchen wall.
     */
    fun addTarget(filter: TodoFilter): TodoSource = filter.source ?: TodoSource.MINE

    /** What the hint under the add field says, for the list picked and where Mine lives. */
    fun addHint(target: TodoSource, mineHome: ListHome): AddHint = when {
        target == TodoSource.FAMILY -> AddHint.WALL
        target == TodoSource.WORK -> AddHint.WORK
        mineHome == ListHome.PHONE -> AddHint.PHONE
        else -> AddHint.YOUR_LIST
    }

    /** Items still to do on [source]'s list; ticked ones are waiting for the sweep. */
    fun openCount(rows: List<TodoRow>, source: TodoSource): Int =
        rows.count { it.source == source && !it.item.done }

    /** The first [count] items still to do, of the rows given: the Home tile's preview. */
    fun preview(rows: List<TodoRow>, count: Int = PREVIEW): List<TodoRow> =
        rows.filterNot { it.item.done }.take(count)

    const val PREVIEW = 3
}
