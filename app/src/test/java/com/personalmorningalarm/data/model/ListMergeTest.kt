package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.model.ListMerge.Counts
import com.personalmorningalarm.data.model.ListMerge.Mine
import com.personalmorningalarm.data.model.ListMerge.Theirs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Copy into…"'s rule, on its own: what a merge adds and ticks, and what it says. */
class ListMergeTest {

    private fun theirs(vararg items: Pair<String, Boolean>) = items.map { Theirs(it.first, it.second) }

    /** Your list, each item's ref its position, so a tick says which one. */
    private fun mine(vararg items: Pair<String, Boolean>) = items.mapIndexed { i, it -> Mine(i, it.first, it.second) }

    @Test
    fun `into an empty list everything arrives, ticks and all, in their order`() {
        val plan = ListMerge.plan(
            theirs("Lego set" to false, "Pyjamas" to true, "Book" to false),
            mine()
        )

        assertEquals(theirs("Lego set" to false, "Pyjamas" to true, "Book" to false), plan.adds)
        assertTrue(plan.ticks.isEmpty())
        assertEquals(Counts(added = 2, alreadyThere = 0, alreadyBought = 1), plan.counts)
    }

    @Test
    fun `an item already there is matched on its name, whatever its case and spacing`() {
        val plan = ListMerge.plan(
            theirs("  Hedge   SHEARS " to false, "Bird\tfeeder" to false, "Gloves" to false),
            mine("hedge shears" to false, "bird feeder" to false)
        )

        assertEquals(theirs("Gloves" to false), plan.adds)
        assertEquals(Counts(added = 1, alreadyThere = 2, alreadyBought = 0), plan.counts)
    }

    @Test
    fun `the name is the part before the dash, so a different note is still the same item`() {
        val plan = ListMerge.plan(
            theirs("Torch — the small one ~£12" to false, "Rope — 10m" to false),
            mine("torch" to false, "Rope" to false)
        )

        assertTrue(plan.adds.isEmpty())
        assertEquals(2, plan.counts.alreadyThere)
    }

    @Test
    fun `an item they've ticked that's on yours unticked is ticked there`() {
        val plan = ListMerge.plan(
            theirs("Wrapping paper" to true, "Tape" to false),
            mine("Tape" to false, "Wrapping Paper" to false)
        )

        assertEquals(listOf(1), plan.ticks)
        assertTrue(plan.adds.isEmpty())
        assertEquals(Counts(added = 0, alreadyThere = 1, alreadyBought = 1), plan.counts)
    }

    @Test
    fun `nothing of yours is ever unticked, and your extra items are left as they are`() {
        // Yours ticked, theirs not: still yours ticked. Yours alone: untouched.
        val plan = ListMerge.plan(
            theirs("Batteries" to false),
            mine("batteries" to true, "Only mine" to false, "Also mine, bought" to true)
        )

        assertTrue(plan.adds.isEmpty())
        assertTrue(plan.ticks.isEmpty())
        assertEquals(Counts(added = 0, alreadyThere = 1, alreadyBought = 0), plan.counts)
    }

    @Test
    fun `items the sharer has since deleted stay on your list`() {
        // The second merge, after they deleted Socks and added Scarf.
        val plan = ListMerge.plan(
            theirs("Hat" to false, "Scarf" to false),
            mine("Hat" to false, "Socks" to false)
        )

        assertEquals(theirs("Scarf" to false), plan.adds)
        assertTrue(plan.ticks.isEmpty())
        // A plan only adds and ticks: there's no way for it to name Socks at all.
        assertEquals(Counts(added = 1, alreadyThere = 1, alreadyBought = 0), plan.counts)
    }

    @Test
    fun `bought on both is already there, and nothing is ticked twice`() {
        val plan = ListMerge.plan(theirs("Candles" to true), mine("Candles" to true))

        assertTrue(plan.ticks.isEmpty())
        assertEquals(Counts(added = 0, alreadyThere = 1, alreadyBought = 0), plan.counts)
    }

    @Test
    fun `their repeats are one item, bought only if every copy is ticked`() {
        val plan = ListMerge.plan(
            theirs("Milk" to true, "milk" to false, "Bread" to true, "BREAD" to true),
            mine()
        )

        assertEquals(theirs("milk" to false, "Bread" to true), plan.adds)
        assertEquals(Counts(added = 1, alreadyThere = 0, alreadyBought = 1), plan.counts)
    }

    @Test
    fun `yours repeated, one of them ticked, counts as bought already`() {
        val plan = ListMerge.plan(theirs("Eggs" to true), mine("Eggs" to false, "eggs" to true))

        assertTrue(plan.ticks.isEmpty())
        assertEquals(1, plan.counts.alreadyThere)
    }

    @Test
    fun `yours repeated and all unticked, the first is the one ticked`() {
        val plan = ListMerge.plan(theirs("Eggs" to true), mine("Flour" to false, "Eggs" to false, "eggs" to false))

        assertEquals(listOf(1), plan.ticks)
    }

    @Test
    fun `their text is kept as it is, never renamed to match yours`() {
        val plan = ListMerge.plan(theirs("Fairy LIGHTS — warm white" to false), mine())

        assertEquals("Fairy LIGHTS — warm white", plan.adds.single().text)
    }

    @Test
    fun `wikilinks match on what they show, and blank items are skipped`() {
        val plan = ListMerge.plan(
            theirs("[[gift-ideas|Board game]]" to false, "   " to true),
            mine("board game" to false)
        )

        assertTrue(plan.adds.isEmpty())
        assertEquals(Counts(added = 0, alreadyThere = 1, alreadyBought = 0), plan.counts)
    }

    @Test
    fun `merging again after a full merge changes nothing`() {
        val theirs = theirs("A" to false, "B" to true, "C" to false)
        val first = ListMerge.plan(theirs, mine("C" to false))
        // Your list once the first plan has landed.
        val after = mine("C" to false, "A" to false, "B" to true)

        val second = ListMerge.plan(theirs, after)

        assertEquals(2, first.adds.size)
        assertTrue(second.adds.isEmpty())
        assertTrue(second.ticks.isEmpty())
    }
}
