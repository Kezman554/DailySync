package com.personalmorningalarm.ui

import com.personalmorningalarm.data.profile.Profile

/** One tile on Home, and how much room it takes. */
enum class HomeTile(val shape: Shape, val growsInColumn: Boolean = true) {
    SCHEDULE(Shape.WIDE),
    TODO(Shape.HALF),
    SHOPPING(Shape.HALF),
    /** Keeps its own height under the to-do, which takes the rest of the column. */
    NOTES(Shape.HALF, growsInColumn = false),
    INBOX(Shape.HALF),
    SIGN_IN(Shape.WIDE),
    KITCHEN_SYNC(Shape.COMING_SOON),
    KANBAN(Shape.COMING_SOON);

    enum class Shape { WIDE, HALF, COMING_SOON }
}

/**
 * The one place that says which tiles each profile shows, and in what order. To
 * change a profile's Home, change its list here; TilesFragment only lays out what
 * it is given, and TilesViewModel only fetches what a shown tile needs.
 *
 * Order is reading order. Half-width tiles next to each other share a two-column
 * bento, filled left, right, left, right; coming-soon tiles go two to a row.
 */
object ProfileTiles {

    fun tilesFor(profile: Profile?): List<HomeTile> = when (profile) {
        Profile.NICK -> listOf(
            HomeTile.SCHEDULE,
            HomeTile.TODO, HomeTile.SHOPPING,
            HomeTile.NOTES, HomeTile.INBOX,
            HomeTile.SIGN_IN,
            HomeTile.KITCHEN_SYNC, HomeTile.KANBAN
        )
        Profile.JESS -> listOf(
            HomeTile.TODO, HomeTile.SHOPPING,
            HomeTile.NOTES,
            HomeTile.KITCHEN_SYNC, HomeTile.KANBAN
        )
        // Before the first-run choice there's nothing to show, so nothing is fetched.
        null -> emptyList()
    }

    /** A row of Home, top to bottom. */
    sealed class Row {
        data class Wide(val tile: HomeTile) : Row()
        data class Bento(val left: List<HomeTile>, val right: List<HomeTile>) : Row()
        data class ComingSoon(val tiles: List<HomeTile>) : Row()
    }

    /** How [tiles] lay out: runs of one shape grouped into rows, order kept. */
    fun rows(tiles: List<HomeTile>): List<Row> {
        val rows = mutableListOf<Row>()
        var i = 0
        while (i < tiles.size) {
            val shape = tiles[i].shape
            var end = i
            while (end < tiles.size && tiles[end].shape == shape) end++
            val run = tiles.subList(i, end)
            when (shape) {
                HomeTile.Shape.WIDE -> run.forEach { rows += Row.Wide(it) }
                HomeTile.Shape.HALF -> rows += Row.Bento(
                    left = run.filterIndexed { index, _ -> index % 2 == 0 },
                    right = run.filterIndexed { index, _ -> index % 2 == 1 }
                )
                HomeTile.Shape.COMING_SOON -> run.chunked(2).forEach { rows += Row.ComingSoon(it) }
            }
            i = end
        }
        return rows
    }
}
