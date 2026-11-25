package com.personalmorningalarm.data.model

/**
 * One item from Alfred's /chalkboard: Nick's rolling to-do or the family chalkboard
 * ([ChalkboardList]).
 *
 * Ticked items stay in the file until the Pi's overnight sweep (03:07). The plain
 * read leaves them out; the app reads with `include_ticked=true`, which returns
 * them too, each item carrying [ticked], so the screen can grey the done ones.
 *
 * All fields are nullable because they come off the wire: [date] is genuinely
 * optional (absent on some items), [line] is absent from responses cached before
 * the API started sending it, and [task] is defensive against a malformed entry.
 */
data class ChalkboardTaskDto(
    val task: String?,
    val date: String?,
    /**
     * The item's raw markdown line in the vault, e.g. "- [ ] Fix fence (2026-07-05)".
     * This is the targeting key for tick/drop — sent back verbatim, never edited
     * locally beyond the optimistic "[ ]"→"[x]" flip.
     */
    val line: String? = null,
    /**
     * Whether the item is ticked, sent on every item when the read asks for
     * `include_ticked=true`. Absent (null) on the plain read and on older cached
     * copies; then the `[x]` in [line] is the answer.
     */
    val ticked: Boolean? = null
)
