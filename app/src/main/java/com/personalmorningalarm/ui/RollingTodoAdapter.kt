package com.personalmorningalarm.ui

import android.graphics.Paint
import androidx.annotation.StringRes
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.TodoRow
import com.personalmorningalarm.data.model.TodoSource
import com.personalmorningalarm.databinding.ItemRollingTodoBinding
import com.personalmorningalarm.util.VaultText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The To-do screen's rows, both lists. Tap ticks — the everyday verb, so it's the
 * lightest gesture — and long-press drops, deliberately heavier because "no longer
 * relevant" shouldn't be fat-fingerable (the fragment adds a confirm on top).
 *
 * Rows are interactive while [writable] and when the item carries its target: a
 * hub row its vault [RollingTodoItem.line], a phone row its id. Ticked items stay
 * listed, struck through and greyed, until the overnight clear. A ticked hub row
 * takes neither verb (the hub targets unticked lines only); a ticked phone row can
 * still be dropped. With [tagLists] (the All filter), family and work rows carry a
 * tag saying which list they're on; Mine's go untagged, as they always have.
 * The alarm-flow screen keeps its own read-only renderer and never uses this.
 */
class RollingTodoAdapter(
    private val onTick: (TodoRow) -> Unit,
    private val onDrop: (TodoRow) -> Unit
) : ListAdapter<TodoRow, RollingTodoAdapter.Holder>(DIFF) {

    /** Whether writes are currently offered. Rows go inert, not invisible, when false. */
    var writable: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    /** Whether family and work rows show their tag: only when every list is on screen. */
    var tagLists: Boolean = false
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

        fun bind(row: TodoRow) {
            val item = row.item
            binding.cbTodo.isChecked = item.done
            binding.tvTodoTask.text = VaultText.render(binding.root.context, item.task)
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
            val tag = if (tagLists) tagFor(row.source) else null
            val tagged = tag != null
            binding.tvTodoTag.isVisible = tagged
            if (tag != null) binding.tvTodoTag.setText(tag)
            binding.tvTodoPending.isVisible = item.pending
            binding.rowTodoMarks.isVisible = tagged || item.pending
            binding.root.alpha = if (item.done) DONE_ALPHA else 1f

            val canTick = writable && !item.done && (item.line != null || item.localId != null)
            val canDrop = writable && (item.localId != null || (item.line != null && !item.done))
            binding.root.isClickable = canTick
            binding.root.isLongClickable = canDrop
            binding.root.setOnClickListener(if (canTick) { _ -> onTick(row) } else null)
            binding.root.setOnLongClickListener(if (canDrop) { _ -> onDrop(row); true } else null)
        }
    }

    internal companion object {
        private const val DONE_ALPHA = 0.5f

        /** The tag a row on [source] carries in All; Mine is the default and carries none. */
        @StringRes
        fun tagFor(source: TodoSource): Int? = when (source) {
            TodoSource.FAMILY -> R.string.todo_tag_family
            TodoSource.WORK -> R.string.todo_tag_work
            TodoSource.MINE -> null
        }
        private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        private val SHORT_DATE_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yy", Locale.getDefault())

        val DIFF = object : DiffUtil.ItemCallback<TodoRow>() {
            // Text+date rather than the raw line: ticking rewrites the line, and
            // the row should update in place rather than leave-and-reappear.
            // Occurrence because identical rows are still separate rows; the list
            // because the same text can be on both.
            override fun areItemsTheSame(old: TodoRow, new: TodoRow): Boolean {
                if (old.source != new.source) return false
                val o = old.item
                val n = new.item
                return if (o.localId != null || n.localId != null) {
                    o.localId == n.localId
                } else {
                    o.task == n.task && o.date == n.date && o.occurrence == n.occurrence
                }
            }

            override fun areContentsTheSame(old: TodoRow, new: TodoRow): Boolean = old == new
        }
    }
}
