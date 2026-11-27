package com.personalmorningalarm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.PhoneLists
import com.personalmorningalarm.data.model.ShoppingList
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.model.ShoppingSharing
import com.personalmorningalarm.data.phone.ListHome
import com.personalmorningalarm.data.phone.PhoneListStore
import com.personalmorningalarm.data.profile.NewListHome
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.profile.ProfileSettings
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.ShoppingCopy
import com.personalmorningalarm.data.remote.ShoppingShare
import com.personalmorningalarm.data.remote.ShoppingSync
import com.personalmorningalarm.databinding.FragmentShoppingMenuBinding
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The shopping-list tile's landing screen: every active list from Alfred's discovery
 * endpoint as a grid of tiles, then the lists kept on this phone, plus a New list
 * tile. The hub's lists behave like the other Alfred menus — cache-backed, rendered
 * from the saved copy when Alfred is unreachable — except create-list, which is
 * deliberately online-only (it needs the API's 409 answer, so there's nothing a
 * queue could usefully do with it offline). Delete-list, a long press on a tile, is
 * online-only as well. A phone list is made and deleted anywhere.
 *
 * New list first asks where the list lives, starting on the hub when an address is
 * set in Settings and on this phone when none is.
 */
class ShoppingMenuFragment : Fragment() {

    private var _binding: FragmentShoppingMenuBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ShoppingMenuViewModel by viewModels {
        ShoppingMenuViewModelFactory(
            AlfredRepository(requireContext()),
            ShoppingSync.getInstance(requireContext()),
            PhoneListStore.getInstance(requireContext())
        )
    }

    private val shareViewModel: ShoppingShareViewModel by viewModels {
        viewModelFactory { initializer { ShoppingShareViewModel(ShoppingShare.getInstance(requireContext())) } }
    }

    private val copyViewModel: ShoppingCopyViewModel by viewModels {
        viewModelFactory { initializer { ShoppingCopyViewModel(ShoppingCopy.getInstance(requireContext())) } }
    }

    private val me: Profile? by lazy { ProfileSettings(requireContext()).profile() }

    private val listAdapter by lazy {
        ShoppingListSummaryAdapter(me, onClick = ::openList, onLongPress = ::showListActions, onNewList = ::openCreate)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentShoppingMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindScreenTitle(binding.titleBar, R.string.shopping_menu_title)

        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.rvShoppingLists.layoutManager = GridLayoutManager(requireContext(), GRID_COLUMNS)
        binding.rvShoppingLists.adapter = listAdapter

        binding.btnShoppingNewList.setOnClickListener { submitCreate() }
        binding.etShoppingNewList.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitCreate()
                true
            } else {
                false
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.events.collect(::showEvent) }
                launch {
                    shareViewModel.results.collect {
                        showShareResult(binding.root, it)
                        viewModel.refresh(quiet = true)
                    }
                }
                launch {
                    copyViewModel.results.collect {
                        showCopyResult(binding.root, it, me)
                        viewModel.refresh(quiet = true)
                    }
                }
            }
        }
        // A move left part-way (no network, the app closed) is settled here, quietly.
        if (savedInstanceState == null) shareViewModel.resume()
        FreshnessWatch.start(
            viewLifecycleOwner,
            render = { renderFreshness(viewModel.state.value) },
            wantsRetry = { Freshness.wantsRetry(age(viewModel.state.value)) },
            retry = { viewModel.refresh(quiet = true) }
        )
    }

    private fun age(state: ShoppingMenuViewModel.MenuState): Freshness.Age? =
        Freshness.of(state.lists, System.currentTimeMillis(), ZoneId.systemDefault())

    private fun renderFreshness(state: ShoppingMenuViewModel.MenuState) {
        FreshnessView.render(binding.freshness, age(state), AlfredReachability.state.value)
    }

    /** The New list tile settles where the list lives ([chooseNewListHome]), then opens the create field. */
    private fun openCreate() {
        chooseNewListHome(requireContext()) { home ->
            viewModel.createHome = home
            if (_binding != null) showCreateField(home)
        }
    }

    /** The create field, focused with the keyboard up, its hint saying where the list goes. */
    private fun showCreateField(home: ListHome) {
        setCreateHint(home)
        binding.rowShoppingNewList.isVisible = true
        binding.etShoppingNewList.requestFocus()
        requireContext().getSystemService<InputMethodManager>()
            ?.showSoftInput(binding.etShoppingNewList, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun setCreateHint(home: ListHome) {
        binding.etShoppingNewList.hint = getString(
            when {
                // Only ever the hub: nothing to say about where, as before phone lists.
                newListRule() is NewListHome.Always -> R.string.shopping_new_list_hint
                else -> when (home) {
                    ListHome.HUB -> R.string.shopping_new_list_hint_hub
                    ListHome.PHONE -> R.string.shopping_new_list_hint_phone
                }
            }
        )
    }

    private fun closeCreate() {
        requireContext().getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(binding.etShoppingNewList.windowToken, 0)
        binding.etShoppingNewList.clearFocus()
        binding.rowShoppingNewList.isVisible = false
    }

    /** Create on an empty field just closes it — a blank name is never sent. */
    private fun submitCreate() {
        val name = binding.etShoppingNewList.text?.toString().orEmpty()
        if (name.isBlank()) {
            closeCreate()
            return
        }
        viewModel.createList(name)
    }

    private fun openList(tile: ShoppingListSummaryAdapter.Tile.ListTile) {
        val summary = tile.summary
        val id = summary.id ?: return
        findNavController().navigate(
            when (tile.home) {
                ListHome.HUB -> R.id.action_shoppingMenu_to_shoppingList
                ListHome.PHONE -> R.id.action_shoppingMenu_to_phoneShoppingList
            },
            bundleOf(
                ShoppingListFragment.ARG_LIST_ID to id,
                ShoppingListFragment.ARG_LIST_TITLE to (summary.title ?: id)
            )
        )
    }

    /**
     * The long press: Share (or Stop sharing) and Delete. The other person's list
     * offers neither, only who it belongs to (only its owner changes its sharing or
     * deletes it) and Copy into…, for a copy of your own.
     */
    private fun showListActions(tile: ShoppingListSummaryAdapter.Tile.ListTile) {
        val summary = tile.summary
        val profile = me
        val title = summary.title ?: summary.id.orEmpty()
        if (tile.home == ListHome.HUB && profile != null && !ShoppingSharing.isMine(summary, profile)) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(ShoppingList.shortTitle(title))
                .setMessage(getString(R.string.shopping_owner_only, ownerName(summary, profile)))
                .setPositiveButton(R.string.shopping_copy_into) { _, _ -> startCopyInto(summary, profile, copyViewModel) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val shared = tile.home == ListHome.HUB && ShoppingSharing.isShared(summary)
        val actions = listOfNotNull(
            (if (shared) R.string.shopping_menu_unshare else R.string.shopping_menu_share).takeIf { profile != null },
            R.string.shopping_delete_confirm
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(ShoppingList.shortTitle(title))
            .setItems(actions.map(::getString).toTypedArray()) { _, which ->
                when (actions[which]) {
                    R.string.shopping_menu_share -> when (tile.home) {
                        ListHome.PHONE -> confirmShare(title, profile, onConfirm = {
                            shareViewModel.sharePhoneList(summary.id ?: return@confirmShare, title)
                        })
                        ListHome.HUB -> shareViewModel.setShared(summary, true)
                    }
                    R.string.shopping_menu_unshare -> profile?.let {
                        confirmUnshare(summary, it, onConfirm = { shareViewModel.setShared(summary, false) })
                    }
                    else -> confirmDelete(tile)
                }
            }
            .show()
    }

    /**
     * Like create, a hub delete needs a live Alfred, so a saved copy gets the reason
     * and no dialog. A phone list is always there to delete.
     */
    private fun confirmDelete(tile: ShoppingListSummaryAdapter.Tile.ListTile) {
        val summary = tile.summary
        val onPhone = tile.home == ListHome.PHONE
        if (!onPhone && viewModel.state.value.lists !is AlfredResult.Fresh) {
            Snackbar.make(binding.root, R.string.shopping_delete_needs_alfred, Snackbar.LENGTH_LONG).show()
            return
        }
        val name = ShoppingList.shortTitle(summary.title ?: summary.id.orEmpty())
        val left = ShoppingList.itemsLeft(summary)
        val message = when {
            left > 0 -> resources.getQuantityString(
                if (onPhone) R.plurals.shopping_phone_delete_message_open else R.plurals.shopping_delete_message_open,
                left,
                left,
                name
            )
            onPhone -> getString(R.string.shopping_phone_delete_message, name)
            else -> getString(R.string.shopping_delete_message, name)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.shopping_delete_title)
            .setMessage(message)
            .setPositiveButton(R.string.shopping_delete_confirm) { _, _ ->
                if (onPhone) viewModel.deletePhoneList(summary) else viewModel.deleteList(summary)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEvent(event: ShoppingMenuViewModel.MenuEvent) {
        when (event) {
            is ShoppingMenuViewModel.MenuEvent.Created -> {
                binding.etShoppingNewList.setText("")
                closeCreate()
                Snackbar.make(
                    binding.root,
                    getString(R.string.shopping_new_list_created, event.title),
                    Snackbar.LENGTH_LONG
                ).show()
            }
            ShoppingMenuViewModel.MenuEvent.Conflict ->
                Snackbar.make(binding.root, R.string.shopping_new_list_conflict, Snackbar.LENGTH_LONG).show()
            ShoppingMenuViewModel.MenuEvent.Unreachable ->
                Snackbar.make(binding.root, R.string.shopping_new_list_needs_alfred, Snackbar.LENGTH_LONG).show()
            is ShoppingMenuViewModel.MenuEvent.Deleted ->
                Snackbar.make(
                    binding.root,
                    getString(R.string.shopping_delete_done, event.title),
                    Snackbar.LENGTH_LONG
                ).show()
            ShoppingMenuViewModel.MenuEvent.DeleteUnreachable ->
                Snackbar.make(binding.root, R.string.shopping_delete_needs_alfred, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun render(state: ShoppingMenuViewModel.MenuState) {
        binding.swipeRefresh.isRefreshing = state.loading || state.deleting

        renderFreshness(state)
        val lists: List<ShoppingListSummaryDto>? = when (val result = state.lists) {
            null -> null
            is AlfredResult.Fresh -> result.data
            is AlfredResult.Stale -> result.data
            AlfredResult.Unavailable -> null
        }

        // A list for the hub asks Alfred as it's created, and says so if he can't be
        // reached; a phone list needs nobody. So the create field stays open either way,
        // where lists can live on the phone. Where they only go to the hub, the tile
        // needs a live Alfred as it did before phone lists, and the field stands down.
        val hubOnly = newListRule() is NewListHome.Always
        val canCreate = !hubOnly || state.lists is AlfredResult.Fresh
        if (!canCreate && binding.rowShoppingNewList.isVisible) closeCreate()
        val phoneLists = state.phoneLists.orEmpty()
        val body = when {
            lists == null && state.lists == null -> R.string.shopping_menu_loading
            lists == null && phoneLists.isEmpty() -> R.string.shopping_menu_unavailable
            lists == null -> R.string.shopping_menu_hub_unavailable
            lists.isEmpty() && phoneLists.isEmpty() -> R.string.shopping_menu_empty
            else -> null
        }
        binding.tvShoppingMenuBody.isVisible = body != null
        body?.let(binding.tvShoppingMenuBody::setText)
        listAdapter.submit(
            lists,
            phoneLists.map(PhoneLists::summary),
            ShoppingListSummaryAdapter.Tile.NewList(hubOnly = hubOnly, canCreate = canCreate)
        )
    }

    private fun newListRule(): NewListHome? = ProfileSettings(requireContext()).profile()?.newListHome

    /**
     * Back on screen (from a list, say), read the hub again: a list may have moved
     * there or away while this screen sat underneath, and its tile should follow.
     * Quiet, and ignored while a read is already running.
     */
    override fun onStart() {
        super.onStart()
        viewModel.refresh(quiet = true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvShoppingLists.adapter = null
        _binding = null
    }

    private companion object {
        const val GRID_COLUMNS = 2
    }
}
