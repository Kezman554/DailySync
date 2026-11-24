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
import com.personalmorningalarm.data.phone.ListHome
import com.personalmorningalarm.databinding.ItemShoppingListBinding

/**
 * The Shopping grid: one tile per list (name, then "9 left" or "All done", with
 * "this phone" on a phone list), and a dashed New list tile last. The New list tile
 * is always tappable: a list can be made on the phone anywhere, and one for the hub
 * says so if Alfred can't be reached. A list tile opens on a tap and offers deletion
 * on a long press; the New list tile has no long press.
 */
class ShoppingListSummaryAdapter(
    private val onClick: (Tile.ListTile) -> Unit,
    private val onLongPress: (Tile.ListTile) -> Unit,
    private val onNewList: () -> Unit
) : ListAdapter<ShoppingListSummaryAdapter.Tile, ShoppingListSummaryAdapter.Holder>(DIFF) {

    sealed interface Tile {
        data class ListTile(val summary: ShoppingListSummaryDto, val home: ListHome = ListHome.HUB) : Tile
        data object NewList : Tile
    }

    /** Shows the hub's [lists] (null: none to show), then the phone's, then the New list tile. */
    fun submit(lists: List<ShoppingListSummaryDto>?, phoneLists: List<ShoppingListSummaryDto> = emptyList()) {
        submitList(
            lists.orEmpty().map { Tile.ListTile(it) } +
                phoneLists.map { Tile.ListTile(it, ListHome.PHONE) } +
                Tile.NewList
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemShoppingListBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        when (val tile = getItem(position)) {
            is Tile.ListTile -> holder.bindList(tile)
            Tile.NewList -> holder.bindNewList()
        }
    }

    inner class Holder(private val binding: ItemShoppingListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val secondary =
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        private val positive = MaterialColors.getColor(binding.root, R.attr.colorPositive)

        fun bindList(tile: Tile.ListTile) {
            val summary = tile.summary
            binding.root.setBackgroundResource(R.drawable.bg_card)
            binding.ivShoppingListBadge.setBackgroundResource(R.drawable.bg_icon_badge)
            binding.ivShoppingListBadge.setImageResource(R.drawable.ic_list)
            binding.tvShoppingListTitle.text = ShoppingList.shortTitle(summary.title ?: summary.id.orEmpty())

            val left = ShoppingList.itemsLeft(summary)
            val resources = binding.root.resources
            // Nothing left reads "All done" even with no items listed: the overnight
            // sweep removes bought items, so a finished list usually has none.
            val count = if (left > 0) {
                resources.getString(R.string.shopping_left, left)
            } else {
                resources.getString(R.string.shopping_all_done)
            }
            binding.tvShoppingListCount.text = when (tile.home) {
                ListHome.HUB -> count
                ListHome.PHONE -> resources.getString(R.string.shopping_tile_on_phone, count)
            }
            binding.tvShoppingListCount.setTextColor(if (left > 0) secondary else positive)

            binding.root.isEnabled = true
            binding.root.setOnClickListener { onClick(tile) }
            binding.root.setOnLongClickListener { onLongPress(tile); true }
        }

        fun bindNewList() {
            binding.root.setBackgroundResource(R.drawable.bg_tile_dashed)
            binding.ivShoppingListBadge.setBackgroundResource(R.drawable.bg_icon_badge_outline)
            binding.ivShoppingListBadge.setImageResource(R.drawable.ic_plus)
            binding.tvShoppingListTitle.setText(R.string.shopping_new_list_heading)
            binding.tvShoppingListCount.setText(R.string.shopping_new_list_ready)
            binding.tvShoppingListCount.setTextColor(secondary)

            binding.root.isEnabled = true
            binding.root.setOnClickListener { onNewList() }
            // A recycled holder may have been a list tile. The listener alone isn't
            // enough: clearing it leaves the view long-clickable.
            binding.root.setOnLongClickListener(null)
            binding.root.isLongClickable = false
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Tile>() {
            override fun areItemsTheSame(old: Tile, new: Tile): Boolean = when {
                old is Tile.ListTile && new is Tile.ListTile -> old.home == new.home && old.summary.id == new.summary.id
                else -> old is Tile.NewList && new is Tile.NewList
            }

            override fun areContentsTheSame(old: Tile, new: Tile): Boolean = old == new
        }
    }
}
