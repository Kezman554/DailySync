package com.personalmorningalarm.ui

import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.ShoppingList
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.model.ShoppingSharing
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.remote.ShoppingShare
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sharing, for whichever shopping screen is open: the menu, a hub list or a phone
 * list. One move at a time; [working] is true while it runs, and each finished call
 * comes out of [results] once.
 */
class ShoppingShareViewModel(private val share: ShoppingShare) : ViewModel() {

    data class Result(val outcome: ShoppingShare.Outcome, val title: String)

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private val _results = MutableSharedFlow<Result>(extraBufferCapacity = 2)
    val results: SharedFlow<Result> = _results.asSharedFlow()

    fun sharePhoneList(phoneListId: String, title: String) = act(title) { share.sharePhoneList(phoneListId) }

    fun setShared(list: ShoppingListSummaryDto, shared: Boolean) =
        act(list.title ?: list.id.orEmpty()) { share.setShared(list, shared) }

    /** Finishes any move left part-way. Quiet: nothing to report unless something moved. */
    fun resume() {
        viewModelScope.launch { share.resume() }
    }

    private fun act(title: String, block: suspend () -> ShoppingShare.Outcome) {
        if (_working.value) return
        _working.value = true
        viewModelScope.launch {
            // ShoppingShare finishes the move even if this scope goes; the result is for whoever stays.
            val outcome = block()
            _working.value = false
            _results.emit(Result(outcome, title))
        }
    }
}

// What the three shopping screens share about sharing: the confirm dialogs, the
// words for each outcome, and moving to the list's new screen once it has moved.

/** The other person's name, as the app writes it: who sharing shares with. */
fun Fragment.otherName(me: Profile?): String = getString((me ?: Profile.NICK).other.label())

/** The owner's name for a hub list, or the other person's if the owner is unknown here. */
fun Fragment.ownerName(list: ShoppingListSummaryDto, me: Profile?): String =
    getString((ShoppingSharing.ownerOf(list) ?: (me ?: Profile.NICK).other).label())

/** Asks before sharing a phone list: it leaves the phone. */
fun Fragment.confirmShare(title: String, me: Profile?, onConfirm: () -> Unit, onCancel: () -> Unit = {}) {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.shopping_share_title)
        .setMessage(getString(R.string.shopping_share_message, ShoppingList.shortTitle(title), otherName(me)))
        .setPositiveButton(R.string.shopping_share_confirm) { _, _ -> onConfirm() }
        .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
        .setOnCancelListener { onCancel() }
        .show()
}

/** Asks before unsharing: says whether the list comes back to the phone or stays on the hub. */
fun Fragment.confirmUnshare(
    list: ShoppingListSummaryDto,
    me: Profile,
    onConfirm: () -> Unit,
    onCancel: () -> Unit = {}
) {
    val title = ShoppingList.shortTitle(list.title ?: list.id.orEmpty())
    val pull = ShoppingSharing.toggle(me, list, shared = false) == ShoppingSharing.Toggle.PULL_BACK
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.shopping_unshare_title)
        .setMessage(
            getString(
                if (pull) R.string.shopping_unshare_pull_message else R.string.shopping_unshare_flag_message,
                title,
                otherName(me)
            )
        )
        .setPositiveButton(R.string.shopping_unshare_confirm) { _, _ -> onConfirm() }
        .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
        .setOnCancelListener { onCancel() }
        .show()
}

/** Says what came of a share call, in a snackbar on [root]. */
fun Fragment.showShareResult(root: View, result: ShoppingShareViewModel.Result) {
    val title = ShoppingList.shortTitle(result.title)
    val message = when (val outcome = result.outcome) {
        is ShoppingShare.Outcome.MovedToHub -> getString(R.string.shopping_share_moved_hub, title)
        is ShoppingShare.Outcome.MovedToPhone -> getString(
            if (outcome.hubConfirmedGone) R.string.shopping_share_moved_phone else R.string.shopping_share_moved_phone_unconfirmed,
            title
        )
        is ShoppingShare.Outcome.FlagSet ->
            getString(if (outcome.shared) R.string.shopping_share_flag_on else R.string.shopping_share_flag_off, title)
        ShoppingShare.Outcome.Unreachable -> getString(R.string.shopping_share_unreachable)
        ShoppingShare.Outcome.Unfinished -> getString(R.string.shopping_share_unfinished)
        ShoppingShare.Outcome.ChangesWaiting -> getString(R.string.shopping_share_changes_waiting)
        ShoppingShare.Outcome.NotOwner -> getString(R.string.shopping_share_not_owner)
        ShoppingShare.Outcome.Gone -> getString(R.string.shopping_share_gone)
        ShoppingShare.Outcome.Refused -> getString(R.string.shopping_share_refused)
        ShoppingShare.Outcome.Busy -> getString(R.string.shopping_share_busy)
    }
    Snackbar.make(root, message, Snackbar.LENGTH_LONG).show()
}

/** Replaces the current list screen ([current]) with the screen for where the list now lives. */
fun Fragment.openMovedList(outcome: ShoppingShare.Outcome, current: Int) {
    val (destination, id, title) = when (outcome) {
        is ShoppingShare.Outcome.MovedToHub -> Triple(R.id.shoppingListFragment, outcome.hubId, outcome.title)
        is ShoppingShare.Outcome.MovedToPhone -> Triple(R.id.phoneShoppingListFragment, outcome.phoneListId, outcome.title)
        else -> return
    }
    findNavController().navigate(
        destination,
        bundleOf(ShoppingListFragment.ARG_LIST_ID to id, ShoppingListFragment.ARG_LIST_TITLE to title),
        NavOptions.Builder().setPopUpTo(current, true).build()
    )
}
