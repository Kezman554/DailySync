package com.personalmorningalarm.data.remote

/**
 * Outcome of an Alfred fetch. Every Alfred-backed screen renders the same three
 * cases: live data, cached data from a previous fetch, or nothing at all.
 */
sealed interface AlfredResult<out T> {

    /** Fetched from Alfred just now. */
    data class Fresh<T>(val data: T) : AlfredResult<T>

    /** Alfred was unreachable; this is the last response it gave, [cachedAtMillis] ago. */
    data class Stale<T>(val data: T, val cachedAtMillis: Long) : AlfredResult<T>

    /** Alfred was unreachable and nothing was cached — the screen shows its fallback. */
    data object Unavailable : AlfredResult<Nothing>
}

/** The data, fresh or cached; null when Alfred gave nothing. */
fun <T> AlfredResult<T>.dataOrNull(): T? = when (this) {
    is AlfredResult.Fresh -> data
    is AlfredResult.Stale -> data
    AlfredResult.Unavailable -> null
}

/** Transforms the data, keeping whether it was fresh or cached (and when). */
fun <T, R> AlfredResult<T>.map(transform: (T) -> R): AlfredResult<R> = when (this) {
    is AlfredResult.Fresh -> AlfredResult.Fresh(transform(data))
    is AlfredResult.Stale -> AlfredResult.Stale(transform(data), cachedAtMillis)
    AlfredResult.Unavailable -> AlfredResult.Unavailable
}
