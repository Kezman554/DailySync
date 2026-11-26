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
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.fragment.findNavController
import com.personalmorningalarm.R
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.dataOrNull
import com.personalmorningalarm.databinding.FragmentCalendarAgendaBinding
import com.personalmorningalarm.databinding.ItemAgendaDayBinding
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Calendar tile's screen: the Young Family calendar for the weeks ahead, a card
 * per day that has anything on, and a + that opens the Add Event screen.
 */
class CalendarAgendaFragment : Fragment() {

    private var _binding: FragmentCalendarAgendaBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CalendarAgendaViewModel by viewModels {
        viewModelFactory {
            initializer { CalendarAgendaViewModel(AlfredRepository(requireContext().applicationContext)) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCalendarAgendaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindScreenTitle(binding.titleBar, R.string.calendar_title)
        binding.titleBar.btnScreenAction.apply {
            isVisible = true
            setImageResource(R.drawable.ic_add)
            contentDescription = getString(R.string.week_add_event)
            setOnClickListener {
                findNavController().navigate(
                    R.id.action_calendar_to_addEvent,
                    bundleOf(AddEventFragment.ARG_DATE to LocalDate.now().toString())
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        FreshnessWatch.start(
            viewLifecycleOwner,
            render = { renderFreshness(viewModel.state.value) },
            wantsRetry = { Freshness.wantsRetry(age(viewModel.state.value)) },
            retry = viewModel::refresh
        )
    }

    private fun render(state: CalendarAgendaViewModel.State) {
        val result = state.calendar
        val days = HomeTiles.agenda(result?.dataOrNull(), state.from, CalendarAgendaViewModel.WEEKS * 7)
        val status = when {
            result == null -> getString(R.string.schedule_loading)
            result == AlfredResult.Unavailable -> getString(R.string.calendar_unavailable)
            days.isEmpty() -> getString(R.string.calendar_empty)
            else -> null
        }
        binding.tvAgendaStatus.isVisible = status != null
        binding.tvAgendaStatus.text = status

        val container = binding.agendaDays
        container.removeAllViews()
        days.forEach { (date, events) ->
            val day = ItemAgendaDayBinding.inflate(layoutInflater, container, true)
            day.tvAgendaLabel.text = when (date) {
                state.from -> getString(R.string.week_day_today)
                state.from.plusDays(1) -> getString(R.string.calendar_tomorrow)
                else -> date.format(WEEKDAY)
            }
            day.tvAgendaDate.text = date.format(DATE)
            EventRows.render(day.agendaRows, events)
        }
        renderFreshness(state)
    }

    private fun age(state: CalendarAgendaViewModel.State): Freshness.Age? =
        Freshness.of(state.calendar, System.currentTimeMillis(), ZoneId.systemDefault())

    private fun renderFreshness(state: CalendarAgendaViewModel.State) {
        FreshnessView.render(binding.freshness, age(state), AlfredReachability.state.value)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault())
    }
}
