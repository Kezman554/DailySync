package com.personalmorningalarm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.google.android.material.snackbar.Snackbar
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.AddHint
import com.personalmorningalarm.data.model.RollingTodo
import com.personalmorningalarm.data.model.TodoBoard
import com.personalmorningalarm.data.model.TodoFilter
import com.personalmorningalarm.data.model.TodoRow
import com.personalmorningalarm.data.model.TodoSource
import com.personalmorningalarm.data.phone.ListSettings
import com.personalmorningalarm.data.phone.PhoneListStore
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.profile.ProfileSettings
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.ChalkboardSync
import com.personalmorningalarm.databinding.FragmentTodoBinding
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * The To-Do tile: the family chalkboard and the person's own list in one screen,
 * under an All | Family | Mine filter that opens on All. Tap ticks, long-press
 * (with a confirm) drops, and the round + opens the field that adds, with a
 * Mine / Family picker and a line saying where the item will go.
 *
 * Hub writes are accepted anywhere: live they go straight to Alfred, and away from
 * home they queue on-device ([ChalkboardSync]). Phone writes never touch the queue.
 */
class TodoFragment : Fragment() {

    private var _binding: FragmentTodoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TodoViewModel by viewModels {
        viewModelFactory {
            initializer {
                val context = requireContext().applicationContext
                val profile = ProfileSettings(context).profile() ?: Profile.NICK
                TodoViewModel(
                    AlfredRepository(context),
                    ChalkboardSync.getInstance(context),
                    PhoneListStore.getInstance(context),
                    mineHome = profile.mineTodoHome(ListSettings(context).todoHome())
                )
            }
        }
    }

    private val todoAdapter by lazy {
        RollingTodoAdapter(onTick = viewModel::tick, onDrop = ::confirmDrop).also { it.writable = true }
    }

    // Functions, not lazies: the view, and so these, is new each time the screen is shown.
    private fun filterSegments() = mapOf(
        TodoFilter.ALL to binding.segFilterAll,
        TodoFilter.FAMILY to binding.segFilterFamily,
        TodoFilter.MINE to binding.segFilterMine
    )

    private fun targetSegments() = mapOf(
        TodoSource.MINE to binding.segTargetMine,
        TodoSource.FAMILY to binding.segTargetFamily
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTodoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindScreenTitle(binding.titleBar, R.string.todo_title)

        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }

        binding.rvChalkboard.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChalkboard.adapter = todoAdapter
        binding.rvChalkboard.addItemDecoration(
            MaterialDividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL).apply {
                dividerColor = MaterialColors.getColor(binding.root, R.attr.colorSurface2)
                isLastItemDecorated = false
            }
        )
        binding.btnChalkboardConflictDismiss.setOnClickListener { viewModel.dismissFailed() }

        filterSegments().forEach { (filter, segment) -> segment.setOnClickListener { viewModel.setFilter(filter) } }
        targetSegments().forEach { (target, segment) -> segment.setOnClickListener { viewModel.setAddTarget(target) } }

        binding.fabAddTodo.setOnClickListener { openAdd() }
        binding.btnChalkboardAdd.setOnClickListener { submitAdd() }
        binding.etChalkboardAdd.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitAdd()
                true
            } else {
                false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.events.collect(::showEvent) }
            }
        }
        FreshnessWatch.start(
            viewLifecycleOwner,
            render = { renderFreshness(viewModel.state.value) },
            wantsRetry = { Freshness.wantsRetry(age(viewModel.state.value)) },
            retry = { viewModel.refresh(quiet = true) }
        )
    }

    /** The oldest hub copy the filter shows; a phone list is never out of date. */
    private fun age(state: TodoViewModel.TodoState): Freshness.Age? {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        return Freshness.worst(
            *state.shownSources.filter { it.onHub }.map { Freshness.of(it.chalkboard, now, zone) }.toTypedArray()
        )
    }

    private fun renderFreshness(state: TodoViewModel.TodoState) {
        FreshnessView.render(binding.freshness, age(state), AlfredReachability.state.value)
    }

    /** The + opens the add field, focused with the keyboard up. */
    private fun openAdd() {
        binding.rowChalkboardAdd.isVisible = true
        binding.fabAddTodo.hide()
        binding.etChalkboardAdd.requestFocus()
        requireContext().getSystemService<InputMethodManager>()
            ?.showSoftInput(binding.etChalkboardAdd, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeAdd() {
        requireContext().getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(binding.etChalkboardAdd.windowToken, 0)
        binding.etChalkboardAdd.clearFocus()
        binding.rowChalkboardAdd.isVisible = false
        binding.fabAddTodo.show()
    }

    /**
     * Adds what's in the field to the list the picker is on, and keeps the field
     * open for the next one. Blank input is never sent — Add on an empty field closes it.
     */
    private fun submitAdd() {
        val text = binding.etChalkboardAdd.text?.toString().orEmpty()
        if (text.isBlank()) {
            closeAdd()
            return
        }
        viewModel.add(text)
        binding.etChalkboardAdd.setText("")
    }

    /**
     * Drop is the heavier verb — long-press got the user here, and this confirm
     * makes "no longer relevant" impossible to fat-finger.
     */
    private fun confirmDrop(row: TodoRow) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.chalkboard_drop_title)
            .setMessage(getString(R.string.chalkboard_drop_message, row.item.task))
            .setPositiveButton(R.string.chalkboard_drop_confirm) { _, _ -> viewModel.drop(row) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEvent(event: TodoEvent) {
        val message = when (event) {
            TodoEvent.LIST_REFRESHED -> R.string.chalkboard_list_refreshed
            TodoEvent.SAVED_OFFLINE -> R.string.chalkboard_saved_offline
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun render(state: TodoViewModel.TodoState) {
        filterSegments().forEach { (filter, segment) -> segment.isSelected = filter == state.filter }
        val shownSources = state.shownSources
        val hubShown = shownSources.filter { it.onHub }

        // Pull to refresh only means something for a hub list.
        binding.swipeRefresh.isEnabled = hubShown.isNotEmpty()
        binding.swipeRefresh.isRefreshing = hubShown.any { it.loading }
        renderFreshness(state)

        // The sync line: hub edits not in the vault yet, either list.
        val queued = state.family.queued.size + state.mine.queued.size
        binding.syncLine.root.isVisible = queued > 0
        binding.syncLine.tvSyncText.text =
            resources.getQuantityString(R.plurals.chalkboard_pending_count, queued, queued)

        // The conflict notice: queued changes that didn't make it.
        val failed = state.family.failed + state.mine.failed
        binding.rowChalkboardConflict.isVisible = failed.isNotEmpty()
        if (failed.isNotEmpty()) {
            binding.tvChalkboardConflict.text = resources.getQuantityString(
                R.plurals.chalkboard_conflict_notice, failed.size, failed.size
            ) + failed.joinToString(separator = "") { "\n• ${it.text}" }
        }

        renderAdd(state)

        val rows = state.shown
        todoAdapter.tagFamily = state.filter == TodoFilter.ALL
        todoAdapter.submitList(rows)
        binding.rvChalkboard.isVisible = rows.isNotEmpty()
        binding.tvFamilyFootnote.isVisible = rows.any { it.source == TodoSource.FAMILY }

        val status = statusLine(state, rows.isEmpty())
        binding.tvChalkboardBody.isVisible = status != null
        binding.tvChalkboardBody.text = status
        renderStats(rows, known = shownSources.any { it.items != null })
    }

    /** The picker, the hint under it and the field's own hint. */
    private fun renderAdd(state: TodoViewModel.TodoState) {
        targetSegments().forEach { (target, segment) -> segment.isSelected = target == state.addTarget }
        binding.tvAddHint.setText(
            when (TodoBoard.addHint(state.addTarget, viewModel.mineHome)) {
                AddHint.WALL -> R.string.todo_hint_wall
                AddHint.PHONE -> R.string.todo_hint_phone
                AddHint.YOUR_LIST -> R.string.todo_hint_your_list
            }
        )
        val target = state.source(state.addTarget)
        binding.etChalkboardAdd.hint = getString(
            if (target.onHub && target.chalkboard !is AlfredResult.Fresh && !target.loading) {
                R.string.chalkboard_offline_hint
            } else {
                R.string.chalkboard_add_hint
            }
        )
    }

    /**
     * Above the list: loading, never-loaded or all-clear when there's nothing to
     * list, and the family list's absence even when Mine has rows.
     */
    private fun statusLine(state: TodoViewModel.TodoState, empty: Boolean): String? {
        val unknown = state.shownSources.filter { it.items == null }
        val stillLoading = unknown.any { it.loading || (it.onHub && it.chalkboard == null) }
        val familyMissing = state.filter.shows(TodoSource.FAMILY) && state.family.items == null
        return when {
            stillLoading && empty -> getString(R.string.chalkboard_loading)
            stillLoading -> null
            familyMissing -> getString(R.string.todo_family_unavailable)
            unknown.isNotEmpty() && empty -> getString(R.string.chalkboard_unavailable)
            empty -> getString(R.string.chalkboard_empty)
            else -> null
        }
    }

    /** OPEN n and OLDEST n days for what the filter shows; dashes until there's a list to count. */
    private fun renderStats(rows: List<TodoRow>, known: Boolean) {
        if (!known) {
            binding.tvOpenCount.setText(R.string.todo_stat_unknown)
            binding.tvOldest.setText(R.string.todo_stat_unknown)
            return
        }
        val items = rows.map { it.item }
        binding.tvOpenCount.text = items.count { !it.done }.toString()
        val oldest = RollingTodo.oldestOpenAgeDays(items, LocalDate.now())
        binding.tvOldest.text = oldest?.let {
            resources.getQuantityString(R.plurals.todo_oldest_days, it.toInt(), it.toInt())
        } ?: getString(R.string.todo_stat_unknown)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvChalkboard.adapter = null
        _binding = null
    }
}
