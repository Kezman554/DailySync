package com.personalmorningalarm.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.AlarmSound
import com.personalmorningalarm.databinding.ViewScreenTitleBinding
import com.personalmorningalarm.util.SoundPreviewPlayer

/** Fills in a pushed screen's title bar: the title, and the back arrow going up. */
fun Fragment.bindScreenTitle(titleBar: ViewScreenTitleBinding, @StringRes title: Int) {
    titleBar.tvScreenTitle.setText(title)
    titleBar.btnBack.setOnClickListener { findNavController().navigateUp() }
}

/**
 * Single-choice sound picker that previews each sound as it's tapped, saving
 * only on confirm. Preview stops when the dialog closes. Shared by the Wake-up
 * sound and Nuclear screens.
 */
fun showSoundPicker(
    context: Context,
    preview: SoundPreviewPlayer,
    @StringRes title: Int,
    sounds: List<AlarmSound>,
    currentKey: String,
    previewVolume: Float,
    onPick: (String) -> Unit
) {
    val names = sounds.map { it.displayName }.toTypedArray()
    var selected = sounds.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
    MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setSingleChoiceItems(names, selected) { _, which ->
            selected = which
            preview.play(context, sounds[which], previewVolume)
        }
        .setPositiveButton(R.string.save) { _, _ -> onPick(sounds[selected].key) }
        .setNegativeButton(R.string.cancel, null)
        .setOnDismissListener { preview.stop() }
        .show()
}
