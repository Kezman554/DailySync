package com.personalmorningalarm.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.WeekDay
import com.personalmorningalarm.data.model.WeekSchedule
import com.personalmorningalarm.data.model.WeekScheduleDto
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.databinding.FragmentWeekScheduleBinding
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Daily Schedule tile's screen: the whole plan week, one swipeable page per
 * day, opening on today, with a pill per day to jump straight to it and a footer
 * naming the days either side. The plan's first and last days are hard edges — no
 * paging into other weeks. The alarm-flow content screen is deliberately not
 * this: it stays today-only on GET /daily-schedule.
 */
class WeekScheduleFragment : Fragment() {

    private var _binding: FragmentWeekScheduleBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WeekScheduleViewModel by viewModels {
        AlfredViewModelFactory(AlfredRepository(requireContext()))
    }

    private val dayAdapter = WeekDayAdapter()

    /** Anchor on today once per fetch, not on every re-render mid-swipe. */
    private var anchored = false

    /** The plan days the pills were built for, so they're rebuilt only when that changes. */
    private var pillDays: List<LocalDate> = emptyList()

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = renderSelection(position)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWeekScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindScreenTitle(binding.titleBar, R.string.week_title)
        binding.pagerWeek.adapter = dayAdapter
        binding.pagerWeek.registerOnPageChangeCallback(pageCallback)
        binding.tvPrevDay.setOnClickListener { binding.pagerWeek.currentItem -= 1 }
        binding.tvNextDay.setOnClickListener { binding.pagerWeek.currentItem += 1 }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        FreshnessWatch.start(
            viewLifecycleOwner,
            render = { renderFreshness(viewModel.state.value) },
            wantsRetry = { Freshness.wantsRetry(weekAge(viewModel.state.value)) },
            retry = viewModel::refresh
        )
    }

    private fun render(state: WeekScheduleViewModel.WeekState) {
        // The calendar is layered onto whatever the plan renders — it is never the
        // reason a week fails to show.
        dayAdapter.setEvents(state.eventsByDate)
        when (val result = state.week) {
            null -> showStatus(R.string.schedule_loading)
            AlfredResult.Unavailable -> showStatus(R.string.schedule_unavailable)
            is AlfredResult.Fresh -> showWeek(result.data)
            is AlfredResult.Stale -> showWeek(result.data)
        }
        renderFreshness(state)
    }

    /** The plan's age, or the calendar's when that is the older of the two. */
    private fun weekAge(state: WeekScheduleViewModel.WeekState): Freshness.Age? {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        return Freshness.worst(Freshness.of(state.week, now, zone), Freshness.of(state.calendar, now, zone))
    }

    private fun renderFreshness(state: WeekScheduleViewModel.WeekState) {
        FreshnessView.render(binding.freshness, weekAge(state), AlfredReachability.state.value)
    }

    private fun showWeek(dto: WeekScheduleDto) {
        val today = LocalDate.now()
        val days = WeekSchedule.days(dto)
        if (days.isEmpty()) {
            showStatus(R.string.week_no_plan)
            return
        }
        binding.tvWeekStatus.isVisible = false
        binding.pagerWeek.isVisible = true
        binding.dayPills.isVisible = true
        binding.weekFooter.isVisible = true
        dayAdapter.setDays(days, today)
        buildPills(days, today)
        if (!anchored) {
            anchored = true
            binding.pagerWeek.setCurrentItem(WeekSchedule.anchorIndex(days, today), false)
        }
        renderSelection(binding.pagerWeek.currentItem)
    }

    /** A pill per plan day — abbreviation over date — that jumps the pager to it. */
    private fun buildPills(days: List<WeekDay>, today: LocalDate) {
        val dates = days.map { it.date }
        if (dates == pillDays) return
        pillDays = dates
        val row = binding.dayPills
        row.removeAllViews()
        days.forEachIndexed { index, day ->
            val pill = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_day_pill)
                isClickable = true
                isFocusable = true
                contentDescription = day.date.format(PILL_DESCRIPTION_FORMAT)
                setOnClickListener { binding.pagerWeek.setCurrentItem(index, true) }
            }
            pill.addView(pillText(day.date.format(PILL_DAY_FORMAT), PILL_DAY_SP))
            pill.addView(pillText(day.date.dayOfMonth.toString(), PILL_DATE_SP))
            pill.tag = day.date.isBefore(today)
            row.addView(pill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun pillText(text: String, sizeSp: Float) = TextView(requireContext()).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = sizeSp
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * The selected day's pill gets the surface fill; past days are in secondary
     * text. The footer names the days either side, hidden at the plan's edges.
     */
    private fun renderSelection(position: Int) {
        val row = binding.dayPills
        for (i in 0 until row.childCount) {
            val pill = row.getChildAt(i) as LinearLayout
            val selected = i == position
            pill.isSelected = selected
            val past = pill.tag == true
            val colour = MaterialColors.getColor(
                pill,
                if (past && !selected) MaterialR.attr.colorOnSurfaceVariant else MaterialR.attr.colorOnSurface
            )
            for (j in 0 until pill.childCount) (pill.getChildAt(j) as TextView).setTextColor(colour)
        }
        val prev = dayAdapter.dayAt(position - 1)
        val next = dayAdapter.dayAt(position + 1)
        binding.tvPrevDay.visibility = if (prev != null) View.VISIBLE else View.INVISIBLE
        binding.tvNextDay.visibility = if (next != null) View.VISIBLE else View.INVISIBLE
        prev?.let { binding.tvPrevDay.text = getString(R.string.week_prev_day, it.date.format(FOOTER_FORMAT)) }
        next?.let { binding.tvNextDay.text = getString(R.string.week_next_day, it.date.format(FOOTER_FORMAT)) }
    }

    private fun showStatus(text: Int) {
        binding.pagerWeek.isVisible = false
        binding.dayPills.isVisible = false
        binding.weekFooter.isVisible = false
        binding.tvWeekStatus.isVisible = true
        binding.tvWeekStatus.text = getString(text)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.pagerWeek.unregisterOnPageChangeCallback(pageCallback)
        pillDays = emptyList()
        _binding = null
    }

    private companion object {
        val PILL_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        val PILL_DESCRIPTION_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())
        val FOOTER_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())
        const val PILL_DAY_SP = 12f
        const val PILL_DATE_SP = 17f
    }
}
