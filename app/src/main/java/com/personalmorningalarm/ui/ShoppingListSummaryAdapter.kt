package com.personalmorningalarm.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.Groceries
import com.personalmorningalarm.data.model.ShoppingList
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.model.ShoppingSharing
import com.personalmorningalarm.data.phone.ListHome
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.databinding.ItemShoppingListBinding

/**
 * The Shopping grid: one tile per list (name, then "9 left" or "All done", with
 * "this phone" on a phone list), and a dashed New list tile last. Where new lists
 * can live on the phone, the New list tile is always tappable: a list can be made
 * on the phone anywhere, and one for the hub says so if Alfred can't be reached.
 * Where they only go to the hub ([Tile.NewList.hubOnly], Nick's profile), it is the
 * tile from before phone lists: "Create on Alfred", and "Needs Alfred", untappable,
 * while he can't be reached. A hub list says "Shared" when it is, and the owner's
 * name when it's the other person's. A list tile opens on a tap and offers sharing
 * and deletion on a long press; the New list tile has no long press.
 *
 * Groceries (KitchenSync's) is one more list tile, after the hub's: "Family" where
 * the others name an owner, "KitchenSync unreachable" with no copy to count, and no
 * long press, since it has nothing to share, delete or copy.
 */
class ShoppingListSummaryAdapter(
    /** Whose phone this is: a hub list that's someone else's says so, and one shared says that. */
    private val me: Profile?,
    private val onClick: (Tile.ListTile) -> Unit,
    private val onLongPress: (Tile.ListTile) -> Unit,
    private val onNewList: () -> Unit
) : ListAdapter<ShoppingListSummaryAdapter.Tile, ShoppingListSummaryAdapter.Holder>(DIFF) {

    sealed interface Tile {
        data class ListTile(
            val summary: ShoppingListSummaryDto,
            val home: ListHome = ListHome.HUB,
            /** False when the list's service can't be reached and there's no copy to count. */
            val counted: Boolean = true
        ) : Tile
        data class NewList(val hubOnly: Boolean = false, val canCreate: Boolean = true) : Tile
    }

    /**
     * Shows the hub's [lists] (null: none to show), then [groceries] if given, then the
     * phone's, then the New list tile.
     */
    fun submit(
        lists: List<ShoppingListSummaryDto>?,
        phoneLists: List<ShoppingListSummaryDto> = emptyList(),
        newList: Tile.NewList = Tile.NewList(),
        groceries: Tile.ListTile? = null
    ) {
        submitList(
            lists.orEmpty().map { Tile.ListTile(it) } +
                listOfNotNull(groceries) +
                phoneLists.map { Tile.ListTile(it, ListHome.PHONE) } +
                newList
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemShoppingListBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        when (val tile = getItem(position)) {
            is Tile.ListTile -> holder.bindList(tile)
            is Tile.NewList -> holder.bindNewList(tile)
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
            val groceries = Groceries.isGroceries(summary.id)
            binding.tvShoppingListCount.text = when {
                groceries && !tile.counted -> resources.getString(R.string.shopping_groceries_tile_unreachable)
                groceries -> resources.getString(R.string.shopping_tile_family, count)
                tile.home == ListHome.PHONE -> resources.getString(R.string.shopping_tile_on_phone, count)
                me != null && !ShoppingSharing.isMine(summary, me) -> resources.getString(
                    R.string.shopping_tile_from,
                    resources.getString((ShoppingSharing.ownerOf(summary) ?: me.other).label()),
                    count
                )
                ShoppingSharing.isShared(summary) -> resources.getString(R.string.shopping_tile_shared, count)
                else -> count
            }
            binding.tvShoppingListCount.setTextColor(if (left > 0 || !tile.counted) secondary else positive)

            binding.root.isEnabled = true
            binding.root.setOnClickListener { onClick(tile) }
            if (groceries) {
                binding.root.setOnLongClickListener(null)
                binding.root.isLongClickable = false
            } else {
                binding.root.setOnLongClickListener { onLongPress(tile); true }
            }
        }

        fun bindNewList(tile: Tile.NewList) {
            binding.root.setBackgroundResource(R.drawable.bg_tile_dashed)
            binding.ivShoppingListBadge.setBackgroundResource(R.drawable.bg_icon_badge_outline)
            binding.ivShoppingListBadge.setImageResource(R.drawable.ic_plus)
            binding.tvShoppingListTitle.setText(R.string.shopping_new_list_heading)
            binding.tvShoppingListCount.setText(
                when {
                    !tile.hubOnly -> R.string.shopping_new_list_ready
                    tile.canCreate -> R.string.shopping_new_list_hub_ready
                    else -> R.string.shopping_new_list_offline
                }
            )
            binding.tvShoppingListCount.setTextColor(secondary)

            binding.root.isEnabled = tile.canCreate
            binding.root.setOnClickListener(if (tile.canCreate) { _ -> onNewList() } else null)
            binding.root.isClickable = tile.canCreate
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
