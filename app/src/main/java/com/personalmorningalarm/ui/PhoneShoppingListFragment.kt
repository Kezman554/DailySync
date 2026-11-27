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
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.ShoppingItem
import com.personalmorningalarm.data.model.ShoppingList
import com.personalmorningalarm.data.phone.PhoneListStore
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.profile.ProfileSettings
import com.personalmorningalarm.data.remote.ShoppingShare
import com.personalmorningalarm.databinding.FragmentShoppingListBinding
import kotlinx.coroutines.launch

/**
 * One shopping list kept on the phone. The same screen as [ShoppingListFragment] —
 * add at the bottom, tap ticks, long press drops — less everything about Alfred:
 * no saved-copy age, no sync line, no conflict notice, no pull to refresh. Keep
 * ticked is kept on the list here, and works offline.
 */
class PhoneShoppingListFragment : Fragment() {

    private var _binding: FragmentShoppingListBinding? = null
    private val binding get() = _binding!!

    private val listId: String by lazy { requireArguments().getString(ShoppingListFragment.ARG_LIST_ID).orEmpty() }
    private val listTitle: String by lazy {
        requireArguments().getString(ShoppingListFragment.ARG_LIST_TITLE).orEmpty()
    }

    private val viewModel: PhoneShoppingListViewModel by viewModels {
        viewModelFactory {
            initializer {
                PhoneShoppingListViewModel(PhoneListStore.getInstance(requireContext().applicationContext), listId)
            }
        }
    }

    private val shareViewModel: ShoppingShareViewModel by viewModels {
        viewModelFactory { initializer { ShoppingShareViewModel(ShoppingShare.getInstance(requireContext())) } }
    }

    private val me: Profile? by lazy { ProfileSettings(requireContext()).profile() }

    private val itemAdapter by lazy { ShoppingItemAdapter(onTick = viewModel::tickItem, onDrop = ::confirmDrop) }

    /** Keep ticked's Bought rows: a tap puts one back. */
    private val boughtAdapter by lazy {
        ShoppingItemAdapter(onTick = viewModel::tickItem, onDrop = ::confirmDrop, onUntick = viewModel::untickItem)
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

        binding.freshness.root.isVisible = false
        binding.syncLine.root.isVisible = false
        binding.rowShoppingConflict.isVisible = false
        binding.swipeRefresh.isEnabled = false

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

        binding.switchShare.setOnClickListener { onShareToggled() }
        binding.switchKeepTicked.setOnClickListener { viewModel.setKeepTicked(binding.switchKeepTicked.isChecked) }
        binding.btnClearBought.setOnClickListener { confirmClearBought() }

        binding.etShoppingAdd.setHint(R.string.shopping_add_hint)
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
                launch { viewModel.items.collect { render(it, viewModel.keepTicked.value) } }
                launch {
                    viewModel.keepTicked.collect { keep ->
                        renderKeepTicked(keep)
                        render(viewModel.items.value, keep)
                    }
                }
                launch { shareViewModel.working.collect { renderShare() } }
                launch { shareViewModel.results.collect(::onShareResult) }
            }
        }
    }

    /** Sharing moves the list to the hub: asked first, and the switch shows off until it's done. */
    private fun onShareToggled() {
        binding.switchShare.isChecked = false
        confirmShare(listTitle, me, onConfirm = { shareViewModel.sharePhoneList(listId, listTitle) })
    }

    private fun onShareResult(result: ShoppingShareViewModel.Result) {
        showShareResult(binding.root, result)
        openMovedList(result.outcome, R.id.phoneShoppingListFragment)
    }

    /** A phone list is never shared: the toggle offers the move, and says where it would go. */
    private fun renderShare() {
        val profile = me
        val working = shareViewModel.working.value
        // The card holds Keep ticked too, so it shows whoever's phone this is.
        binding.rowShare.isVisible = true
        binding.switchShare.isVisible = profile != null
        binding.tvShareNote.isVisible = profile != null
        binding.switchKeepTicked.isEnabled = !working
        if (profile == null) return
        binding.switchShare.isChecked = false
        binding.switchShare.isEnabled = !working
        binding.switchShare.text = getString(R.string.shopping_share_with, otherName(profile))
        binding.tvShareNote.text = if (working) {
            getString(R.string.shopping_share_working)
        } else {
            getString(R.string.shopping_share_note_phone, otherName(profile))
        }
        // Mid-move the phone list takes no edits (the store ignores them too): they'd be lost with it.
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.shopping_drop_title)
            .setMessage(getString(R.string.shopping_drop_message, item.task))
            .setPositiveButton(R.string.chalkboard_drop_confirm) { _, _ -> viewModel.dropItem(item) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderKeepTicked(keep: Boolean) {
        binding.switchKeepTicked.isChecked = keep
        binding.tvKeepTickedNote.setText(
            if (keep) R.string.shopping_keep_ticked_note_on else R.string.shopping_keep_ticked_note_off
        )
    }

    /** Clear bought, asked first: they're gone for good. */
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

    private fun render(items: List<ShoppingItem>?, keepTicked: Boolean) {
        binding.titleBar.tvScreenMeta.isVisible = items != null
        binding.titleBar.tvScreenMeta.text = getString(R.string.shopping_left, items.orEmpty().count { !it.done })
        if (items == null) {
            binding.rvShoppingItems.isVisible = false
            binding.tvShoppingBody.isVisible = false
            return
        }
        showItems(binding, itemAdapter, boughtAdapter, items, keepTicked)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvShoppingItems.adapter = null
        binding.rvShoppingBought.adapter = null
        _binding = null
    }
}
