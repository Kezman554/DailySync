package com.personalmorningalarm.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.fragment.findNavController
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.DayAgenda
import com.personalmorningalarm.data.model.TodoBoard
import com.personalmorningalarm.data.model.TodoRow
import com.personalmorningalarm.data.model.TodoSource
import com.personalmorningalarm.data.model.ShoppingList
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.phone.ListHome
import com.personalmorningalarm.data.phone.ListSettings
import com.personalmorningalarm.data.phone.PhoneListStore
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.profile.ProfileSettings
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.ChalkboardSync
import com.personalmorningalarm.data.remote.PresenceRepository
import com.personalmorningalarm.data.remote.PresenceResult
import com.personalmorningalarm.data.remote.dataOrNull
import com.personalmorningalarm.databinding.FragmentTilesBinding
import com.personalmorningalarm.util.MarkdownRenderer
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The app's landing screen, under the shared header: a Daily Schedule card with
 * today's periods, then a bento of tiles that each open their own screen. The
 * alarm sits beside it under the header's Home | Alarm pills — this is where the
 * app opens.
 *
 * The tiles only read ([TilesViewModel], refreshed on every start); acting on
 * anything happens on the screen a tile opens.
 *
 * Which tiles show, and in what order, is the profile's ([ProfileTiles]); this
 * screen only arranges what it's given.
 */
class TilesFragment : Fragment() {

    private var _binding: FragmentTilesBinding? = null
    private val binding get() = _binding!!

    /** This profile's tiles, in order. A profile change recreates the view, so read once here. */
    private val tiles: List<HomeTile> by lazy { profileTiles(requireContext()) }

    /** The tile that stands tall in this profile's bento, if any. */
    private val tall: HomeTile? by lazy { ProfileTiles.tallFor(ProfileSettings(requireContext()).profile()) }

    private val viewModel: TilesViewModel by viewModels {
        viewModelFactory {
            initializer {
                val context = requireContext().applicationContext
                val lists = ListSettings(context)
                val profiles = ProfileSettings(context)
                TilesViewModel(
                    AlfredRepository(context),
                    ChalkboardSync.getInstance(context),
                    PresenceRepository(context),
                    PhoneListStore.getInstance(context),
                    // Not [tiles]: the ViewModel outlives the recreate a profile change makes.
                    tiles = { profileTiles(context) },
                    mineHome = { (profiles.profile() ?: Profile.NICK).mineTodoHome(lists.todoHome()) }
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
        arrangeTiles()

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
            findNavController().navigate(R.id.action_tiles_to_todo)
        }
        binding.cardShopping.setOnClickListener {
            findNavController().navigate(R.id.action_tiles_to_shoppingMenu)
        }
        binding.cardInbox.setOnClickListener {
            findNavController().navigate(R.id.action_tiles_to_inbox)
        }
        binding.cardNotes.setOnClickListener {
            findNavController().navigate(R.id.action_tiles_to_notes)
        }
        val openSignIn = View.OnClickListener {
            findNavController().navigate(R.id.action_tiles_to_presence)
        }
        binding.cardSignIn.setOnClickListener(openSignIn)
        binding.btnSignIn.setOnClickListener(openSignIn)
        // Today and Calendar both open the family agenda.
        val openCalendar = View.OnClickListener {
            findNavController().navigate(R.id.action_tiles_to_calendar)
        }
        binding.cardToday.setOnClickListener(openCalendar)
        binding.cardCalendar.setOnClickListener(openCalendar)

        // The coming-soon tiles are inert: no click listener at all rather than a
        // disabled one, so there's no tap to swallow and nothing to wire up by
        // accident later.
        listOf(binding.tileKitchenSync, binding.tileKanban, binding.tileMeals).forEach {
            it.isClickable = false
            it.isFocusable = false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderToday(state)
                    renderSchedule(state.schedule)
                    renderTodos(state)
                    renderShopping(state.shopping, state.phoneShopping)
                    renderSignIn(state.presence)
                    renderFreshness(state)
                }
            }
        }
        FreshnessWatch.start(
            viewLifecycleOwner,
            render = { renderFreshness(viewModel.state.value) },
            wantsRetry = { ages(viewModel.state.value).any(Freshness::wantsRetry) },
            retry = viewModel::refresh
        )
    }

    override fun onStart() {
        super.onStart()
        // Every start: plans, lists and the wall's session all change elsewhere.
        viewModel.refresh()
    }

    /**
     * Lays out [tiles] as [ProfileTiles.rows] says: each tile view moves from the
     * flat list in the layout into its row, and a tile the profile doesn't show is
     * left out. Gaps are [R.dimen.tile_gap] across and down. In the bento a tile
     * that grows shares its column's spare height; the others keep their own.
     */
    private fun arrangeTiles() {
        val views: Map<HomeTile, View> = mapOf(
            HomeTile.TODAY to binding.cardToday,
            HomeTile.SCHEDULE to binding.cardSchedule,
            HomeTile.TODO to binding.cardTodo,
            HomeTile.SHOPPING to binding.cardShopping,
            HomeTile.NOTES to binding.cardNotes,
            HomeTile.INBOX to binding.cardInbox,
            HomeTile.SIGN_IN to binding.cardSignIn,
            HomeTile.KITCHEN_SYNC to binding.tileKitchenSync,
            HomeTile.KANBAN to binding.tileKanban,
            HomeTile.CALENDAR to binding.cardCalendar,
            HomeTile.MEALS to binding.tileMeals
        )
        val container = binding.tileRows
        container.removeAllViews()
        val gap = resources.getDimensionPixelSize(R.dimen.tile_gap)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        fun row() = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }

        fun column(column: List<HomeTile>) = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            column.forEachIndexed { index, tile ->
                val grows = tile.growsInColumn
                addView(
                    views.getValue(tile),
                    LinearLayout.LayoutParams(match, if (grows) 0 else wrap, if (grows) 1f else 0f)
                        .apply { if (index > 0) topMargin = gap }
                )
            }
        }

        /** A half-width cell: [view], or an empty one holding the place of a missing tile. */
        fun LinearLayout.addHalf(view: View?, first: Boolean, height: Int) = addView(
            view ?: Space(requireContext()),
            LinearLayout.LayoutParams(0, height, 1f).apply { if (!first) marginStart = gap }
        )

        ProfileTiles.rows(tiles, tall).forEachIndexed { index, plan ->
            val rowView: View = when (plan) {
                is ProfileTiles.Row.Wide -> views.getValue(plan.tile)
                is ProfileTiles.Row.Bento -> row().apply {
                    addHalf(column(plan.left), first = true, height = match)
                    addHalf(plan.right.takeIf { it.isNotEmpty() }?.let(::column), first = false, height = match)
                }
                is ProfileTiles.Row.ComingSoon -> row().apply {
                    addHalf(views.getValue(plan.tiles[0]), first = true, height = wrap)
                    addHalf(plan.tiles.getOrNull(1)?.let(views::getValue), first = false, height = wrap)
                }
            }
            container.addView(
                rowView,
                LinearLayout.LayoutParams(match, wrap).apply { if (index > 0) topMargin = gap }
            )
        }
    }

    private fun profileTiles(context: android.content.Context): List<HomeTile> =
        ProfileTiles.tilesFor(ProfileSettings(context).profile())

    /**
     * Today's family events, or why there are none: still asking, nothing on, or the
     * hub out of reach with no copy of today. Never blank.
     */
    private fun renderToday(state: TilesViewModel.TilesState) {
        if (HomeTile.TODAY !in tiles) return
        val today = HomeTiles.today(state.today, LocalDate.now())
        val events = (today as? HomeTiles.Today.Events)?.events.orEmpty()
        binding.todayRows.isVisible = events.isNotEmpty()
        EventRows.render(binding.todayRows, events)
        val note = when (today) {
            HomeTiles.Today.Loading -> getString(R.string.schedule_loading)
            HomeTiles.Today.Unreachable -> getString(R.string.today_unreachable)
            is HomeTiles.Today.Events -> if (events.isEmpty()) getString(R.string.today_empty) else null
        }
        binding.tvTodayNote.isVisible = note != null
        binding.tvTodayNote.text = note
    }

    private fun renderSchedule(result: AlfredResult<DayAgenda>?) {
        val agenda = result?.dataOrNull()
        val columns = agenda?.let { HomeTiles.scheduleColumns(it, LocalTime.now()) }.orEmpty()
        binding.scheduleScroll.isVisible = columns.isNotEmpty()
        ScheduleStrip.render(binding.scheduleStrip, binding.scheduleScroll, columns)

        val note = when {
            result == null -> getString(R.string.schedule_loading)
            agenda == null -> getString(R.string.schedule_unavailable)
            columns.isEmpty() -> getString(R.string.schedule_empty)
            else -> null
        }
        binding.tvScheduleNote.isVisible = note != null
        binding.tvScheduleNote.text = note
    }

    /** Both lists' open counts, then the first few open items, family ones tagged. */
    private fun renderTodos(state: TilesViewModel.TilesState) {
        val family = state.familyTodos?.dataOrNull()
        val mine = state.mineItems
        val rows = state.todoRows
        val preview = TodoBoard.preview(rows)

        binding.tvTodoCounts.isVisible = family != null || mine != null
        binding.tvTodoCounts.text = getString(
            R.string.tiles_todo_counts,
            family?.let { TodoBoard.openCount(rows, TodoSource.FAMILY).toString() } ?: getString(R.string.todo_stat_unknown),
            mine?.let { TodoBoard.openCount(rows, TodoSource.MINE).toString() } ?: getString(R.string.todo_stat_unknown)
        )

        binding.todoItems.removeAllViews()
        preview.forEach { row ->
            val hairline = View(requireContext(), null, 0, R.style.DailySync_Hairline)
            binding.todoItems.addView(
                hairline,
                ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hairlineHeight())
                    .apply { topMargin = resources.getDimensionPixelSize(R.dimen.todo_item_gap) }
            )
            binding.todoItems.addView(previewRow(row))
        }

        val note = when {
            // Neither list known yet: still asking, or (no copy anywhere) never loaded.
            family == null && mine == null -> getString(
                if (state.familyTodos is AlfredResult.Unavailable) {
                    R.string.tiles_todo_unavailable
                } else {
                    R.string.tiles_todo_loading
                }
            )
            preview.isEmpty() -> getString(R.string.tiles_todo_empty)
            else -> null
        }
        binding.tvTodoNote.isVisible = note != null
        binding.tvTodoNote.text = note
    }

    /** One preview line: the task, and a Family tag after it when it's on the family list. */
    private fun previewRow(row: TodoRow): View {
        val text = TextView(requireContext(), null, 0, R.style.DailySync_TileListItem)
        text.text = MarkdownRenderer.render(requireContext(), row.item.task)
        if (row.source != TodoSource.FAMILY) return text
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                TextView(requireContext(), null, 0, R.style.DailySync_Tag),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = resources.getDimensionPixelSize(R.dimen.todo_item_gap) }
            )
        }
    }

    private fun renderShopping(
        result: AlfredResult<List<ShoppingListSummaryDto>>?,
        phoneLists: List<ShoppingListSummaryDto>
    ) {
        val lists = result?.dataOrNull()
        // A phone list with items left shows even while the hub's lists can't be had.
        val active = HomeTiles.activeShoppingList(lists.orEmpty(), phoneLists)
        binding.tvShoppingName.text = when {
            active != null -> ShoppingList.shortTitle(active.title!!.trim())
            result == null -> getString(R.string.tiles_todo_loading)
            lists == null -> getString(R.string.tiles_shopping_unavailable)
            else -> getString(R.string.tiles_shopping_done)
        }
        binding.tvShoppingCount.isVisible = active != null
        binding.tvShoppingCount.text = active?.let { getString(R.string.tiles_shopping_left, it.unticked ?: 0) }
    }

    /** Today, schedule (plan or calendar, whichever is older), to-do, shopping — each its own endpoint's age. */
    private fun ages(state: TilesViewModel.TilesState): List<Freshness.Age?> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        // A calendar only matters when there's a plan for it to sit in.
        val calendar = Freshness.of(state.calendar, now, zone).takeIf { state.schedule?.dataOrNull() != null }
        // A copy saved on another day isn't shown (Today says the hub's out of reach),
        // so it has no age to show and nothing to retry for.
        val todayShown = HomeTiles.today(state.today, LocalDate.now(), zone) is HomeTiles.Today.Events
        return listOf(
            Freshness.of(state.today, now, zone).takeIf { todayShown },
            Freshness.worst(Freshness.of(state.schedule, now, zone), calendar),
            // The hub's lists only: a to-do kept on the phone is never out of date.
            Freshness.worst(
                Freshness.of(state.familyTodos, now, zone),
                Freshness.of(state.mineTodos, now, zone).takeIf { state.mineHome == ListHome.HUB }
            ),
            Freshness.of(state.shopping, now, zone)
        )
    }

    /** Ages on the tiles themselves — readable from Home without opening anything. */
    private fun renderFreshness(state: TilesViewModel.TilesState) {
        val reach = AlfredReachability.state.value
        val (today, schedule, todos, shopping) = ages(state)
        FreshnessView.render(binding.todayFreshness, today, reach)
        FreshnessView.render(binding.scheduleFreshness, schedule, reach)
        FreshnessView.render(binding.todoFreshness, todos, reach, compact = true, card = binding.cardTodo)
        FreshnessView.render(binding.shoppingFreshness, shopping, reach, compact = true, card = binding.cardShopping)
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

    private fun hairlineHeight(): Int = resources.getDimensionPixelSize(R.dimen.hairline)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
