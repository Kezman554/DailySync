package com.personalmorningalarm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.NoteRow
import com.personalmorningalarm.data.model.Notes
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.NoteSync
import com.personalmorningalarm.data.remote.dataOrNull
import com.personalmorningalarm.databinding.FragmentNotesBinding
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The Notes tile: every note as a title and preview, newest first, and a button to
 * start another. A tap opens the editor and a long press offers to delete. Works
 * anywhere: queued notes are listed from the phone, on top of the saved copy.
 */
class NotesFragment : Fragment() {

    private var _binding: FragmentNotesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NoteListViewModel by viewModels {
        NoteListViewModelFactory(AlfredRepository(requireContext()), NoteSync.getInstance(requireContext()))
    }

    private val noteAdapter by lazy {
        NoteAdapter(onClick = { open(it.id, isNew = false) }, onLongPress = ::confirmDelete)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindScreenTitle(binding.titleBar, R.string.notes_title)

        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.rvNotes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNotes.adapter = noteAdapter
        // The id is made here, not in the editor, so it travels in the nav arguments
        // and a restored editor is still the same note.
        binding.fabNewNote.setOnClickListener { open(Notes.newId(), isNew = true) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        FreshnessWatch.start(
            viewLifecycleOwner,
            render = { renderFreshness(viewModel.state.value) },
            wantsRetry = { Freshness.wantsRetry(age(viewModel.state.value)) },
            retry = { viewModel.refresh(quiet = true) }
        )
    }

    override fun onStart() {
        super.onStart()
        // Back from the editor, or back from another app. The first start's fetch is
        // already running, and a second is ignored.
        viewModel.refresh(quiet = true)
    }

    private fun open(noteId: String, isNew: Boolean) {
        // A second tap can land before the first has finished navigating.
        if (findNavController().currentDestination?.id != R.id.notesFragment) return
        findNavController().navigate(
            R.id.action_notes_to_editor,
            bundleOf(
                NoteEditorFragment.ARG_NOTE_ID to noteId,
                NoteEditorFragment.ARG_IS_NEW to isNew
            )
        )
    }

    private fun confirmDelete(row: NoteRow) {
        showNoteDeleteDialog(requireContext(), row.title, onDelete = { viewModel.delete(row.id) })
    }

    private fun age(state: NoteListViewModel.NoteListState): Freshness.Age? =
        Freshness.of(state.result, System.currentTimeMillis(), ZoneId.systemDefault())

    private fun renderFreshness(state: NoteListViewModel.NoteListState) {
        FreshnessView.render(binding.freshness, age(state), AlfredReachability.state.value)
    }

    private fun render(state: NoteListViewModel.NoteListState) {
        binding.swipeRefresh.isRefreshing = state.loading
        renderFreshness(state)

        val waiting = state.pending.count { !it.failed }
        binding.syncLine.root.isVisible = waiting > 0
        binding.syncLine.tvSyncText.text =
            resources.getQuantityString(R.plurals.notes_pending_count, waiting, waiting)

        val rows = Notes.merged(state.result?.dataOrNull().orEmpty(), state.pending)
        binding.rvNotes.isVisible = rows.isNotEmpty()
        binding.tvNotesBody.isVisible = rows.isEmpty()
        binding.tvNotesBody.setText(
            when (state.result) {
                null -> R.string.notes_loading
                AlfredResult.Unavailable -> R.string.notes_unavailable
                else -> R.string.notes_empty
            }
        )
        binding.titleBar.tvScreenMeta.isVisible = rows.isNotEmpty()
        binding.titleBar.tvScreenMeta.text = rows.size.toString()
        noteAdapter.submitList(rows)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvNotes.adapter = null
        _binding = null
    }
}
