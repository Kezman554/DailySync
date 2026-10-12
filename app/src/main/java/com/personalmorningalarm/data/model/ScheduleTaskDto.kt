package com.personalmorningalarm.data.model

/**
 * One task from Alfred's /daily-schedule. The API sends only these two fields —
 * there is no completion state and no timestamp, and this screen never writes back.
 *
 * Both fields are nullable because they come off the wire: [period] is genuinely
 * optional (an ungrouped task), and [task] is defensive against a malformed entry.
 */
data class ScheduleTaskDto(
    val task: String?,
    val period: String?
)

/**
 * The parts of the day Alfred groups tasks into, in the order they're shown —
 * chronological, with Anytime last. Exactly the five values vault-api emits; see
 * docs/schedule-period-contract.md in AlfredHomeHub. Never add one ahead of the API.
 */
enum class SchedulePeriod(val apiValue: String?) {
    EARLY("early"),
    MORNING("am"),
    AFTERNOON("pm"),
    EVENING("eve"),

    /** Tasks with no period, or one Alfred hasn't taught us about yet. */
    UNGROUPED(null);

    companion object {
        /** Maps an API value to a period; anything unrecognised is [UNGROUPED]. */
        fun fromApiValue(value: String?): SchedulePeriod {
            val key = value?.trim()?.lowercase() ?: return UNGROUPED
            return entries.firstOrNull { it.apiValue == key } ?: UNGROUPED
        }
    }
}
