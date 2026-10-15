package com.personalmorningalarm.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.ShoppingList
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.databinding.ItemShoppingListBinding

/**
 * The Shopping grid: one tile per list (name, then "9 left" or "All done"), and a
 * dashed New list tile last. The New list tile is only tappable while [canCreate]
 * — creating needs a live Alfred, and offline it says so instead.
 */
class ShoppingListSummaryAdapter(
    private val onClick: (ShoppingListSummaryDto) -> Unit,
    private val onNewList: () -> Unit
) : ListAdapter<ShoppingListSummaryAdapter.Tile, ShoppingListSummaryAdapter.Holder>(DIFF) {

    sealed interface Tile {
        data class ListTile(val summary: ShoppingListSummaryDto) : Tile
        data class NewList(val canCreate: Boolean) : Tile
    }

    /** Shows [lists] (null: none to show) followed by the New list tile. */
    fun submit(lists: List<ShoppingListSummaryDto>?, canCreate: Boolean) {
        submitList(lists.orEmpty().map { Tile.ListTile(it) } + Tile.NewList(canCreate))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemShoppingListBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        when (val tile = getItem(position)) {
            is Tile.ListTile -> holder.bindList(tile.summary)
            is Tile.NewList -> holder.bindNewList(tile.canCreate)
        }
    }

    inner class Holder(private val binding: ItemShoppingListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val secondary =
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        private val positive = MaterialColors.getColor(binding.root, R.attr.colorPositive)

        fun bindList(summary: ShoppingListSummaryDto) {
            binding.root.setBackgroundResource(R.drawable.bg_card)
            binding.ivShoppingListBadge.setBackgroundResource(R.drawable.bg_icon_badge)
            binding.ivShoppingListBadge.setImageResource(R.drawable.ic_list)
            binding.tvShoppingListTitle.text = ShoppingList.shortTitle(summary.title ?: summary.id.orEmpty())

            val total = summary.total ?: 0
            val left = summary.unticked ?: total
            val resources = binding.root.resources
            // Nothing left reads "All done" even with no items listed: the overnight
            // sweep removes bought items, so a finished list usually has none.
            binding.tvShoppingListCount.text = if (left > 0) {
                resources.getString(R.string.shopping_left, left)
            } else {
                resources.getString(R.string.shopping_all_done)
            }
            binding.tvShoppingListCount.setTextColor(if (left > 0) secondary else positive)

            binding.root.isEnabled = true
            binding.root.setOnClickListener { onClick(summary) }
        }

        fun bindNewList(canCreate: Boolean) {
            binding.root.setBackgroundResource(R.drawable.bg_tile_dashed)
            binding.ivShoppingListBadge.setBackgroundResource(R.drawable.bg_icon_badge_outline)
            binding.ivShoppingListBadge.setImageResource(R.drawable.ic_plus)
            binding.tvShoppingListTitle.setText(R.string.shopping_new_list_heading)
            binding.tvShoppingListCount.setText(
                if (canCreate) R.string.shopping_new_list_ready else R.string.shopping_new_list_offline
            )
            binding.tvShoppingListCount.setTextColor(secondary)

            binding.root.isEnabled = canCreate
            binding.root.setOnClickListener(if (canCreate) { _ -> onNewList() } else null)
            binding.root.isClickable = canCreate
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Tile>() {
            override fun areItemsTheSame(old: Tile, new: Tile): Boolean = when {
                old is Tile.ListTile && new is Tile.ListTile -> old.summary.id == new.summary.id
                else -> old is Tile.NewList && new is Tile.NewList
            }

            override fun areContentsTheSame(old: Tile, new: Tile): Boolean = old == new
        }
    }
}
