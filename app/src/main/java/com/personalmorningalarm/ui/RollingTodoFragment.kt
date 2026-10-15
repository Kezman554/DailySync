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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.google.android.material.snackbar.Snackbar
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.RollingTodo
import com.personalmorningalarm.data.model.RollingTodoItem
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.ChalkboardSync
import com.personalmorningalarm.databinding.FragmentRollingTodoBinding
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The To-Do tile: the rolling to-do, read-write. Tap ticks, long-press (with a
 * confirm) drops, and the round + opens the field that adds. Writes are accepted
 * anywhere: live they go straight to Alfred, and away from home they queue
 * on-device ([ChalkboardSync]) and show as snapshot + pending edits until a flush
 * lands them. Today's schedule isn't here — the Week screen covers it.
 *
 * [TodayViewModel] still fetches the schedule alongside: one extra request that
 * keeps the cache warm for the alarm's own schedule screen.
 */
class RollingTodoFragment : Fragment() {

    private var _binding: FragmentRollingTodoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TodayViewModel by viewModels {
        AlfredViewModelFactory(
            AlfredRepository(requireContext()),
            ChalkboardSync.getInstance(requireContext())
        )
    }

    private val todoAdapter by lazy {
        RollingTodoAdapter(
            onTick = { item -> item.line?.let(viewModel::tickItem) },
            onDrop = ::confirmDrop
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRollingTodoBinding.inflate(inflater, container, false)
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
        // Writes are offered everywhere — offline they queue rather than send.
        todoAdapter.writable = true
        binding.btnChalkboardConflictDismiss.setOnClickListener { viewModel.dismissFailed() }

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
     * Adds what's in the field and keeps it open for the next one. Empty or
     * whitespace-only input is never sent — Add on an empty field closes it.
     */
    private fun submitAdd() {
        val text = binding.etChalkboardAdd.text?.toString().orEmpty()
        if (text.isBlank()) {
            closeAdd()
            return
        }
        viewModel.addItem(text)
        binding.etChalkboardAdd.setText("")
    }

    /**
     * Drop is the heavier verb — long-press got the user here, and this confirm
     * makes "no longer relevant" impossible to fat-finger.
     */
    private fun confirmDrop(item: RollingTodoItem) {
        val line = item.line ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.chalkboard_drop_title)
            .setMessage(getString(R.string.chalkboard_drop_message, item.task))
            .setPositiveButton(R.string.chalkboard_drop_confirm) { _, _ -> viewModel.dropItem(line) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEvent(event: TodayViewModel.TodayEvent) {
        val message = when (event) {
            TodayViewModel.TodayEvent.LIST_REFRESHED -> R.string.chalkboard_list_refreshed
            TodayViewModel.TodayEvent.SAVED_OFFLINE -> R.string.chalkboard_saved_offline
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun render(state: TodayViewModel.TodayState) {
        binding.swipeRefresh.isRefreshing = state.loading
        val result = state.chalkboard
        val queued = state.pending.filterNot { it.failed }
        val failed = state.pending.filter { it.failed }

        // The sync line: a saved copy, and/or edits not in the vault yet.
        val sync = listOfNotNull(
            getString(R.string.chalkboard_stale).takeIf { result is AlfredResult.Stale },
            resources.getQuantityString(R.plurals.chalkboard_pending_count, queued.size, queued.size)
                .takeIf { queued.isNotEmpty() }
        )
        binding.syncLine.root.isVisible = sync.isNotEmpty()
        binding.syncLine.tvSyncText.text = sync.joinToString(" · ")

        // The conflict notice: queued changes that didn't make it.
        binding.rowChalkboardConflict.isVisible = failed.isNotEmpty()
        if (failed.isNotEmpty()) {
            binding.tvChalkboardConflict.text = resources.getQuantityString(
                R.plurals.chalkboard_conflict_notice, failed.size, failed.size
            ) + failed.joinToString(separator = "") { "\n• ${it.text}" }
        }

        // Writes are accepted everywhere; the hint says where they're going.
        binding.etChalkboardAdd.hint = getString(
            if (result is AlfredResult.Stale || result is AlfredResult.Unavailable) {
                R.string.chalkboard_offline_hint
            } else {
                R.string.chalkboard_add_hint
            }
        )

        val base: List<ChalkboardTaskDto>? = when (result) {
            null -> null
            is AlfredResult.Fresh -> result.data
            is AlfredResult.Stale -> result.data
            AlfredResult.Unavailable -> null
        }

        // No snapshot AND nothing queued: only then is there truly nothing to list.
        if (base == null && queued.isEmpty()) {
            binding.tvChalkboardBody.isVisible = true
            binding.rvChalkboard.isVisible = false
            binding.tvChalkboardBody.text =
                getString(if (result == null) R.string.chalkboard_loading else R.string.chalkboard_unavailable)
            renderStats(emptyList(), known = false)
            todoAdapter.submitList(emptyList())
            return
        }

        val items = RollingTodo.merged(base.orEmpty(), state.pending)
        binding.rvChalkboard.isVisible = items.isNotEmpty()
        binding.tvChalkboardBody.isVisible = items.isEmpty()
        if (items.isEmpty()) {
            binding.tvChalkboardBody.text = getString(R.string.chalkboard_empty)
        }
        renderStats(items, known = true)
        todoAdapter.submitList(items)
    }

    /** OPEN n and OLDEST n days; dashes until there's a list to count. */
    private fun renderStats(items: List<RollingTodoItem>, known: Boolean) {
        if (!known) {
            binding.tvOpenCount.setText(R.string.todo_stat_unknown)
            binding.tvOldest.setText(R.string.todo_stat_unknown)
            return
        }
        binding.tvOpenCount.text = items.count { !it.done }.toString()
        val oldest = RollingTodo.oldestOpenAgeDays(items, LocalDate.now())
        binding.tvOldest.text = oldest?.let {
            resources.getQuantityString(R.plurals.todo_oldest_days, it.toInt(), it.toInt())
        } ?: getString(R.string.todo_stat_unknown)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
