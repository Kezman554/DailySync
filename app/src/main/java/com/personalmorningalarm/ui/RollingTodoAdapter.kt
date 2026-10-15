package com.personalmorningalarm.ui

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.personalmorningalarm.data.model.RollingTodoItem
import com.personalmorningalarm.databinding.ItemRollingTodoBinding
import com.personalmorningalarm.util.VaultText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Today screen's editable rolling to-do. Tap ticks — the everyday verb, so
 * it's the lightest gesture — and long-press drops, deliberately heavier because
 * "no longer relevant" shouldn't be fat-fingerable (the fragment adds a confirm
 * on top).
 *
 * Rows are interactive while [writable] and when the item carries its targeting
 * [RollingTodoItem.line] — offline, the fragment's writes queue rather than
 * send, so writable now stays on. Ticked items stay listed, struck through,
 * until the Pi's overnight sweep removes them — the alarm-flow screen keeps its
 * own read-only renderer and never uses this.
 */
class RollingTodoAdapter(
    private val onTick: (RollingTodoItem) -> Unit,
    private val onDrop: (RollingTodoItem) -> Unit
) : ListAdapter<RollingTodoItem, RollingTodoAdapter.Holder>(DIFF) {

    /** Whether writes are currently offered. Rows go inert, not invisible, when false. */
    var writable: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemRollingTodoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemRollingTodoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RollingTodoItem) {
            binding.cbTodo.isChecked = item.done
            binding.tvTodoTask.text = VaultText.render(item.task)
            binding.tvTodoTask.paintFlags = if (item.done) {
                binding.tvTodoTask.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                binding.tvTodoTask.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            // "8 Apr" on the right; the year only when it isn't this one. A date the
            // vault wrote in some other shape is shown as it came.
            val shown = item.addedOn?.format(
                if (item.addedOn.year == LocalDate.now().year) SHORT_DATE else SHORT_DATE_YEAR
            ) ?: item.date
            binding.tvTodoDate.isVisible = shown != null
            binding.tvTodoDate.text = shown
            binding.tvTodoPending.isVisible = item.pending
            binding.root.alpha = if (item.done) DONE_ALPHA else 1f

            // A ticked item has had its verb — only drop remains for it.
            val targetable = writable && item.line != null
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
        val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        val SHORT_DATE_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yy", Locale.getDefault())

        val DIFF = object : DiffUtil.ItemCallback<RollingTodoItem>() {
            // Text+date rather than the raw line: ticking rewrites the line, and
            // the row should update in place rather than leave-and-reappear.
            override fun areItemsTheSame(old: RollingTodoItem, new: RollingTodoItem): Boolean =
                old.task == new.task && old.date == new.date

            override fun areContentsTheSame(old: RollingTodoItem, new: RollingTodoItem): Boolean =
                old == new
        }
    }
}
