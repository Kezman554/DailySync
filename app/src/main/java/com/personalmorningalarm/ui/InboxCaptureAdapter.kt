package com.personalmorningalarm.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.personalmorningalarm.data.model.InboxCapture
import com.personalmorningalarm.databinding.ItemInboxCaptureBinding
import com.personalmorningalarm.util.VaultText

/**
 * One inbox capture: title, when it was captured, and a preview of the body. No
 * click listener anywhere by design — the inbox is read-only from the phone, and
 * a row that responds to a tap would imply otherwise.
 */
class InboxCaptureAdapter : ListAdapter<InboxCapture, InboxCaptureAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemInboxCaptureBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemInboxCaptureBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(capture: InboxCapture) {
            binding.tvInboxTitle.text = capture.title
            binding.tvInboxCaptured.isVisible = capture.captured != null
            binding.tvInboxCaptured.text = capture.captured
            binding.tvInboxPreview.isVisible = capture.preview.isNotBlank()
            // The vault's markup rendered rather than shown raw ("**bold**").
            binding.tvInboxPreview.text = VaultText.render(capture.preview)
            binding.tvInboxPending.isVisible = capture.pending
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<InboxCapture>() {
            // No stable id from the API: a queued capture hasn't got a filename yet,
            // and the title is what it'll be filed under. Title + pending is as
            // close to identity as this list gets.
            override fun areItemsTheSame(old: InboxCapture, new: InboxCapture): Boolean =
                old.title == new.title && old.pending == new.pending

            override fun areContentsTheSame(old: InboxCapture, new: InboxCapture): Boolean =
                old == new
        }
    }
}
