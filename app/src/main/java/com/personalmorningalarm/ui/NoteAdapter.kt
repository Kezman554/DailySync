package com.personalmorningalarm.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.NoteRow
import com.personalmorningalarm.databinding.ItemNoteBinding

/**
 * One note: title, a preview, and whether it's still on the phone. Set as plain text
 * and never through VaultText: a note isn't markup, so what's typed is what shows.
 */
class NoteAdapter(
    private val onClick: (NoteRow) -> Unit,
    private val onLongPress: (NoteRow) -> Unit
) : ListAdapter<NoteRow, NoteAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemNoteBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(note: NoteRow) {
            binding.tvNoteTitle.text = note.title
            binding.tvNotePreview.isVisible = note.preview.isNotBlank()
            binding.tvNotePreview.text = note.preview
            binding.tvNotePending.isVisible = note.pending
            binding.tvNoteRefused.isVisible = note.refused
            binding.root.setOnClickListener { onClick(note) }
            binding.root.setOnLongClickListener { onLongPress(note); true }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<NoteRow>() {
            override fun areItemsTheSame(old: NoteRow, new: NoteRow): Boolean = old.id == new.id

            override fun areContentsTheSame(old: NoteRow, new: NoteRow): Boolean = old == new
        }
    }
}

/**
 * The one confirmation for deleting a note, from the list or from emptying it in the
 * editor. [onKeep] runs on Cancel, and on dismissing it with Back or a tap outside.
 */
fun showNoteDeleteDialog(
    context: Context,
    title: String,
    onDelete: () -> Unit,
    onKeep: () -> Unit = {}
): AlertDialog =
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.note_delete_title)
        .setMessage(context.getString(R.string.note_delete_message, title))
        .setPositiveButton(R.string.delete) { _, _ -> onDelete() }
        .setNegativeButton(R.string.cancel) { _, _ -> onKeep() }
        .setOnCancelListener { onKeep() }
        .show()
