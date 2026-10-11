package com.personalmorningalarm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.personalmorningalarm.R
import com.personalmorningalarm.databinding.FragmentTilesBinding
import com.personalmorningalarm.databinding.ItemTileBinding

/**
 * The app's landing screen: a grid of tiles, one per thing you might want at a
 * glance. The alarm now lives one slot along in the bottom bar — this is where
 * the app opens.
 */
class TilesFragment : Fragment() {

    private var _binding: FragmentTilesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The schedule tile opens the week pager; the alarm content screen and
        // the Today screen stay today-only.
        liveTile(binding.tileDailySchedule, R.string.tile_daily_schedule) {
            findNavController().navigate(R.id.action_tiles_to_week)
        }
        liveTile(binding.tileRollingTodo, R.string.tile_rolling_todo) {
            openToday(TodaySection.CHALKBOARD)
        }
        liveTile(binding.tileShoppingLists, R.string.tile_shopping_lists) {
            findNavController().navigate(R.id.action_tiles_to_shoppingMenu)
        }

        liveTile(binding.tileInbox, R.string.tile_inbox) {
            findNavController().navigate(R.id.action_tiles_to_inbox)
        }

        liveTile(binding.tileKitchenSignIn, R.string.tile_kitchen_sign_in) {
            findNavController().navigate(R.id.action_tiles_to_presence)
        }

        comingSoonTile(binding.tileKitchenSync, R.string.tile_kitchen_sync)
        comingSoonTile(binding.tileKanban, R.string.tile_kanban)
    }

    private fun liveTile(tile: ItemTileBinding, @StringRes title: Int, onClick: () -> Unit) {
        tile.tvTileTitle.setText(title)
        tile.tvTileSubtitle.isVisible = false
        tile.root.setOnClickListener { onClick() }
    }

    /**
     * Greyed and inert. It gets no click listener at all rather than a disabled
     * one, so there's no tap to swallow and nothing to wire up by accident later.
     */
    private fun comingSoonTile(tile: ItemTileBinding, @StringRes title: Int) {
        tile.tvTileTitle.setText(title)
        tile.tvTileSubtitle.isVisible = true
        tile.tvTileSubtitle.setText(R.string.tile_coming_soon)
        tile.root.isClickable = false
        tile.root.isFocusable = false
        tile.root.alpha = COMING_SOON_ALPHA
    }

    private fun openToday(section: TodaySection) {
        findNavController().navigate(
            R.id.action_tiles_to_today,
            bundleOf(TodayFragment.ARG_SECTION to section.name)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val COMING_SOON_ALPHA = 0.4f
    }
}
