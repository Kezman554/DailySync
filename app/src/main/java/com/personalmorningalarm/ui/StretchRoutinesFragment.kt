package com.personalmorningalarm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
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
import com.personalmorningalarm.data.dao.RoutineWithCount
import com.personalmorningalarm.data.entity.AlarmConfig
import com.personalmorningalarm.data.model.MorningGoal
import com.personalmorningalarm.databinding.FragmentStretchRoutinesBinding
import kotlinx.coroutines.launch

/** Lists stretch routines, sets the active one, and maps routines to morning goals. */
class StretchRoutinesFragment : Fragment() {

    private var _binding: FragmentStretchRoutinesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StretchRoutinesViewModel by viewModels {
        ViewModelFactory(AlarmRepository(AppDatabase.getInstance(requireContext())))
    }

    private val routineAdapter = RoutineAdapter(
        onOpen = { openEditor(it.routine.id) },
        onSetActive = { viewModel.setActive(it.routine.id) }
    )

    private var routines: List<RoutineWithCount> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStretchRoutinesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindScreenTitle(binding.titleBar, R.string.routines_title)

        binding.rvRoutines.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRoutines.adapter = routineAdapter

        binding.fabAddRoutine.setOnClickListener {
            viewModel.createRoutine(getString(R.string.routine_new_name)) { id -> openEditor(id) }
        }

        binding.switchMatchGoal.setOnClickListener {
            viewModel.setMatchToGoal(binding.switchMatchGoal.isChecked)
        }
        binding.rowExerciseRoutine.setOnClickListener { chooseRoutineForGoal(MorningGoal.EXERCISE) }
        binding.rowProjectRoutine.setOnClickListener { chooseRoutineForGoal(MorningGoal.PROJECT) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.routines.collect {
                        routines = it
                        routineAdapter.submitList(it)
                        renderGoalMapping(viewModel.config.value)
                    }
                }
                launch { viewModel.config.collect(::renderConfig) }
                launch { viewModel.messages.collect { toast(it) } }
            }
        }
    }

    private fun renderConfig(config: AlarmConfig?) {
        val match = config?.matchRoutineToGoal == true
        binding.switchMatchGoal.isChecked = match
        binding.goalMappingGroup.visibility = if (match) View.VISIBLE else View.GONE
        routineAdapter.matchToGoal = match
        renderGoalMapping(config)
    }

    private fun renderGoalMapping(config: AlarmConfig?) {
        binding.tvExerciseRoutine.text = routineName(config?.exerciseRoutineId)
        binding.tvProjectRoutine.text = routineName(config?.projectRoutineId)
    }

    private fun routineName(id: Long?): String =
        routines.firstOrNull { it.routine.id == id }?.routine?.name
            ?: getString(R.string.routine_none_selected)

    private fun chooseRoutineForGoal(goal: MorningGoal) {
        if (routines.isEmpty()) return
        val names = routines.map { it.routine.name }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.routine_choose_title)
            .setItems(names) { _, which ->
                viewModel.setGoalRoutine(goal, routines[which].routine.id)
            }
            .show()
    }

    private fun openEditor(routineId: Long) {
        findNavController().navigate(
            R.id.action_routines_to_edit,
            bundleOf(RoutineEditFragment.ARG_ROUTINE_ID to routineId)
        )
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvRoutines.adapter = null
        _binding = null
    }
}
