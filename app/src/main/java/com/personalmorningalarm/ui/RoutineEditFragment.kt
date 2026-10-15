package com.personalmorningalarm.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.personalmorningalarm.R
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.entity.StretchExercise
import com.personalmorningalarm.data.entity.StretchRoutine
import com.personalmorningalarm.databinding.DialogStretchExerciseBinding
import com.personalmorningalarm.databinding.FragmentRoutineEditBinding
import kotlinx.coroutines.launch

/** View/edit a single routine: rename, reorder, add/edit/delete stretches, delete routine. */
class RoutineEditFragment : Fragment() {

    private var _binding: FragmentRoutineEditBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RoutineEditViewModel by viewModels {
        ViewModelFactory(AlarmRepository(AppDatabase.getInstance(requireContext())))
    }

    private val exerciseAdapter = StretchExerciseAdapter(
        onEdit = { showExerciseDialog(it) },
        onMoveUp = { viewModel.moveUp(it) },
        onMoveDown = { viewModel.moveDown(it) },
        onDelete = { confirmDeleteExercise(it) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRoutineEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        viewModel.setRoutine(requireArguments().getLong(ARG_ROUTINE_ID, -1L))

        binding.rvExercises.layoutManager = LinearLayoutManager(requireContext())
        binding.rvExercises.adapter = exerciseAdapter

        binding.fabAddStretch.setOnClickListener { showExerciseDialog(null) }
        binding.btnRename.setOnClickListener { showRenameDialog() }
        binding.btnDeleteRoutine.setOnClickListener { confirmDeleteRoutine() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.routine.collect { routine ->
                        binding.tvRoutineName.text = routine?.name.orEmpty()
                    }
                }
                launch {
                    viewModel.exercises.collect { exercises ->
                        exerciseAdapter.submitList(exercises)
                        binding.tvRoutineEmpty.visibility =
                            if (exercises.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch { viewModel.messages.collect { toast(it) } }
            }
        }
    }

    private fun showRenameDialog() {
        val current = viewModel.routine.value ?: return
        val input = EditText(requireContext()).apply {
            setText(current.name)
            setSelection(text.length)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.routine_rename_title)
            .setView(input.apply { setPadding(pad, pad / 2, pad, 0) })
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) viewModel.rename(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Add (when [existing] is null) or edit a stretch via the three-field dialog. */
    private fun showExerciseDialog(existing: StretchExercise?) {
        val dialogBinding = DialogStretchExerciseBinding.inflate(layoutInflater)
        existing?.let {
            dialogBinding.etStretchName.setText(it.name)
            dialogBinding.etStretchSeconds.setText(it.durationSeconds.toString())
            dialogBinding.etStretchInstructions.setText(it.instructions)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.stretch_add_title else R.string.stretch_edit_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save, null) // overridden to validate
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.etStretchName.text?.toString().orEmpty().trim()
                val seconds = dialogBinding.etStretchSeconds.text?.toString()?.toIntOrNull() ?: 0
                val instructions = dialogBinding.etStretchInstructions.text?.toString().orEmpty().trim()
                if (name.isEmpty()) {
                    dialogBinding.etStretchName.error = getString(R.string.stretch_name_required)
                    return@setOnClickListener
                }
                if (seconds <= 0) {
                    dialogBinding.etStretchSeconds.error = getString(R.string.stretch_seconds_required)
                    return@setOnClickListener
                }
                if (existing == null) viewModel.addExercise(name, seconds, instructions)
                else viewModel.updateExercise(existing, name, seconds, instructions)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDeleteExercise(exercise: StretchExercise) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.stretch_delete_title)
            .setMessage(getString(R.string.stretch_delete_message, exercise.name))
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteExercise(exercise) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteRoutine() {
        val routine: StretchRoutine = viewModel.routine.value ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.routine_delete_title)
            .setMessage(getString(R.string.routine_delete_message, routine.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteRoutine { deleted -> if (deleted) findNavController().navigateUp() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvExercises.adapter = null
        _binding = null
    }

    companion object {
        const val ARG_ROUTINE_ID = "routineId"
    }
}
