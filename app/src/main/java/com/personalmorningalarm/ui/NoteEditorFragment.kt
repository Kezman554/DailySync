package com.personalmorningalarm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.fragment.findNavController
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.Notes
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.NoteSync
import com.personalmorningalarm.databinding.FragmentNoteEditorBinding
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The plain full-screen editor: a title bar and the text. What's typed is what
 * shows, and it's saved on the way out, never as it's typed. The window resizes for
 * the keyboard (adjustResize on MainActivity), and the field scrolls inside what's
 * left, so the line being typed stays above it.
 */
class NoteEditorFragment : Fragment() {

    private var _binding: FragmentNoteEditorBinding? = null
    private val binding get() = _binding!!

    private val isNew: Boolean get() = requireArguments().getBoolean(ARG_IS_NEW)

    private val viewModel: NoteEditorViewModel by viewModels {
        viewModelFactory {
            initializer {
                val context = requireContext().applicationContext
                NoteEditorViewModel(
                    AlfredRepository(context),
                    NoteSync.getInstance(context),
                    requireArguments().getString(ARG_NOTE_ID).orEmpty(),
                    isNew,
                    createSavedStateHandle()
                )
            }
        }
    }

    /** Whether this view has been given the note yet; it's only ever given it once. */
    private var populated = false

    private var deleteDialog: AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNoteEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        populated = false
        bindScreenTitle(binding.titleBar, if (isNew) R.string.notes_new else R.string.note_title)
        binding.titleBar.btnBack.setOnClickListener { leave() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { leave() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        // Keeps the saved-copy line's age honest. Never retries: a refetch can't
        // replace text that's being edited.
        FreshnessWatch.start(
            viewLifecycleOwner,
            render = ::renderFreshness,
            wantsRetry = { false },
            retry = {}
        )
    }

    /** Only a saved copy is worth a line here. Live text ageing as it's edited is just editing. */
    private fun renderFreshness() {
        val source = (viewModel.state.value as? NoteEditorViewModel.EditorState.Ready)?.source
        val age = Freshness.of(source, System.currentTimeMillis(), ZoneId.systemDefault())
        FreshnessView.render(binding.freshness, age?.takeIf { it.saved }, AlfredReachability.state.value)
    }

    private fun render(state: NoteEditorViewModel.EditorState) {
        val ready = state is NoteEditorViewModel.EditorState.Ready
        binding.etNote.isVisible = ready
        binding.tvNoteStatus.isVisible = !ready
        binding.tvNoteStatus.setText(
            if (state is NoteEditorViewModel.EditorState.Loading) R.string.notes_loading else R.string.note_unavailable
        )
        renderFreshness()
        if (ready && !populated) {
            populated = true
            binding.etNote.setText(viewModel.draft)
            if (viewModel.draft.isEmpty()) showKeyboard()
        }
    }

    private fun showKeyboard() {
        binding.etNote.requestFocus()
        binding.etNote.post {
            _binding?.let {
                requireContext().getSystemService<InputMethodManager>()
                    ?.showSoftInput(it.etNote, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun leave() {
        when (viewModel.leave(currentText())) {
            Notes.Leaving.CONFIRM_DELETE -> confirmDelete()
            else -> close()
        }
    }

    /** An emptied note: yes deletes it, and anything else puts the text back. */
    private fun confirmDelete() {
        val title = Notes.summary(viewModel.noteId, viewModel.kept.orEmpty()).title.orEmpty()
        deleteDialog = showNoteDeleteDialog(
            requireContext(),
            title,
            onDelete = {
                viewModel.confirmDelete()
                close()
            },
            onKeep = {
                binding.etNote.setText(viewModel.keepNote())
                binding.etNote.setSelection(binding.etNote.length())
            }
        )
    }

    private fun close() {
        requireContext().getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(binding.etNote.windowToken, 0)
        findNavController().navigateUp()
    }

    override fun onStop() {
        super.onStop()
        viewModel.background(currentText())
    }

    private fun currentText(): String = binding.etNote.text?.toString().orEmpty()

    override fun onDestroyView() {
        super.onDestroyView()
        deleteDialog?.dismiss()
        deleteDialog = null
        _binding = null
    }

    companion object {
        const val ARG_NOTE_ID = "noteId"
        const val ARG_IS_NEW = "isNew"
    }
}
