package com.personalmorningalarm.data.model

/*
 * Rows from a vault list can be identical, down to the raw line: two "- [ ] Milk"
 * are one targeting key for two rows. Alfred resolves a tick or drop to the FIRST
 * exact match in the file, so these helpers do the same to the local copy. One tap
 * then changes the row Alfred will change, and nothing else.
 *
 * A tick has to rewrite the line as well as flag the row. That is what moves the
 * next tick of the same line on to the next row, and it is what Alfred's file then
 * holds, so a later drop of the ticked row targets it correctly.
 */

/** "[ ]" → "[x]", as Alfred rewrites a ticked line. */
fun tickedLine(line: String): String = line.replaceFirst("[ ]", "[x]")

/** This list with [change] applied to its first row matching [matches], and to no other. */
inline fun <T> List<T>.updateFirst(matches: (T) -> Boolean, change: (T) -> T): List<T> {
    val index = indexOfFirst(matches)
    if (index < 0) return this
    return toMutableList().also { it[index] = change(it[index]) }
}

/** This list without its first row matching [matches]. */
inline fun <T> List<T>.withoutFirst(matches: (T) -> Boolean): List<T> {
    val index = indexOfFirst(matches)
    if (index < 0) return this
    return toMutableList().also { it.removeAt(index) }
}

/**
 * Hands each row its occurrence among rows sharing a [key], counting from 0, so that
 * identical rows stay distinct in a list diff. The line can't serve, as it is equally
 * identical and a tick rewrites it.
 */
inline fun <T> List<T>.numberRepeats(key: (T) -> Any?, number: (T, Int) -> T): List<T> {
    val seen = HashMap<Any?, Int>()
    return map { row ->
        val k = key(row)
        val occurrence = seen[k] ?: 0
        seen[k] = occurrence + 1
        number(row, occurrence)
    }
}
