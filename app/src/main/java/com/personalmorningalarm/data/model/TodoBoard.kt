package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.phone.ListHome

/**
 * The To-do tile's two lists. [FAMILY] is the hub's family chalkboard, which is also
 * on the kitchen wall. [MINE] is the person's own: Nick's rolling to-do on the hub,
 * or a list kept on the phone. Where each lives is the ViewModel's business; nothing
 * here knows.
 */
enum class TodoSource { FAMILY, MINE }

/** The filter at the top of the To-do screen. */
enum class TodoFilter {
    ALL, FAMILY, MINE;

    fun shows(source: TodoSource): Boolean = when (this) {
        ALL -> true
        FAMILY -> source == TodoSource.FAMILY
        MINE -> source == TodoSource.MINE
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
    YOUR_LIST
}

/**
 * What the To-do screen and its Home tile show, from the two lists, with no
 * Android in sight so it can be tested on the JVM.
 */
object TodoBoard {

    /**
     * Both lists as one, each keeping its own order. The two are interleaved by the
     * day each item went on its list: a family item comes first unless the mine one
     * is strictly older, or the family one has no date and the mine one has. An
     * unknown list (null: never loaded) adds nothing.
     */
    fun merge(family: List<RollingTodoItem>?, mine: List<RollingTodoItem>?): List<TodoRow> {
        val f = family.orEmpty()
        val m = mine.orEmpty()
        val rows = ArrayList<TodoRow>(f.size + m.size)
        var i = 0
        var j = 0
        while (i < f.size || j < m.size) {
            val takeMine = when {
                i >= f.size -> true
                j >= m.size -> false
                else -> {
                    val fd = f[i].addedOn
                    val md = m[j].addedOn
                    if (fd == null) md != null else md != null && md.isBefore(fd)
                }
            }
            rows += if (takeMine) TodoRow(m[j++], TodoSource.MINE) else TodoRow(f[i++], TodoSource.FAMILY)
        }
        return rows
    }

    fun filter(rows: List<TodoRow>, filter: TodoFilter): List<TodoRow> = rows.filter { filter.shows(it.source) }

    /**
     * Where the add picker starts: the list in view, and Mine when the filter is All.
     * A wrong pick then stays private rather than reaching the kitchen wall.
     */
    fun addTarget(filter: TodoFilter): TodoSource = when (filter) {
        TodoFilter.FAMILY -> TodoSource.FAMILY
        TodoFilter.ALL, TodoFilter.MINE -> TodoSource.MINE
    }

    /** What the hint under the add field says, for the list picked and where Mine lives. */
    fun addHint(target: TodoSource, mineHome: ListHome): AddHint = when {
        target == TodoSource.FAMILY -> AddHint.WALL
        mineHome == ListHome.PHONE -> AddHint.PHONE
        else -> AddHint.YOUR_LIST
    }

    /** Items still to do on [source]'s list; ticked ones are waiting for the sweep. */
    fun openCount(rows: List<TodoRow>, source: TodoSource): Int =
        rows.count { it.source == source && !it.item.done }

    /** The first [count] items still to do, both lists merged: the Home tile's preview. */
    fun preview(rows: List<TodoRow>, count: Int = PREVIEW): List<TodoRow> =
        rows.filterNot { it.item.done }.take(count)

    const val PREVIEW = 3
}
