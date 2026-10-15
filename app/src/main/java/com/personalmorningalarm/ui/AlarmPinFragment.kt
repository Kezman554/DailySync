package com.personalmorningalarm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.personalmorningalarm.R
import com.personalmorningalarm.databinding.FragmentAlarmPinBinding
import com.personalmorningalarm.util.PinManager

/**
 * Alarm PIN, from the Alarm tab: what it protects, whether it's on, and set /
 * change / disable. The flows themselves are [PinPrompts].
 */
class AlarmPinFragment : Fragment() {

    private var _binding: FragmentAlarmPinBinding? = null
    private val binding get() = _binding!!

    private lateinit var pinManager: PinManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlarmPinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pinManager = PinManager(requireContext())
        bindScreenTitle(binding.titleBar, R.string.alarm_tab_pin)

        binding.btnSetPin.setOnClickListener {
            PinPrompts.setup(requireContext(), pinManager) { ok ->
                if (ok) {
                    toast(R.string.pin_set_done)
                    render()
                }
            }
        }
        binding.btnChangePin.setOnClickListener {
            PinPrompts.change(requireContext(), pinManager) { ok ->
                if (ok) toast(R.string.pin_changed)
            }
        }
        binding.btnDisablePin.setOnClickListener {
            PinPrompts.disable(requireContext(), pinManager) { ok ->
                if (ok) {
                    toast(R.string.pin_disabled)
                    render()
                }
            }
        }
        render()
    }

    /** Shows Set, or Change + Disable, depending on whether a PIN exists. */
    private fun render() {
        val pinSet = pinManager.isPinSet()
        binding.tvPinStatus.setText(if (pinSet) R.string.pin_status_on else R.string.pin_status_off)
        binding.btnSetPin.isVisible = !pinSet
        binding.btnChangePin.isVisible = pinSet
        binding.btnDisablePin.isVisible = pinSet
    }

    private fun toast(message: Int) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
