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
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.RollingTodo
import com.personalmorningalarm.data.model.RollingTodoItem
import com.personalmorningalarm.data.phone.PhoneListStore
import com.personalmorningalarm.databinding.FragmentRollingTodoBinding
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The To-Do tile when Settings keeps its list on the phone. The same screen as
 * [RollingTodoFragment] — Open / Oldest, tap ticks, long press drops, + adds — less
 * everything about Alfred: no saved-copy age, no sync line, no conflict notice, no
 * pull to refresh. There's nothing to sync.
 */
class PhoneTodoFragment : Fragment() {

    private var _binding: FragmentRollingTodoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhoneTodoViewModel by viewModels {
        viewModelFactory {
            initializer { PhoneTodoViewModel(PhoneListStore.getInstance(requireContext().applicationContext)) }
        }
    }

    private val todoAdapter by lazy {
        RollingTodoAdapter(onTick = viewModel::tickItem, onDrop = ::confirmDrop).also { it.writable = true }
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
        binding.titleBar.tvScreenMeta.isVisible = true
        binding.titleBar.tvScreenMeta.setText(R.string.list_home_meta_phone)

        binding.freshness.root.isVisible = false
        binding.syncLine.root.isVisible = false
        binding.rowChalkboardConflict.isVisible = false
        binding.swipeRefresh.isEnabled = false

        binding.rvChalkboard.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChalkboard.adapter = todoAdapter
        binding.rvChalkboard.addItemDecoration(
            MaterialDividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL).apply {
                dividerColor = MaterialColors.getColor(binding.root, R.attr.colorSurface2)
                isLastItemDecorated = false
            }
        )

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
                viewModel.items.collect(::render)
            }
        }
    }

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

    /** Adds and keeps the field open for the next one; Add on an empty field closes it. */
    private fun submitAdd() {
        val text = binding.etChalkboardAdd.text?.toString().orEmpty()
        if (text.isBlank()) {
            closeAdd()
            return
        }
        viewModel.addItem(text)
        binding.etChalkboardAdd.setText("")
    }

    private fun confirmDrop(item: RollingTodoItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.chalkboard_drop_title)
            .setMessage(getString(R.string.chalkboard_drop_message, item.task))
            .setPositiveButton(R.string.chalkboard_drop_confirm) { _, _ -> viewModel.dropItem(item) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun render(items: List<RollingTodoItem>?) {
        binding.rvChalkboard.isVisible = !items.isNullOrEmpty()
        binding.tvChalkboardBody.isVisible = items.isNullOrEmpty()
        binding.tvChalkboardBody.text = if (items == null) "" else getString(R.string.chalkboard_empty)
        renderStats(items.orEmpty())
        todoAdapter.submitList(items.orEmpty())
    }

    /** OPEN n and OLDEST n days, as the hub's to-do counts them. */
    private fun renderStats(items: List<RollingTodoItem>) {
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
