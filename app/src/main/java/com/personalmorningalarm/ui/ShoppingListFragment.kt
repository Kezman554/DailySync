package com.personalmorningalarm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.ShoppingItem
import com.personalmorningalarm.data.model.ShoppingItemDto
import com.personalmorningalarm.data.model.ShoppingList
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.ShoppingSync
import com.personalmorningalarm.databinding.FragmentShoppingListBinding
import kotlinx.coroutines.launch

/**
 * One shopping list: add, tap-to-tick, long-press-to-drop — behaving exactly like
 * the rolling to-do's [RollingTodoFragment] editing, just scoped to one list instead of
 * one chalkboard. Writes are accepted anywhere; offline they queue through the
 * shared [ShoppingSync] outbox and show as snapshot + pending edits.
 */
class ShoppingListFragment : Fragment() {

    private var _binding: FragmentShoppingListBinding? = null
    private val binding get() = _binding!!

    private val listId: String by lazy { requireArguments().getString(ARG_LIST_ID).orEmpty() }
    private val listTitle: String by lazy { requireArguments().getString(ARG_LIST_TITLE).orEmpty() }

    private val viewModel: ShoppingListViewModel by viewModels {
        ShoppingListViewModelFactory(
            AlfredRepository(requireContext()),
            ShoppingSync.getInstance(requireContext()),
            listId
        )
    }

    private val itemAdapter by lazy {
        ShoppingItemAdapter(
            onTick = { item -> item.line?.let(viewModel::tickItem) },
            onDrop = ::confirmDrop
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentShoppingListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().title = listTitle

        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.rvShoppingItems.layoutManager = LinearLayoutManager(requireContext())
        binding.rvShoppingItems.adapter = itemAdapter

        binding.btnShoppingConflictDismiss.setOnClickListener { viewModel.dismissFailed() }
        binding.btnShoppingAdd.setOnClickListener { submitAdd() }
        binding.etShoppingAdd.setOnEditorActionListener { _, actionId, _ ->
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

    private fun submitAdd() {
        val text = binding.etShoppingAdd.text?.toString().orEmpty()
        if (text.isBlank()) return
        viewModel.addItem(text)
        binding.etShoppingAdd.setText("")
    }

    private fun confirmDrop(item: ShoppingItem) {
        val line = item.line ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.shopping_drop_title)
            .setMessage(getString(R.string.shopping_drop_message, item.task))
            .setPositiveButton(R.string.chalkboard_drop_confirm) { _, _ -> viewModel.dropItem(line) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEvent(event: ShoppingListViewModel.ShoppingEvent) {
        val message = when (event) {
            ShoppingListViewModel.ShoppingEvent.LIST_REFRESHED -> R.string.chalkboard_list_refreshed
            ShoppingListViewModel.ShoppingEvent.SAVED_OFFLINE -> R.string.chalkboard_saved_offline
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun render(state: ShoppingListViewModel.ShoppingListState) {
        binding.swipeRefresh.isRefreshing = state.loading

        val queued = state.pending.filterNot { it.failed }
        val failed = state.pending.filter { it.failed }

        binding.tvShoppingPendingCount.isVisible = queued.isNotEmpty()
        if (queued.isNotEmpty()) {
            binding.tvShoppingPendingCount.text = resources.getQuantityString(
                R.plurals.chalkboard_pending_count, queued.size, queued.size
            )
        }
        binding.rowShoppingConflict.isVisible = failed.isNotEmpty()
        if (failed.isNotEmpty()) {
            binding.tvShoppingConflict.text = resources.getQuantityString(
                R.plurals.chalkboard_conflict_notice, failed.size, failed.size
            ) + failed.joinToString(separator = "") { "\n• ${it.text}" }
        }

        binding.tilShoppingAdd.hint = getString(
            if (state.result is AlfredResult.Stale || state.result is AlfredResult.Unavailable) {
                R.string.shopping_offline_hint
            } else {
                R.string.shopping_add_hint
            }
        )

        binding.tvShoppingNote.isVisible = false
        val base: List<ShoppingItemDto>? = when (val result = state.result) {
            null -> null
            is AlfredResult.Fresh -> result.data
            is AlfredResult.Stale -> {
                binding.tvShoppingNote.isVisible = true
                binding.tvShoppingNote.text = getString(R.string.schedule_stale)
                result.data
            }
            AlfredResult.Unavailable -> null
        }

        // No snapshot AND nothing queued: only then is there truly nothing to list.
        if (base == null && queued.isEmpty()) {
            binding.tvShoppingBody.isVisible = true
            binding.rvShoppingItems.isVisible = false
            binding.tvShoppingBody.text =
                getString(if (state.result == null) R.string.shopping_menu_loading else R.string.shopping_list_unavailable)
            itemAdapter.submitList(emptyList())
            return
        }

        val items = ShoppingList.merged(base.orEmpty(), state.pending)
        binding.rvShoppingItems.isVisible = items.isNotEmpty()
        binding.tvShoppingBody.isVisible = items.isEmpty()
        if (items.isEmpty()) {
            binding.tvShoppingBody.text = getString(R.string.shopping_list_empty)
        }
        itemAdapter.submitList(items)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvShoppingItems.adapter = null
        _binding = null
    }

    companion object {
        const val ARG_LIST_ID = "listId"
        const val ARG_LIST_TITLE = "listTitle"
    }
}
