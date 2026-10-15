package com.personalmorningalarm.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.personalmorningalarm.R
import com.personalmorningalarm.data.remote.AlfredSettings
import com.personalmorningalarm.data.remote.DashboardSettings
import com.personalmorningalarm.databinding.DialogAlfredHostBinding
import com.personalmorningalarm.databinding.DialogDashboardHostBinding
import com.personalmorningalarm.databinding.FragmentSettingsBinding
import com.personalmorningalarm.util.BatteryOptimisationHelper
import com.personalmorningalarm.util.NotificationPermissionHelper
import com.personalmorningalarm.util.ThemeManager
import com.personalmorningalarm.util.ThemeMode

/**
 * Settings, behind the header's cog: Appearance, the Alfred and dashboard
 * addresses, and Alarm health. Everything about the alarm itself lives on the
 * Alarm tab's tiles.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var themeManager: ThemeManager
    private lateinit var alfredSettings: AlfredSettings
    private lateinit var dashboardSettings: DashboardSettings

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        themeManager = ThemeManager(requireContext())
        alfredSettings = AlfredSettings(requireContext())
        dashboardSettings = DashboardSettings(requireContext())

        bindScreenTitle(binding.titleBar, R.string.title_settings)

        // Theme: a three-way segmented control. Changing it recreates the activity,
        // so the new theme shows immediately; the segment is marked first so it
        // doesn't flicker back while that happens.
        val themeSegments = mapOf(
            ThemeMode.LIGHT to binding.segThemeLight,
            ThemeMode.DARK to binding.segThemeDark,
            ThemeMode.SYSTEM to binding.segThemeSystem
        )
        fun selectTheme(mode: ThemeMode) =
            themeSegments.forEach { (m, segment) -> segment.isSelected = m == mode }
        selectTheme(themeManager.getThemeMode())
        themeSegments.forEach { (mode, segment) ->
            segment.setOnClickListener {
                if (themeManager.getThemeMode() == mode) return@setOnClickListener
                selectTheme(mode)
                themeManager.setThemeMode(mode)
            }
        }

        binding.btnBattery.setOnClickListener {
            BatteryOptimisationHelper.showExplanationDialog(requireContext())
        }

        binding.btnNotifications.setOnClickListener {
            NotificationPermissionHelper.openNotificationSettings(requireContext())
        }

        binding.rowAlfredHost.setOnClickListener { showAlfredHostDialog() }
        renderAlfredHost()
        binding.rowDashboardHost.setOnClickListener { showDashboardHostDialog() }
        renderDashboardHost()
    }

    /** Shows the saved Alfred address (or the default, which is what we'd use). */
    private fun renderAlfredHost() {
        binding.tvAlfredHost.text = alfredSettings.getHost()
    }

    /**
     * Text dialog for the Alfred address. Saves through [AlfredSettings.setHost],
     * which normalises what was typed, then shows back what we actually stored.
     *
     * Save is wired after show() so an unusable address can report itself inline
     * and keep the dialog open — the default listener would dismiss regardless,
     * and a typo here otherwise wouldn't surface until the alarm goes off.
     */
    private fun showAlfredHostDialog() {
        val dialogBinding = DialogAlfredHostBinding.inflate(layoutInflater)
        dialogBinding.etAlfredHost.setText(alfredSettings.getHost())
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.alfred_host_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val typed = dialogBinding.etAlfredHost.text?.toString().orEmpty()
                if (!AlfredSettings.isValid(typed)) {
                    dialogBinding.tilAlfredHost.error = getString(R.string.alfred_host_invalid)
                    return@setOnClickListener
                }
                alfredSettings.setHost(typed)
                renderAlfredHost()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** Shows the saved dashboard address (or the default, which is what we'd use). */
    private fun renderDashboardHost() {
        binding.tvDashboardHost.text = dashboardSettings.getHost()
    }

    /** The dashboard's twin of [showAlfredHostDialog] — same inline validation, own setting. */
    private fun showDashboardHostDialog() {
        val dialogBinding = DialogDashboardHostBinding.inflate(layoutInflater)
        dialogBinding.etDashboardHost.setText(dashboardSettings.getHost())
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dashboard_host_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val typed = dialogBinding.etDashboardHost.text?.toString().orEmpty()
                if (!DashboardSettings.isValid(typed)) {
                    dialogBinding.tilDashboardHost.error = getString(R.string.dashboard_host_invalid)
                    return@setOnClickListener
                }
                dashboardSettings.setHost(typed)
                renderDashboardHost()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        renderBatteryStatus()
        renderNotificationStatus()
    }

    /** Reflects whether notifications can actually be delivered (refreshed on return). */
    private fun renderNotificationStatus() {
        renderHealthRow(
            ok = NotificationPermissionHelper.areNotificationsEnabled(requireContext()),
            status = binding.tvNotificationsStatus,
            help = binding.tvNotificationsHelp,
            fix = binding.btnNotifications
        )
    }

    /** Reflects the current battery-optimisation state (refreshed on return). */
    private fun renderBatteryStatus() {
        renderHealthRow(
            ok = BatteryOptimisationHelper.isIgnoringBatteryOptimizations(requireContext()),
            status = binding.tvBatteryStatus,
            help = binding.tvBatteryHelp,
            fix = binding.btnBattery
        )
    }

    /**
     * One Alarm health row: "Enabled" in the positive colour when the check
     * passes, otherwise "Restricted" in the text colour with the explanation and
     * the fix action, which only exist while the check fails.
     */
    private fun renderHealthRow(ok: Boolean, status: TextView, help: View, fix: View) {
        status.setText(if (ok) R.string.health_enabled else R.string.health_restricted)
        status.setTextColor(
            MaterialColors.getColor(
                status,
                if (ok) R.attr.colorPositive else com.google.android.material.R.attr.colorOnSurface
            )
        )
        help.isVisible = !ok
        fix.isVisible = !ok
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
