package com.personalmorningalarm.challenge

import com.personalmorningalarm.data.model.ContentType

/**
 * Decides which content screen (if any) fills each gap between NFC checkpoints.
 * Each enabled content type is shown at most once per morning (one quote, one
 * stretch, etc.), in display order across the first gaps; any further gaps get no
 * content rather than cycling the types again. Empty list -> no content.
 */
class ContentScreenManager(private val enabledTypes: List<ContentType>) {

    val hasContent: Boolean get() = enabledTypes.isNotEmpty()

    /** How many content screens this morning has (one per enabled type). */
    val count: Int get() = enabledTypes.size

    /** Content for the gap after the (0-based) checkpoint just completed, or null. */
    fun contentForGap(gapIndex: Int): ContentType? = enabledTypes.getOrNull(gapIndex)
}
