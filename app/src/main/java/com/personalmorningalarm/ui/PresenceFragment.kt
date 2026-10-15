package com.personalmorningalarm.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.personalmorningalarm.R
import com.personalmorningalarm.data.remote.DashboardSettings
import com.personalmorningalarm.data.remote.PresenceRepository
import com.personalmorningalarm.databinding.FragmentPresenceBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The kitchen sign-in tile: opens and closes the wall's presence session.
 *
 * Every action is live against the dashboard — there's no offline path here, by
 * design (see [PresenceRepository]). The PIN is typed on an on-screen keypad into
 * a plain field of this fragment: never in the ViewModel, saved state or a view
 * that saves its own state, and cleared after every attempt (right or wrong) and
 * whenever the screen stops. The dashboard's PIN has no fixed length, so a Sign in
 * button sends it rather than the last digit.
 */
class PresenceFragment : Fragment() {

    private var _binding: FragmentPresenceBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PresenceViewModel by viewModels {
        PresenceViewModelFactory(PresenceRepository(requireContext()))
    }

    /** The digits typed so far. Lives only as long as this view does. */
    private val pin = StringBuilder()

    /**
     * The ring's full circle: the longest time-left seen for the current session.
     * The dashboard only says how long is left, so the first reading after signing
     * in is the whole window, and a touch at the wall that extends it refills it.
     */
    private var ringTotalMs = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPresenceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindScreenTitle(binding.titleBar, R.string.presence_title)

        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        listOf(
            binding.key0, binding.key1, binding.key2, binding.key3, binding.key4,
            binding.key5, binding.key6, binding.key7, binding.key8, binding.key9
        ).forEachIndexed { digit, key -> key.setOnClickListener { typeDigit(digit) } }
        binding.keyDelete.setOnClickListener {
            if (pin.isNotEmpty()) pin.setLength(pin.length - 1)
            renderPin()
        }
        binding.keyDelete.setOnLongClickListener {
            clearPin()
            true
        }
        binding.btnPresenceSignIn.setOnClickListener { submitPin() }
        binding.btnPresenceSignOut.setOnClickListener { viewModel.signOut() }
        renderPin()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.events.collect(::showEvent) }
                // Keeps the ring and its m:ss counting down between reads.
                launch {
                    while (true) {
                        delay(1_000)
                        render(viewModel.state.value)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Read on every visit, not just the first: the session may have been
        // opened at the wall, or lapsed, while the phone was elsewhere.
        viewModel.refresh()
    }

    override fun onStop() {
        super.onStop()
        // A half-typed PIN doesn't wait in the background for whoever picks the phone up.
        clearPin()
    }

    private fun typeDigit(digit: Int) {
        if (pin.length >= MAX_DIGITS) return
        pin.append(digit)
        // A refusal belongs to the attempt that earned it, not the next one being typed.
        binding.tvPresenceMessage.text = null
        renderPin()
    }

    private fun clearPin() {
        pin.setLength(0)
        if (_binding != null) renderPin()
    }

    private fun submitPin() {
        if (pin.isEmpty()) return
        val attempt = pin.toString()
        clearPin()
        binding.tvPresenceMessage.text = null
        viewModel.signIn(attempt)
    }

    /** One accent dot per digit, and Sign in only once there's something to send. */
    private fun renderPin() {
        val dots = binding.pinDots
        while (dots.childCount < pin.length) {
            val size = resources.getDimensionPixelSize(R.dimen.pin_dot_size)
            dots.addView(
                View(requireContext()).apply { setBackgroundResource(R.drawable.pin_dot_filled) },
                LinearLayout.LayoutParams(size, size).apply {
                    marginStart = resources.getDimensionPixelSize(R.dimen.pin_dot_gap) / 2
                    marginEnd = marginStart
                }
            )
        }
        while (dots.childCount > pin.length) dots.removeViewAt(dots.childCount - 1)
        dots.contentDescription =
            resources.getQuantityString(R.plurals.presence_digits_entered, pin.length, pin.length)
        binding.btnPresenceSignIn.isEnabled = pin.isNotEmpty() && !viewModel.state.value.busy
    }

    private fun showEvent(event: PresenceViewModel.PresenceEvent) {
        val refusal = when (event) {
            // The screen itself turns over to "Signed in" or back to the keypad,
            // so success needs no toast on top (it would cover Sign out).
            PresenceViewModel.PresenceEvent.SignedIn,
            PresenceViewModel.PresenceEvent.SignedOut -> return
            PresenceViewModel.PresenceEvent.WrongPin -> getString(R.string.presence_wrong_pin)
            is PresenceViewModel.PresenceEvent.LockedOut -> event.retryAfterSeconds
                ?.let { getString(R.string.presence_locked_out_seconds, it) }
                ?: getString(R.string.presence_locked_out)
            PresenceViewModel.PresenceEvent.NotConfigured -> getString(R.string.presence_not_configured)
            PresenceViewModel.PresenceEvent.Unreachable ->
                getString(R.string.presence_unreachable, DashboardSettings(requireContext()).getHost())
            is PresenceViewModel.PresenceEvent.Failed ->
                getString(R.string.presence_failed, event.httpCode)
        }
        // Refusals sit under the dots, in the error colour, where the PIN was typed.
        // Signed in there's no keypad to sit under (a failed sign-out), so they pop up.
        if (binding.groupSignedOut.isVisible) {
            binding.tvPresenceMessage.text = refusal
        } else {
            snackbar(refusal)
        }
    }

    // Room for the whole sentence: the unreachable message's point ("nothing will
    // be sent later") is its second half, which two lines cut off.
    private fun snackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setTextMaxLines(SNACKBAR_MAX_LINES)
            .show()
    }

    private fun render(state: PresenceViewModel.PresenceState) {
        val binding = _binding ?: return
        binding.swipeRefresh.isRefreshing = state.loading

        val session = state.session
        binding.keypad.children.forEach { it.isEnabled = !state.busy }
        binding.btnPresenceSignIn.isEnabled = pin.isNotEmpty() && !state.busy
        binding.btnPresenceSignOut.isEnabled = !state.busy
        // Sign-out shows whenever a session might be open — including "unknown",
        // so an unreachable read doesn't hide the way to try closing it.
        binding.btnPresenceSignOut.isVisible = session !is PresenceViewModel.Session.Closed

        val open = session as? PresenceViewModel.Session.Open
        binding.groupSignedIn.isVisible = open != null
        binding.groupSignedOut.isVisible = open == null

        when (session) {
            is PresenceViewModel.Session.Open -> renderOpen(session)
            PresenceViewModel.Session.Closed -> {
                ringTotalMs = 0L
                binding.tvSignedOutLabel.setText(R.string.presence_label_signed_out)
                // "Can't reach it" was about the last read; this one answered.
                if (binding.tvPresenceMessage.text?.toString() == unreachableDetail()) {
                    binding.tvPresenceMessage.text = null
                }
            }
            PresenceViewModel.Session.Unknown -> {
                ringTotalMs = 0L
                binding.tvSignedOutLabel.setText(
                    if (state.unreachable) R.string.presence_label_unknown else R.string.presence_label_checking
                )
                // Say why, unless an attempt's own refusal is already showing.
                if (state.unreachable && binding.tvPresenceMessage.text.isNullOrEmpty()) {
                    binding.tvPresenceMessage.text = unreachableDetail()
                }
            }
        }
    }

    private fun unreachableDetail(): String =
        getString(R.string.presence_unreachable_detail, DashboardSettings(requireContext()).getHost())

    private fun renderOpen(session: PresenceViewModel.Session.Open) {
        binding.tvPresenceMessage.text = null
        binding.tvOpenAs.text = session.person
            ?.let { getString(R.string.presence_open_as, it) }
            ?: getString(R.string.presence_open)

        val expiresAt = session.expiresAt
        binding.ringGroup.isVisible = expiresAt != null
        if (expiresAt == null) return
        val leftMs = (expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        ringTotalMs = maxOf(ringTotalMs, leftMs)
        binding.ringLapse.setProgress(if (ringTotalMs > 0) leftMs.toFloat() / ringTotalMs else 0f)
        val left = leftMs / 1000
        binding.tvLapseTime.text = getString(R.string.presence_lapse_time, left / 60, left % 60)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val SNACKBAR_MAX_LINES = 4
        /** Far longer than any real PIN; only stops a stuck thumb filling the row. */
        const val MAX_DIGITS = 12
    }
}
