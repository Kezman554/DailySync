package com.personalmorningalarm.ui

import android.view.View
import android.widget.EditText
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.ListMerge
import com.personalmorningalarm.data.model.ShoppingList
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.phone.ListHome
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.remote.ShoppingCopy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Copy into…" for whichever shopping screen is open: the menu or a shared list.
 * One copy at a time; [working] is true while it runs, and each finished copy comes
 * out of [results] once.
 */
class ShoppingCopyViewModel(private val copy: ShoppingCopy) : ViewModel() {

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private val _results = MutableSharedFlow<ShoppingCopy.Outcome>(extraBufferCapacity = 2)
    val results: SharedFlow<ShoppingCopy.Outcome> = _results.asSharedFlow()

    suspend fun targets(source: ShoppingListSummaryDto): List<ShoppingCopy.Target> = copy.targets(source)

    fun copy(source: ShoppingListSummaryDto, target: ShoppingCopy.Target) {
        if (_working.value) return
        _working.value = true
        viewModelScope.launch {
            // ShoppingCopy finishes even if this scope goes; the result is for whoever stays.
            val outcome = copy.copy(source, target)
            _working.value = false
            _results.emit(outcome)
        }
    }
}

/**
 * Starts "Copy into…" for [source], a list someone shared with you: pick a new list
 * or one of your own, then copy. The menu and the list screen both start here.
 */
fun Fragment.startCopyInto(source: ShoppingListSummaryDto, me: Profile?, viewModel: ShoppingCopyViewModel) {
    if (me == null) {
        Snackbar.make(requireView(), R.string.shopping_copy_no_profile, Snackbar.LENGTH_LONG).show()
        return
    }
    if (viewModel.working.value) return
    val title = ShoppingList.shortTitle(source.title ?: source.id.orEmpty())
    viewLifecycleOwner.lifecycleScope.launch {
        val targets = viewModel.targets(source)
        if (view == null) return@launch
        // Where both kinds are offered, say which lists are on this phone.
        val mixed = targets.any { it is ShoppingCopy.Target.Hub } && targets.any { it is ShoppingCopy.Target.Phone }
        val labels = listOf(getString(R.string.shopping_copy_new_list)) + targets.map {
            val name = ShoppingList.shortTitle(it.title)
            if (mixed && it is ShoppingCopy.Target.Phone) getString(R.string.shopping_tile_on_phone, name) else name
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.shopping_copy_pick_title, title))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) askNewListName(source, title, me, viewModel)
                else viewModel.copy(source, targets[which - 1])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

/** The new list's name, offered as "<their list> (mine)". */
private fun Fragment.askNewListName(
    source: ShoppingListSummaryDto,
    title: String,
    me: Profile,
    viewModel: ShoppingCopyViewModel
) {
    val pad = (16 * resources.displayMetrics.density).toInt()
    val input = EditText(requireContext()).apply {
        setText(getString(R.string.shopping_copy_new_default, title))
        setSelection(text.length)
        setSingleLine()
        setPadding(pad, pad / 2, pad, pad / 2)
    }
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.shopping_copy_new_title)
        .setMessage(
            when (me.unsharedListHome) {
                ListHome.HUB -> R.string.shopping_copy_new_message_hub
                ListHome.PHONE -> R.string.shopping_copy_new_message_phone
            }
        )
        .setView(input)
        .setPositiveButton(R.string.shopping_copy_confirm) { _, _ ->
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) viewModel.copy(source, ShoppingCopy.Target.NewList(name))
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/** "Added 3, 5 already there, 2 already bought by Jess": the one line after a copy. */
fun Fragment.copyLine(counts: ListMerge.Counts, sharer: String): String =
    listOfNotNull(
        getString(R.string.shopping_copy_added, counts.added),
        getString(R.string.shopping_copy_already_there, counts.alreadyThere).takeIf { counts.alreadyThere > 0 },
        getString(R.string.shopping_copy_already_bought, counts.alreadyBought, sharer).takeIf { counts.alreadyBought > 0 }
    ).joinToString(", ")

/** Says what came of a copy, in a snackbar on [root], with Open when there's a list to open. */
fun Fragment.showCopyResult(root: View, outcome: ShoppingCopy.Outcome, me: Profile?) {
    val (message, open) = when (outcome) {
        is ShoppingCopy.Outcome.Done -> {
            val sharer = getString((outcome.sharer ?: (me ?: Profile.NICK).other).label())
            copyLine(outcome.counts, sharer) to outcome
        }
        is ShoppingCopy.Outcome.Unfinished ->
            getString(R.string.shopping_copy_unfinished, ShoppingList.shortTitle(outcome.title)) to null
        ShoppingCopy.Outcome.Unreachable -> getString(R.string.shopping_copy_unreachable) to null
        ShoppingCopy.Outcome.NameTaken -> getString(R.string.shopping_copy_name_taken) to null
        ShoppingCopy.Outcome.Gone -> getString(R.string.shopping_copy_gone) to null
        ShoppingCopy.Outcome.NoProfile -> getString(R.string.shopping_copy_no_profile) to null
    }
    val bar = Snackbar.make(root, message, Snackbar.LENGTH_LONG)
    if (open != null) {
        bar.setAction(R.string.shopping_copy_open) {
            if (view == null) return@setAction
            findNavController().navigate(
                when (open.home) {
                    ListHome.HUB -> R.id.shoppingListFragment
                    ListHome.PHONE -> R.id.phoneShoppingListFragment
                },
                bundleOf(ShoppingListFragment.ARG_LIST_ID to open.listId, ShoppingListFragment.ARG_LIST_TITLE to open.title)
            )
        }
    }
    bar.show()
}
