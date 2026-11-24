package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.phone.ListHome
import com.personalmorningalarm.data.phone.PhoneListItem
import com.personalmorningalarm.data.phone.PhoneListSummary
import com.personalmorningalarm.data.phone.PhoneNote
import com.personalmorningalarm.util.VaultText
import java.time.LocalDate

/**
 * Phone-kept rows in the shapes the existing screens render, so a phone list looks
 * like a hub list. Items carry [ShoppingItem.localId] / [RollingTodoItem.localId]
 * in place of a vault line: that's what a tick or drop targets.
 */
object PhoneLists {

    fun shoppingItems(items: List<PhoneListItem>): List<ShoppingItem> = items.map {
        ShoppingItem(task = VaultText.resolveLinks(it.text), done = it.ticked, localId = it.id)
    }

    fun todoItems(items: List<PhoneListItem>): List<RollingTodoItem> = items.map {
        val added = LocalDate.ofEpochDay(it.addedOnEpochDay)
        RollingTodoItem(
            task = VaultText.resolveLinks(it.text),
            date = added.toString(),
            addedOn = added,
            done = it.ticked,
            localId = it.id
        )
    }

    /** The menu's tile for a phone list: the same counts a hub list's tile shows. */
    fun summary(list: PhoneListSummary): ShoppingListSummaryDto =
        ShoppingListSummaryDto(id = list.id, title = list.title, total = list.total, unticked = list.left)

    /** Newest first, as the store gives them; titled the way the hub titles its notes. */
    fun noteRows(notes: List<PhoneNote>): List<NoteRow> = notes.map { note ->
        val summary = Notes.summary(note.id, note.text)
        NoteRow(
            id = note.id,
            title = summary.title ?: note.id,
            preview = summary.preview.orEmpty(),
            home = ListHome.PHONE
        )
    }
}
