package com.personalmorningalarm.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.personalmorningalarm.R
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.entity.BundledQuote
import com.personalmorningalarm.databinding.DialogQuoteEditBinding
import com.personalmorningalarm.databinding.FragmentQuoteManagementBinding
import kotlinx.coroutines.launch

/** Manage the motivational-quote pool: list, add, edit, and swipe-to-delete. */
class QuoteManagementFragment : Fragment() {

    private var _binding: FragmentQuoteManagementBinding? = null
    private val binding get() = _binding!!

    private val viewModel: QuoteManagementViewModel by viewModels {
        ViewModelFactory(AlarmRepository(AppDatabase.getInstance(requireContext())))
    }

    private val quoteAdapter = QuoteAdapter(onEdit = { showQuoteDialog(it) })

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuoteManagementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindScreenTitle(binding.titleBar, R.string.quote_management_title)

        binding.rvQuotes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvQuotes.adapter = quoteAdapter
        attachSwipeToDelete()

        binding.fabAddQuote.setOnClickListener { showQuoteDialog(null) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.quotes.collect { quotes ->
                        quoteAdapter.submitList(quotes)
                        binding.tvQuoteCount.text =
                            if (quotes.size == 1) getString(R.string.quote_count_one)
                            else getString(R.string.quote_count, quotes.size)
                        binding.tvQuotesEmpty.visibility =
                            if (quotes.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch { viewModel.messages.collect { toast(it) } }
            }
        }
    }

    /** Swipe a row left/right to delete, with a confirmation dialog first. */
    private fun attachSwipeToDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val quote = quoteAdapter.currentList[position]

                // Pool must keep at least one — block the swipe outright.
                if (quoteAdapter.currentList.size <= 1) {
                    toast(getString(R.string.quote_keep_one))
                    quoteAdapter.notifyItemChanged(position)
                    return
                }
                confirmDelete(quote, position)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.rvQuotes)
    }

    private fun confirmDelete(quote: BundledQuote, position: Int) {
        // Restore the swiped row unless the delete actually goes through.
        val restore = { quoteAdapter.notifyItemChanged(position) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_quote_title)
            .setMessage(R.string.delete_quote_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteQuote(quote) { deleted -> if (!deleted) restore() }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> restore() }
            .setOnCancelListener { restore() }
            .show()
    }

    /** Add (when [existing] is null) or edit a quote via a two-field dialog. */
    private fun showQuoteDialog(existing: BundledQuote?) {
        val dialogBinding = DialogQuoteEditBinding.inflate(layoutInflater)
        dialogBinding.etQuoteText.setText(existing?.quoteText.orEmpty())
        dialogBinding.etQuoteAuthor.setText(existing?.author.orEmpty())

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.quote_add_title else R.string.quote_edit_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save, null) // overridden below to validate
            .setNegativeButton(R.string.cancel, null)
            .create()

        // Override the positive button so blank text keeps the dialog open.
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val text = dialogBinding.etQuoteText.text?.toString().orEmpty().trim()
                val author = dialogBinding.etQuoteAuthor.text?.toString()
                if (text.isEmpty()) {
                    dialogBinding.etQuoteText.error = getString(R.string.quote_text_required)
                    return@setOnClickListener
                }
                if (existing == null) viewModel.addQuote(text, author)
                else viewModel.updateQuote(existing, text, author)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvQuotes.adapter = null
        _binding = null
    }
}
