package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.entity.ChalkboardVerb
import com.personalmorningalarm.data.entity.PendingChalkboardWrite
import com.personalmorningalarm.util.VaultText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One rolling to-do item, cleaned up and ready to render. */
data class RollingTodoItem(
    /** Wikilinks resolved; still carries emphasis markers for the renderer. */
    val task: String,
    /** Display date, or null when the item has none — never the string "null". */
    val date: String?,
    /** Raw vault line — the targeting key for tick/drop. Null on pre-line cached data. */
    val line: String? = null,
    /** Ticked in the vault, awaiting the overnight sweep. Shown struck through, not hidden. */
    val done: Boolean = false,
    /** Waiting in the offline queue — captured on the phone, not yet in the vault. */
    val pending: Boolean = false,
    /** The day it went on the list, when the vault's date parses; null otherwise. */
    val addedOn: LocalDate? = null
)

/**
 * Turns Alfred's /chalkboard array into display-ready items: entries with no text
 * dropped, wikilinks resolved, and the date formatted for the faint secondary line
 * (omitted entirely when absent, rather than rendered as an empty or "null" line).
 */
object RollingTodo {

    // Items are dated by day in the vault (e.g. 2026-04-08). Anything else is
    // shown as sent rather than guessed at or dropped.
    private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

    // "- [x]" at the start of the raw line. Done state lives in the markdown
    // itself — the API sends no separate flag.
    private val TICKED = Regex("""^\s*-\s*\[[xX]]""")

    fun items(tasks: List<ChalkboardTaskDto>): List<RollingTodoItem> =
        tasks
            // A task with no text is nothing to show, whatever its date.
            .filter { !it.task.isNullOrBlank() }
            .map {
                RollingTodoItem(
                    task = VaultText.stripWikiLinks(it.task!!.trim()),
                    date = displayDate(it.date),
                    addedOn = parseDate(it.date),
                    line = it.line,
                    done = it.line != null && TICKED.containsMatchIn(it.line)
                )
            }

    /**
     * The list as the user should see it: the last-known server list with their
     * queued edits laid over the top — adds appended, ticks flipped, drops gone —
     * each marked [RollingTodoItem.pending] so the not-yet-synced state is honest.
     * Failed entries are excluded: they're a notice, not part of the list.
     */
    fun merged(tasks: List<ChalkboardTaskDto>, pending: List<PendingChalkboardWrite>): List<RollingTodoItem> {
        var result = items(tasks)
        pending.filterNot { it.failed }.forEach { write ->
            result = when (ChalkboardVerb.fromName(write.verb)) {
                ChalkboardVerb.ADD -> result + RollingTodoItem(
                    task = VaultText.stripWikiLinks(write.text),
                    date = null,
                    line = null,
                    pending = true
                )
                ChalkboardVerb.TICK -> result.map {
                    if (it.line == write.line) it.copy(done = true, pending = true) else it
                }
                ChalkboardVerb.DROP -> result.filterNot { it.line == write.line }
                null -> result
            }
        }
        return result
    }

    /**
     * ISO dates render as "8 Apr 2026". A date in any other shape is passed
     * through as-is — it's the vault's text, and showing it is better than
     * dropping information because it didn't parse. Absent or blank gives null,
     * so the screen omits the line.
     */
    /**
     * How many days the oldest item still to do has been on the list, or null when
     * no open item carries a date. Ticked items don't count — they're done.
     */
    fun oldestOpenAgeDays(items: List<RollingTodoItem>, today: LocalDate): Long? =
        items.filterNot { it.done }
            .mapNotNull { it.addedOn }
            .minOrNull()
            ?.let { ChronoUnit.DAYS.between(it, today).coerceAtLeast(0) }

    private fun parseDate(raw: String?): LocalDate? = try {
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
    } catch (e: Exception) {
        null
    }

    private fun displayDate(raw: String?): String? {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) return null
        return try {
            LocalDate.parse(trimmed).format(DISPLAY_FORMAT)
        } catch (e: Exception) {
            trimmed
        }
    }
}
