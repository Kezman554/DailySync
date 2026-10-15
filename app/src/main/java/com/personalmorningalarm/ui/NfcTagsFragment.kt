package com.personalmorningalarm.ui

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.personalmorningalarm.R
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.entity.NfcTag
import com.personalmorningalarm.databinding.FragmentNfcTagsBinding
import kotlinx.coroutines.launch

/** Dedicated screen for registering and managing NFC checkpoint tags. */
class NfcTagsFragment : Fragment() {

    private var _binding: FragmentNfcTagsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NfcTagsViewModel by viewModels {
        ViewModelFactory(AlarmRepository(AppDatabase.getInstance(requireContext())))
    }

    private val tagAdapter = NfcTagAdapter(onDelete = { tag -> confirmDelete(tag) })

    private var nfcAdapter: NfcAdapter? = null
    private var registering = false
    private var waitingDialog: AlertDialog? = null

    // Reader-mode callback fires on a binder thread — hop to the UI thread.
    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        val tagId = tag.id.toHex()
        Log.d(TAG, "Reader read tag $tagId (registering=$registering)")
        activity?.runOnUiThread {
            if (!registering) return@runOnUiThread
            stopRegistration()
            promptForLabel(tagId)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNfcTagsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindScreenTitle(binding.titleBar, R.string.nfc_tags_screen_title)

        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())

        binding.rvTags.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTags.adapter = tagAdapter

        binding.btnRegisterTag.setOnClickListener { startRegistration() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.tags.collect { tagAdapter.submitList(it) } }
                launch {
                    viewModel.activeTagCount.collect {
                        binding.tvActiveCount.text = getString(R.string.active_tag_count, it)
                    }
                }
                launch { viewModel.messages.collect { toast(it) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateNfcStatus()
    }

    override fun onPause() {
        super.onPause()
        if (registering) stopRegistration()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        waitingDialog?.dismiss()
        waitingDialog = null
        binding.rvTags.adapter = null
        _binding = null
    }

    private fun updateNfcStatus() {
        val adapter = nfcAdapter
        when {
            adapter == null -> {
                binding.tvNfcStatus.text = getString(R.string.nfc_unavailable)
                binding.btnRegisterTag.isEnabled = false
            }
            !adapter.isEnabled -> {
                binding.tvNfcStatus.text = getString(R.string.nfc_disabled)
                binding.btnRegisterTag.isEnabled = true
            }
            else -> {
                binding.tvNfcStatus.text = getString(R.string.nfc_ready)
                binding.btnRegisterTag.isEnabled = true
            }
        }
    }

    private fun startRegistration() {
        val adapter = nfcAdapter
        when {
            adapter == null -> toast(getString(R.string.nfc_unavailable))
            !adapter.isEnabled -> {
                toast(getString(R.string.nfc_disabled))
                runCatching { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            }
            else -> {
                registering = true
                adapter.enableReaderMode(requireActivity(), readerCallback, READER_FLAGS, null)
                showWaitingDialog()
                Log.d(TAG, "Registration started — reader mode enabled, waiting for a tag")
            }
        }
    }

    private fun stopRegistration() {
        registering = false
        nfcAdapter?.disableReaderMode(requireActivity())
        waitingDialog?.dismiss()
        waitingDialog = null
    }

    private fun showWaitingDialog() {
        waitingDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.register_new_tag)
            .setMessage(R.string.nfc_hold_tag)
            .setNegativeButton(R.string.cancel) { _, _ -> stopRegistration() }
            .setOnCancelListener { stopRegistration() }
            .show()
    }

    private fun promptForLabel(tagId: String) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val labelInput = EditText(requireContext()).apply { hint = getString(R.string.label_hint) }
        val locationInput =
            EditText(requireContext()).apply { hint = getString(R.string.location_hint) }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(labelInput)
            addView(locationInput)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.register_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val label = labelInput.text.toString().trim().ifBlank { getString(R.string.nfc_section_title) }
                val location = locationInput.text.toString().trim()
                viewModel.registerTag(tagId, label, location)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(tag: NfcTag) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_tag_title)
            .setMessage(getString(R.string.delete_tag_message, tag.label))
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteTag(tag) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    companion object {
        private const val TAG = "PMA"

        private const val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
    }
}
