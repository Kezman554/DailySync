package com.personalmorningalarm.data.remote

import com.personalmorningalarm.data.model.ShoppingItemDto
import com.personalmorningalarm.data.model.ShoppingListSummaryDto

/** Outcome of a shopping-list add/tick/drop. Mirrors [AlfredWriteResult] — nothing here throws. */
sealed interface ShoppingWriteResult {

    /** The write landed. Callers refetch to reconcile rather than trusting local state. */
    data object Done : ShoppingWriteResult

    /**
     * The targeting line no longer matches the vault (edited elsewhere, or swept).
     * [current] is the list as it stands now, returned in the same round trip.
     */
    data class StaleTarget(val current: List<ShoppingItemDto>) : ShoppingWriteResult

    /** Alfred didn't take the write — unreachable, or an unexpected error. */
    data object Unreachable : ShoppingWriteResult
}

/**
 * Outcome of creating a shopping list. Deliberately its own type, not [ShoppingWriteResult]:
 * create-list is online-only (never queued) and its conflict is a name collision, not a
 * stale targeting line, so there's nothing to merge back in.
 */
sealed interface ShoppingCreateResult {
    data class Created(val list: ShoppingListSummaryDto) : ShoppingCreateResult
    /** A list with that (slugified) name already exists. */
    data object Conflict : ShoppingCreateResult
    /** Alfred didn't take the request — unreachable, or an unexpected error. */
    data object Unreachable : ShoppingCreateResult
}

/** Outcome of deleting a shopping list. Online-only like create, and likewise never queued. */
sealed interface ShoppingDeleteResult {
    /** The list is gone, whether this call removed it or it already had been. */
    data class Deleted(val remaining: List<ShoppingListSummaryDto>) : ShoppingDeleteResult
    /** Alfred didn't take the request — unreachable, or an unexpected error. The list stays. */
    data object Unreachable : ShoppingDeleteResult
}
