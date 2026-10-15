package com.personalmorningalarm.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.DayAgenda
import com.personalmorningalarm.data.model.RollingTodoItem
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.ChalkboardSync
import com.personalmorningalarm.data.remote.PresenceRepository
import com.personalmorningalarm.data.remote.PresenceResult
import com.personalmorningalarm.data.remote.dataOrNull
import com.personalmorningalarm.databinding.FragmentTilesBinding
import com.personalmorningalarm.databinding.ItemScheduleColumnBinding
import com.personalmorningalarm.util.MarkdownRenderer
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * The app's landing screen, under the shared header: a Daily Schedule card with
 * today's periods, then a bento of tiles that each open their own screen. The
 * alarm sits beside it under the header's Home | Alarm pills — this is where the
 * app opens.
 *
 * The tiles only read ([TilesViewModel], refreshed on every start); acting on
 * anything happens on the screen a tile opens.
 */
class TilesFragment : Fragment() {

    private var _binding: FragmentTilesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TilesViewModel by viewModels {
        viewModelFactory {
            initializer {
                val context = requireContext().applicationContext
                TilesViewModel(
                    AlfredRepository(context),
                    ChalkboardSync.getInstance(context),
                    PresenceRepository(context)
                )
            }
        }
    }

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

        // The schedule card opens the week pager; the alarm content screen and
        // the Today screen stay today-only.
        val openWeek = View.OnClickListener {
            findNavController().navigate(R.id.action_tiles_to_week)
        }
        binding.cardSchedule.setOnClickListener(openWeek)
        // The strip sits in a HorizontalScrollView, which swallows touches without
        // ever clicking. A clickable strip inside it gets the tap (the scroll view
        // still intercepts a drag), so the whole card opens the week.
        binding.scheduleStrip.setOnClickListener(openWeek)
        binding.cardTodo.setOnClickListener {
            findNavController().navigate(
                R.id.action_tiles_to_today,
                bundleOf(TodayFragment.ARG_SECTION to TodaySection.CHALKBOARD.name)
            )
        }
        binding.cardShopping.setOnClickListener {
            findNavController().navigate(R.id.action_tiles_to_shoppingMenu)
        }
        binding.cardInbox.setOnClickListener {
            findNavController().navigate(R.id.action_tiles_to_inbox)
        }
        val openSignIn = View.OnClickListener {
            findNavController().navigate(R.id.action_tiles_to_presence)
        }
        binding.cardSignIn.setOnClickListener(openSignIn)
        binding.btnSignIn.setOnClickListener(openSignIn)

        // KitchenSync and Kanban are inert: no click listener at all rather than a
        // disabled one, so there's no tap to swallow and nothing to wire up by
        // accident later.
        listOf(binding.tileKitchenSync, binding.tileKanban).forEach {
            it.isClickable = false
            it.isFocusable = false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderSchedule(state.schedule)
                    renderTodos(state.todos)
                    renderShopping(state.shopping)
                    renderSignIn(state.presence)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Every start: plans, lists and the wall's session all change elsewhere.
        viewModel.refresh()
    }

    private fun renderSchedule(result: AlfredResult<DayAgenda>?) {
        val agenda = result?.dataOrNull()
        val columns = agenda?.let { HomeTiles.scheduleColumns(it, LocalTime.now()) }.orEmpty()
        binding.scheduleStrip.removeAllViews()
        binding.scheduleScroll.isVisible = columns.isNotEmpty()
        columns.forEachIndexed { index, column ->
            val item = ItemScheduleColumnBinding.inflate(layoutInflater, binding.scheduleStrip, true)
            if (index < columns.lastIndex) {
                item.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    marginEnd = resources.getDimensionPixelSize(R.dimen.schedule_column_gap)
                }
            }
            item.bar.backgroundTintList = ColorStateList.valueOf(
                color(if (column.current) MaterialR.attr.colorPrimary else R.attr.colorSurface2)
            )
            item.tvPeriod.setText(ScheduleRenderer.periodHeading(column.period))
            item.tvPeriod.setTextColor(
                color(if (column.current) MaterialR.attr.colorPrimary else MaterialR.attr.colorOnSurfaceVariant)
            )
            val shown = column.entries.take(SCHEDULE_PREVIEW)
            val more = column.entries.size - shown.size
            item.tvTasks.text = buildString {
                append(shown.joinToString("\n"))
                if (more > 0) append("\n").append(getString(R.string.tiles_schedule_more, more))
            }
        }

        // When the strip scrolls, make sure the current period starts in view.
        val currentIndex = columns.indexOfFirst { it.current }
        binding.scheduleScroll.post {
            val scroll = _binding?.scheduleScroll ?: return@post
            val current = _binding?.scheduleStrip?.getChildAt(currentIndex)
            scroll.scrollTo(
                if (current != null && current.right > scroll.width) current.right - scroll.width else 0,
                0
            )
        }

        val note = when {
            result == null -> getString(R.string.schedule_loading)
            agenda == null -> getString(R.string.schedule_unavailable)
            columns.isEmpty() -> getString(R.string.schedule_empty)
            result is AlfredResult.Stale -> getString(R.string.schedule_stale)
            else -> null
        }
        binding.tvScheduleNote.isVisible = note != null
        binding.tvScheduleNote.text = note
    }

    private fun renderTodos(result: AlfredResult<List<RollingTodoItem>>?) {
        val items = result?.dataOrNull()?.let { HomeTiles.openTodos(it) }.orEmpty()
        binding.todoItems.removeAllViews()
        items.forEach { item ->
            val hairline = View(requireContext(), null, 0, R.style.DailySync_Hairline)
            binding.todoItems.addView(
                hairline,
                ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hairlineHeight())
                    .apply { topMargin = resources.getDimensionPixelSize(R.dimen.todo_item_gap) }
            )
            val row = TextView(requireContext(), null, 0, R.style.DailySync_TileListItem)
            row.text = MarkdownRenderer.render(item.task)
            binding.todoItems.addView(row)
        }

        val note = when {
            result == null -> getString(R.string.tiles_todo_loading)
            result is AlfredResult.Unavailable -> getString(R.string.tiles_todo_unavailable)
            items.isEmpty() -> getString(R.string.tiles_todo_empty)
            else -> null
        }
        binding.tvTodoNote.isVisible = note != null
        binding.tvTodoNote.text = note
    }

    private fun renderShopping(result: AlfredResult<List<ShoppingListSummaryDto>>?) {
        val lists = result?.dataOrNull()
        val active = lists?.let { HomeTiles.activeShoppingList(it) }
        binding.tvShoppingName.text = when {
            result == null -> getString(R.string.tiles_todo_loading)
            lists == null -> getString(R.string.tiles_shopping_unavailable)
            active == null -> getString(R.string.tiles_shopping_done)
            else -> active.title!!.trim()
        }
        binding.tvShoppingCount.isVisible = active != null
        binding.tvShoppingCount.text = active?.let { getString(R.string.tiles_shopping_left, it.unticked ?: 0) }
    }

    private fun renderSignIn(result: PresenceResult?) {
        val status = (result as? PresenceResult.Status)?.status
        val open = status?.open == true
        binding.tvSignInStatus.text = when {
            result == null -> getString(R.string.tiles_sign_in_checking)
            open -> status?.person?.takeIf { it.isNotBlank() }
                ?.let { getString(R.string.tiles_sign_in_open_as, it) }
                ?: getString(R.string.tiles_sign_in_open)
            result is PresenceResult.Unreachable || result is PresenceResult.Failed ->
                getString(R.string.tiles_sign_in_unreachable)
            else -> getString(R.string.tiles_sign_in_locked)
        }
        // Signing in is the tile's one action; once someone is in, the tile still
        // opens the screen (to sign out) but the pill has nothing to offer.
        binding.btnSignIn.isVisible = !open
    }

    private fun color(attr: Int): Int = MaterialColors.getColor(binding.root, attr)

    private fun hairlineHeight(): Int = resources.getDimensionPixelSize(R.dimen.hairline)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** Tasks shown per schedule column before "+N more". */
        const val SCHEDULE_PREVIEW = 3
    }
}
