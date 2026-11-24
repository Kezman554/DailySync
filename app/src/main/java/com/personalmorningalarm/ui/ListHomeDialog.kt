package com.personalmorningalarm.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.personalmorningalarm.R
import com.personalmorningalarm.data.phone.ListHome

/** The two places a list or note can live, in the order every chooser offers them. */
private val HOMES = listOf(ListHome.HUB, ListHome.PHONE)

@StringRes
fun ListHome.label(): Int = when (this) {
    ListHome.HUB -> R.string.list_home_hub
    ListHome.PHONE -> R.string.list_home_phone
}

/**
 * Asks where a new shopping list or note lives, starting on [initial]
 * ([com.personalmorningalarm.data.phone.ListSettings.defaultHome]). [onChosen] runs
 * on the positive button only: Cancel, Back or a tap outside makes nothing.
 */
fun showListHomeDialog(
    context: Context,
    initial: ListHome,
    @StringRes title: Int = R.string.list_home_question,
    @StringRes positive: Int = R.string.list_home_next,
    onChosen: (ListHome) -> Unit
): AlertDialog {
    var chosen = initial
    return MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setSingleChoiceItems(
            HOMES.map { context.getString(it.label()) }.toTypedArray(),
            HOMES.indexOf(initial)
        ) { _, which -> chosen = HOMES[which] }
        .setPositiveButton(positive) { _, _ -> onChosen(chosen) }
        .setNegativeButton(R.string.cancel, null)
        .show()
}
