package com.personalmorningalarm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.personalmorningalarm.R
import com.personalmorningalarm.data.model.Inbox
import com.personalmorningalarm.data.model.InboxCaptureDto
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.InboxSync
import com.personalmorningalarm.databinding.FragmentInboxBinding
import kotlinx.coroutines.launch

/**
 * The Inbox tile: the vault's current `0-inbox/` captures, and a box to add another.
 *
 * Read-only above the compose row — no tick, no drop, no tap target on a capture.
 * Inbox items are resolved by a vault triage session, never from the phone, so the
 * screen deliberately offers no way to clear one. Captures are accepted anywhere;
 * offline they queue through [InboxSync] and show with a pending marker.
 */
class InboxFragment : Fragment() {

    private var _binding: FragmentInboxBinding? = null
    private val binding get() = _binding!!

    private val viewModel: InboxViewModel by viewModels {
        InboxViewModelFactory(
            AlfredRepository(requireContext()),
            InboxSync.getInstance(requireContext())
        )
    }

    private val captureAdapter by lazy { InboxCaptureAdapter() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInboxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindScreenTitle(binding.titleBar, R.string.inbox_title)

        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.rvInboxCaptures.layoutManager = LinearLayoutManager(requireContext())
        binding.rvInboxCaptures.adapter = captureAdapter

        binding.btnInboxRefusedDismiss.setOnClickListener { viewModel.dismissFailed() }
        binding.btnInboxCapture.setOnClickListener { submitCapture() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.events.collect(::showEvent) }
            }
        }
    }

    private fun submitCapture() {
        val text = binding.etInboxCapture.text?.toString().orEmpty()
        if (text.isBlank()) return
        viewModel.capture(text)
        binding.etInboxCapture.setText("")
    }

    private fun showEvent(event: InboxViewModel.InboxEvent) {
        val message = when (event) {
            InboxViewModel.InboxEvent.CAPTURED -> R.string.inbox_captured
            InboxViewModel.InboxEvent.SAVED_OFFLINE -> R.string.chalkboard_saved_offline
            InboxViewModel.InboxEvent.REFUSED -> R.string.inbox_capture_refused
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun render(state: InboxViewModel.InboxState) {
        binding.swipeRefresh.isRefreshing = state.loading

        val queued = state.pending.filterNot { it.failed }
        val refused = state.pending.filter { it.failed }

        // The sync line: a saved copy, and/or captures not in the vault yet.
        val sync = listOfNotNull(
            getString(R.string.schedule_stale).takeIf { state.result is AlfredResult.Stale },
            resources.getQuantityString(R.plurals.inbox_pending_count, queued.size, queued.size)
                .takeIf { queued.isNotEmpty() }
        )
        binding.syncLine.root.isVisible = sync.isNotEmpty()
        binding.syncLine.tvSyncText.text = sync.joinToString(" · ")
        binding.rowInboxRefused.isVisible = refused.isNotEmpty()
        if (refused.isNotEmpty()) {
            binding.tvInboxRefused.text = resources.getQuantityString(
                R.plurals.inbox_refused_notice, refused.size, refused.size
            ) + refused.joinToString(separator = "") { "\n• ${it.text.lineSequence().first()}" }
        }

        binding.etInboxCapture.hint = getString(
            if (state.result is AlfredResult.Stale || state.result is AlfredResult.Unavailable) {
                R.string.inbox_offline_hint
            } else {
                R.string.inbox_capture_hint
            }
        )

        val base: List<InboxCaptureDto>? = when (val result = state.result) {
            null -> null
            is AlfredResult.Fresh -> result.data
            is AlfredResult.Stale -> result.data
            AlfredResult.Unavailable -> null
        }

        // No snapshot AND nothing queued: only then is there truly nothing to list.
        if (base == null && queued.isEmpty()) {
            binding.tvInboxBody.isVisible = true
            binding.rvInboxCaptures.isVisible = false
            binding.tvInboxBody.text =
                getString(if (state.result == null) R.string.shopping_menu_loading else R.string.inbox_unavailable)
            binding.titleBar.tvScreenMeta.isVisible = false
            captureAdapter.submitList(emptyList())
            return
        }

        val captures = Inbox.merged(base.orEmpty(), state.pending)
        binding.rvInboxCaptures.isVisible = captures.isNotEmpty()
        binding.tvInboxBody.isVisible = captures.isEmpty()
        if (captures.isEmpty()) {
            binding.tvInboxBody.text = getString(R.string.inbox_empty)
        }
        // How many captures are waiting for triage, on the right of the title.
        binding.titleBar.tvScreenMeta.isVisible = true
        binding.titleBar.tvScreenMeta.text = captures.size.toString()
        captureAdapter.submitList(captures)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvInboxCaptures.adapter = null
        _binding = null
    }
}
