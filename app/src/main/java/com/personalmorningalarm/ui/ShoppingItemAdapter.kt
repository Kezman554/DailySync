package com.personalmorningalarm.ui

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.personalmorningalarm.data.model.ShoppingItem
import com.personalmorningalarm.databinding.ItemShoppingItemBinding
import com.personalmorningalarm.util.VaultText

/**
 * One shopping list's editable rows. Mirrors [RollingTodoAdapter] — tap ticks
 * (bought), long-press drops (no longer wanted) — minus the date, since shopping
 * items aren't dated. Each row splits the vault line into a bold name and a note.
 */
class ShoppingItemAdapter(
    private val onTick: (ShoppingItem) -> Unit,
    private val onDrop: (ShoppingItem) -> Unit
) : ListAdapter<ShoppingItem, ShoppingItemAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemShoppingItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemShoppingItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ShoppingItem) {
            binding.cbShopping.isChecked = item.done
            // Two lines: the name in bold, then whatever followed " — " as the note.
            binding.tvShoppingTask.text = VaultText.render(item.name)
            binding.tvShoppingTask.paintFlags = if (item.done) {
                binding.tvShoppingTask.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                binding.tvShoppingTask.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            binding.tvShoppingNote.isVisible = item.note != null
            binding.tvShoppingNote.text = item.note?.let(VaultText::render)
            binding.tvShoppingPending.isVisible = item.pending
            binding.root.alpha = if (item.done) DONE_ALPHA else 1f

            // A ticked item has had its verb — only drop remains for it.
            val targetable = item.line != null
            binding.root.isClickable = targetable && !item.done
            binding.root.isLongClickable = targetable
            binding.root.setOnClickListener(
                if (targetable && !item.done) { _ -> onTick(item) } else null
            )
            binding.root.setOnLongClickListener(
                if (targetable) { _ -> onDrop(item); true } else null
            )
        }
    }

    private companion object {
        const val DONE_ALPHA = 0.5f

        val DIFF = object : DiffUtil.ItemCallback<ShoppingItem>() {
            // Text rather than the raw line: ticking rewrites the line, and the
            // row should update in place rather than leave-and-reappear.
            override fun areItemsTheSame(old: ShoppingItem, new: ShoppingItem): Boolean =
                old.task == new.task

            override fun areContentsTheSame(old: ShoppingItem, new: ShoppingItem): Boolean =
                old == new
        }
    }
}
