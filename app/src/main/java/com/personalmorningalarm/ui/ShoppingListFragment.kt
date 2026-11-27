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
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.google.android.material.snackbar.Snackbar
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.ShoppingItem
import com.personalmorningalarm.data.model.ShoppingItemDto
import com.personalmorningalarm.data.model.ShoppingList
import com.personalmorningalarm.data.model.ShoppingSharing
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.profile.ProfileSettings
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.ShoppingCopy
import com.personalmorningalarm.data.remote.ShoppingShare
import com.personalmorningalarm.data.remote.ShoppingSync
import com.personalmorningalarm.databinding.FragmentShoppingListBinding
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * One shopping list, its name as the title with "n left" beside it: add (the bar
 * docked at the bottom), tap-to-tick, long-press-to-drop — behaving exactly like
 * the rolling to-do's [RollingTodoFragment] editing, just scoped to one list instead of
 * one chalkboard. Writes are accepted anywhere; offline they queue through the
 * shared [ShoppingSync] outbox and show as snapshot + pending edits.
 *
 * The settings card holds sharing, Keep ticked (bought items stay at the bottom,
 * under Bought, each one untickable, until Clear bought) and, on a list someone has
 * shared with you, Copy into….
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

    private val shareViewModel: ShoppingShareViewModel by viewModels {
        viewModelFactory { initializer { ShoppingShareViewModel(ShoppingShare.getInstance(requireContext())) } }
    }

    private val copyViewModel: ShoppingCopyViewModel by viewModels {
        viewModelFactory { initializer { ShoppingCopyViewModel(ShoppingCopy.getInstance(requireContext())) } }
    }

    private val me: Profile? by lazy { ProfileSettings(requireContext()).profile() }

    private val itemAdapter by lazy {
        ShoppingItemAdapter(
            onTick = { item -> item.line?.let(viewModel::tickItem) },
            onDrop = ::confirmDrop
        )
    }

    /** Keep ticked's Bought rows: a tap puts one back. */
    private val boughtAdapter by lazy {
        ShoppingItemAdapter(
            onTick = { item -> item.line?.let(viewModel::tickItem) },
            onDrop = ::confirmDrop,
            onUntick = { item -> item.line?.let(viewModel::untickItem) }
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

        binding.titleBar.tvScreenTitle.text = ShoppingList.shortTitle(listTitle)
        binding.titleBar.btnBack.setOnClickListener { findNavController().navigateUp() }

        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.rvShoppingItems.layoutManager = LinearLayoutManager(requireContext())
        binding.rvShoppingItems.adapter = itemAdapter
        binding.rvShoppingBought.layoutManager = LinearLayoutManager(requireContext())
        binding.rvShoppingBought.adapter = boughtAdapter
        listOf(binding.rvShoppingItems, binding.rvShoppingBought).forEach { list ->
            list.addItemDecoration(
                MaterialDividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL).apply {
                    dividerColor = MaterialColors.getColor(binding.root, R.attr.colorSurface2)
                    isLastItemDecorated = false
                }
            )
        }

        binding.btnShoppingConflictDismiss.setOnClickListener { viewModel.dismissFailed() }
        binding.switchShare.setOnClickListener { onShareToggled() }
        binding.switchKeepTicked.setOnClickListener {
            // Asks only: put back at once, and shown as the hub has it.
            val wanted = binding.switchKeepTicked.isChecked
            binding.switchKeepTicked.isChecked = !wanted
            viewModel.setKeepTicked(wanted)
        }
        binding.btnClearBought.setOnClickListener { confirmClearBought() }
        binding.btnCopyInto.setOnClickListener {
            viewModel.state.value.summary?.let { startCopyInto(it, me, copyViewModel) }
        }
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
                launch { shareViewModel.working.collect { renderShare(viewModel.state.value) } }
                launch { shareViewModel.results.collect(::onShareResult) }
                launch { copyViewModel.working.collect { renderShare(viewModel.state.value) } }
                launch { copyViewModel.results.collect { showCopyResult(binding.root, it, me) } }
            }
        }
        FreshnessWatch.start(
            viewLifecycleOwner,
            render = { renderFreshness(viewModel.state.value) },
            wantsRetry = { Freshness.wantsRetry(age(viewModel.state.value)) },
            retry = { viewModel.refresh(quiet = true) }
        )
    }

    private fun age(state: ShoppingListViewModel.ShoppingListState): Freshness.Age? =
        Freshness.of(state.result, System.currentTimeMillis(), ZoneId.systemDefault())

    private fun renderFreshness(state: ShoppingListViewModel.ShoppingListState) {
        FreshnessView.render(binding.freshness, age(state), AlfredReachability.state.value)
    }

    /**
     * The switch only asks: it's put back at once, and shows the new state when the
     * hub has it (render reads it from the list's summary).
     */
    private fun onShareToggled() {
        val wanted = binding.switchShare.isChecked
        binding.switchShare.isChecked = !wanted
        val list = viewModel.state.value.summary ?: return
        val profile = me ?: return
        when {
            wanted -> shareViewModel.setShared(list, true)
            else -> confirmUnshare(list, profile, onConfirm = { shareViewModel.setShared(list, false) })
        }
    }

    private fun onShareResult(result: ShoppingShareViewModel.Result) {
        showShareResult(binding.root, result)
        when (result.outcome) {
            is ShoppingShare.Outcome.MovedToPhone -> openMovedList(result.outcome, R.id.shoppingListFragment)
            else -> viewModel.refresh(quiet = true)
        }
    }

    /**
     * The settings card. Sharing: for the owner, a toggle; for the other person's list,
     * its name, a toggle that can't be changed, and Copy into…. Keep ticked for anyone.
     * The sharing rows wait for the list's summary; the card waits for the list.
     */
    private fun renderShare(state: ShoppingListViewModel.ShoppingListState) {
        val list = state.summary
        val profile = me
        val working = shareViewModel.working.value
        binding.rowShare.isVisible = state.result != null && state.result !is AlfredResult.Unavailable
        binding.switchKeepTicked.isChecked = state.keepTicked
        binding.switchKeepTicked.isEnabled = !state.keepWorking && !working
        binding.tvKeepTickedNote.setText(
            if (state.keepTicked) R.string.shopping_keep_ticked_note_on else R.string.shopping_keep_ticked_note_off
        )
        val copying = copyViewModel.working.value
        binding.switchShare.isVisible = list != null && profile != null
        binding.tvShareNote.isVisible = list != null && profile != null
        binding.rowCopyInto.isVisible = list != null && profile != null && !ShoppingSharing.isMine(list, profile)
        binding.btnCopyInto.isEnabled = !copying
        binding.btnCopyInto.setText(if (copying) R.string.shopping_copy_working else R.string.shopping_copy_into)
        if (list == null || profile == null) return
        val shared = ShoppingSharing.isShared(list)
        val mine = ShoppingSharing.isMine(list, profile)
        binding.switchShare.isChecked = shared
        binding.switchShare.isEnabled = mine && !working
        binding.switchShare.text =
            if (mine) getString(R.string.shopping_share_with, otherName(profile)) else getString(R.string.shopping_share_by, ownerName(list, profile))
        binding.tvShareNote.text = when {
            working -> getString(R.string.shopping_share_working)
            !mine -> getString(R.string.shopping_share_note_not_owner, ownerName(list, profile))
            !shared -> getString(R.string.shopping_share_note_hub_private)
            ShoppingSharing.toggle(profile, list, shared = false) == ShoppingSharing.Toggle.PULL_BACK ->
                getString(R.string.shopping_share_note_pull)
            else -> getString(R.string.shopping_share_note_hub_shared, otherName(profile))
        }
        // Mid-move the list takes no edits here either: they'd be stranded on the copy that goes.
        binding.etShoppingAdd.isEnabled = !working
        binding.btnShoppingAdd.isEnabled = !working
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

    /** Clear bought, asked first: it takes them off for good (logged as bought on the hub). */
    private fun confirmClearBought() {
        val bought = boughtAdapter.itemCount
        if (bought == 0) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.shopping_clear_bought_title)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.shopping_clear_bought_message, bought, bought, ShoppingList.shortTitle(listTitle)
                )
            )
            .setPositiveButton(R.string.shopping_clear_bought) { _, _ -> viewModel.clearBought() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEvent(event: ShoppingListViewModel.ShoppingEvent) {
        val message = when (event) {
            ShoppingListViewModel.ShoppingEvent.LIST_REFRESHED -> R.string.chalkboard_list_refreshed
            ShoppingListViewModel.ShoppingEvent.SAVED_OFFLINE -> R.string.chalkboard_saved_offline
            ShoppingListViewModel.ShoppingEvent.KEEP_NEEDS_HUB -> R.string.shopping_keep_ticked_needs_hub
            ShoppingListViewModel.ShoppingEvent.CLEAR_NEEDS_HUB -> R.string.shopping_clear_bought_needs_hub
            ShoppingListViewModel.ShoppingEvent.LIST_GONE -> R.string.shopping_copy_gone
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun render(state: ShoppingListViewModel.ShoppingListState) {
        binding.swipeRefresh.isRefreshing = state.loading
        renderShare(state)

        val queued = state.pending.filterNot { it.failed }
        val failed = state.pending.filter { it.failed }

        renderFreshness(state)

        // The sync line: edits not in the vault yet. The list's age is FreshnessView's.
        val sync = listOfNotNull(
            resources.getQuantityString(R.plurals.chalkboard_pending_count, queued.size, queued.size)
                .takeIf { queued.isNotEmpty() }
        )
        binding.syncLine.root.isVisible = sync.isNotEmpty()
        binding.syncLine.tvSyncText.text = sync.joinToString(" · ")

        binding.rowShoppingConflict.isVisible = failed.isNotEmpty()
        if (failed.isNotEmpty()) {
            binding.tvShoppingConflict.text = resources.getQuantityString(
                R.plurals.chalkboard_conflict_notice, failed.size, failed.size
            ) + failed.joinToString(separator = "") { "\n• ${it.text}" }
        }

        binding.etShoppingAdd.hint = getString(
            if (state.result is AlfredResult.Stale || state.result is AlfredResult.Unavailable) {
                R.string.shopping_offline_hint
            } else {
                R.string.shopping_add_hint
            }
        )

        val base: List<ShoppingItemDto>? = when (val result = state.result) {
            null -> null
            is AlfredResult.Fresh -> result.data
            is AlfredResult.Stale -> result.data
            AlfredResult.Unavailable -> null
        }

        // No snapshot AND nothing queued: only then is there truly nothing to list.
        if (base == null && queued.isEmpty()) {
            binding.tvShoppingBody.isVisible = true
            binding.rvShoppingItems.isVisible = false
            binding.tvShoppingBody.text =
                getString(if (state.result == null) R.string.shopping_menu_loading else R.string.shopping_list_unavailable)
            binding.titleBar.tvScreenMeta.isVisible = false
            itemAdapter.submitList(emptyList())
            boughtAdapter.submitList(emptyList())
            binding.rowBought.isVisible = false
            binding.rvShoppingBought.isVisible = false
            return
        }

        val items = ShoppingList.merged(base.orEmpty(), state.pending)
        binding.titleBar.tvScreenMeta.isVisible = true
        binding.titleBar.tvScreenMeta.text = getString(R.string.shopping_left, items.count { !it.done })
        showItems(binding, itemAdapter, boughtAdapter, items, state.keepTicked)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvShoppingItems.adapter = null
        binding.rvShoppingBought.adapter = null
        _binding = null
    }

    companion object {
        const val ARG_LIST_ID = "listId"
        const val ARG_LIST_TITLE = "listTitle"
    }
}

/**
 * Both list screens' rows. With [keepTicked]: what's still to buy, then Bought, the
 * ticked items struck through at the bottom, with Clear bought. Without it, every row
 * in the order it was added, as before.
 */
internal fun showItems(
    binding: FragmentShoppingListBinding,
    toBuy: ShoppingItemAdapter,
    boughtList: ShoppingItemAdapter,
    items: List<ShoppingItem>,
    keepTicked: Boolean
) {
    val bought = if (keepTicked) items.filter { it.done } else emptyList()
    val rest = if (keepTicked) items.filterNot { it.done } else items
    binding.rvShoppingItems.isVisible = rest.isNotEmpty()
    binding.rowBought.isVisible = bought.isNotEmpty()
    binding.rvShoppingBought.isVisible = bought.isNotEmpty()
    binding.tvShoppingBody.isVisible = items.isEmpty()
    if (items.isEmpty()) binding.tvShoppingBody.setText(R.string.shopping_list_empty)
    toBuy.submitList(rest)
    boughtList.submitList(bought)
}
