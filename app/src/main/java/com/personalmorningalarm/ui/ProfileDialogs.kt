package com.personalmorningalarm.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.personalmorningalarm.R
import com.personalmorningalarm.data.profile.Profile

/** The profiles in the order every chooser offers them. */
private val PROFILES = listOf(Profile.NICK, Profile.JESS)

@StringRes
fun Profile.label(): Int = when (this) {
    Profile.NICK -> R.string.profile_nick
    Profile.JESS -> R.string.profile_jess
}

/**
 * First run: whose phone this is. It can't be dismissed, since nothing on Home
 * shows until it's answered. A fragment, so it survives rotation; the choice goes
 * to [MainActivity.onProfileChosen].
 */
class ProfileChooserDialog : DialogFragment() {

    init {
        isCancelable = false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_setup_title)
            .setItems(PROFILES.map { getString(it.label()) }.toTypedArray()) { _, which ->
                (activity as? MainActivity)?.onProfileChosen(PROFILES[which])
            }
            .create()

    companion object {
        const val TAG = "profile_chooser"
    }
}

/** Settings' profile switch, starting on [initial]; [onChosen] runs on Save only. */
fun showProfileDialog(context: Context, initial: Profile, onChosen: (Profile) -> Unit): AlertDialog {
    var chosen = initial
    return MaterialAlertDialogBuilder(context)
        .setTitle(R.string.profile_dialog_title)
        .setSingleChoiceItems(
            PROFILES.map { context.getString(it.label()) }.toTypedArray(),
            PROFILES.indexOf(initial)
        ) { _, which -> chosen = PROFILES[which] }
        .setPositiveButton(R.string.save) { _, _ -> onChosen(chosen) }
        .setNegativeButton(R.string.cancel, null)
        .show()
}
