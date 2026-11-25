package com.personalmorningalarm.data.remote

import com.personalmorningalarm.data.model.ShoppingItemDto

/**
 * What the hub said to a sharing call. Never a saved copy: moving a list depends on
 * what the hub holds right now, so these calls never fall back to the cache.
 */
sealed interface ShareFlagResult {
    data object Done : ShareFlagResult
    /** The list isn't on the hub (404). */
    data object Gone : ShareFlagResult
    data object Unreachable : ShareFlagResult
}

sealed interface UploadResult {
    data class Uploaded(val hubId: String) : UploadResult
    /** The hub answered and said no (a 4xx): nothing was made. */
    data class Refused(val code: Int) : UploadResult
    /** No answer, or one that can't be read. The upload may or may not have happened. */
    data object Unreachable : UploadResult
}

sealed interface PullBackResult {
    data class PulledBack(val items: List<ShoppingItemDto>) : PullBackResult
    /** 404: no such list. Either an earlier pull-back took it or it was deleted. */
    data object Gone : PullBackResult
    /** 409, or another refusal: the hub kept the list. */
    data class Refused(val code: Int) : PullBackResult
    /** No answer. The pull-back may or may not have happened. */
    data object Unreachable : PullBackResult
}

/** One list read live, for deciding a move. */
sealed interface LiveListResult {
    data class Found(val items: List<ShoppingItemDto>) : LiveListResult
    data object Gone : LiveListResult
    data object Unreachable : LiveListResult
}
