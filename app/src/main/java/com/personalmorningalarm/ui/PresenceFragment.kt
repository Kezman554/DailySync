package com.personalmorningalarm.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
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
 * design (see [PresenceRepository]). The PIN field is cleared after every attempt,
 * right or wrong, and is excluded from saved state.
 */
class PresenceFragment : Fragment() {

    private var _binding: FragmentPresenceBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PresenceViewModel by viewModels {
        PresenceViewModelFactory(PresenceRepository(requireContext()))
    }

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

        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.btnPresenceSignIn.setOnClickListener { submitPin() }
        binding.etPresencePin.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitPin()
                true
            } else {
                false
            }
        }
        // A refusal belongs to the attempt that earned it, not the next one being typed.
        binding.etPresencePin.doAfterTextChanged { if (!it.isNullOrEmpty()) binding.tilPresencePin.error = null }
        binding.btnPresenceSignOut.setOnClickListener { viewModel.signOut() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.events.collect(::showEvent) }
                // Keeps the "lapses in m:ss" line counting down between reads.
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

    private fun submitPin() {
        val pin = binding.etPresencePin.text?.toString().orEmpty()
        binding.etPresencePin.setText("")
        binding.tilPresencePin.error = null
        viewModel.signIn(pin)
    }

    private fun showEvent(event: PresenceViewModel.PresenceEvent) {
        val message = when (event) {
            PresenceViewModel.PresenceEvent.SignedIn -> getString(R.string.presence_signed_in)
            PresenceViewModel.PresenceEvent.SignedOut -> getString(R.string.presence_signed_out)
            PresenceViewModel.PresenceEvent.WrongPin -> {
                // Inline as well as the snackbar: this is the refusal the user
                // most needs to see, right where they typed.
                binding.tilPresencePin.error = getString(R.string.presence_wrong_pin)
                getString(R.string.presence_wrong_pin)
            }
            is PresenceViewModel.PresenceEvent.LockedOut -> event.retryAfterSeconds
                ?.let { getString(R.string.presence_locked_out_seconds, it) }
                ?: getString(R.string.presence_locked_out)
            PresenceViewModel.PresenceEvent.NotConfigured -> getString(R.string.presence_not_configured)
            PresenceViewModel.PresenceEvent.Unreachable ->
                getString(R.string.presence_unreachable, DashboardSettings(requireContext()).getHost())
            is PresenceViewModel.PresenceEvent.Failed ->
                getString(R.string.presence_failed, event.httpCode)
        }
        // Room for the whole sentence: the unreachable message's point ("nothing
        // will be sent later") is its second half, which two lines cut off.
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setTextMaxLines(SNACKBAR_MAX_LINES)
            .show()
    }

    private fun render(state: PresenceViewModel.PresenceState) {
        val binding = _binding ?: return
        binding.swipeRefresh.isRefreshing = state.loading

        val session = state.session
        binding.btnPresenceSignIn.isEnabled = !state.busy
        binding.btnPresenceSignOut.isEnabled = !state.busy
        // Sign-out shows whenever a session might be open — including "unknown",
        // so an unreachable read doesn't hide the way to try closing it.
        binding.btnPresenceSignOut.isVisible = session !is PresenceViewModel.Session.Closed

        when (session) {
            is PresenceViewModel.Session.Open -> {
                binding.tvPresenceStatus.text = session.person
                    ?.let { getString(R.string.presence_open_as, it) }
                    ?: getString(R.string.presence_open)
                binding.tvPresenceDetail.isVisible = session.expiresAt != null
                session.expiresAt?.let { expiresAt ->
                    val left = ((expiresAt - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
                    binding.tvPresenceDetail.text =
                        getString(R.string.presence_lapses_in, left / 60, left % 60)
                }
            }
            PresenceViewModel.Session.Closed -> {
                binding.tvPresenceStatus.text = getString(R.string.presence_closed)
                binding.tvPresenceDetail.isVisible = false
            }
            PresenceViewModel.Session.Unknown -> {
                binding.tvPresenceStatus.text = getString(
                    if (state.unreachable) R.string.presence_status_unreachable else R.string.presence_checking
                )
                binding.tvPresenceDetail.isVisible = state.unreachable
                binding.tvPresenceDetail.text =
                    getString(R.string.presence_unreachable_detail, DashboardSettings(requireContext()).getHost())
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val SNACKBAR_MAX_LINES = 4
    }
}
