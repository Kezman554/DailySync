package com.personalmorningalarm.ui

import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.text.inSpans
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.google.android.material.color.MaterialColors
import com.personalmorningalarm.R
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.entity.AlarmConfig
import com.personalmorningalarm.data.remote.ChalkboardSync
import com.personalmorningalarm.data.remote.InboxSync
import com.personalmorningalarm.data.remote.ShoppingSync
import com.personalmorningalarm.databinding.ActivityMainBinding
import com.personalmorningalarm.util.AlarmScheduler
import com.personalmorningalarm.util.BatteryOptimisationHelper
import com.personalmorningalarm.util.NotificationPermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    /** Latest saved alarm, for the Home header's status line. */
    private var alarmConfig: AlarmConfig? = null

    /** Guards against stacking a second dialog when onStart runs again. */
    private var notificationDialogShown = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "POST_NOTIFICATIONS granted=$granted")
            // Denied means the alarm can sound with no way to dismiss it, so say so
            // now rather than letting it surface at 6am.
            if (!granted) showNotificationsBlockedDialog()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(binding.navHostFragment.id) as NavHostFragment
        navController = navHostFragment.navController
        setUpHeader()

        rearmAlarmIfEnabled()

        // Once, on first launch, nudge the user to whitelist the app so alarms fire
        // reliably. savedInstanceState guards against re-showing on recreation
        // (e.g. when a theme change recreates the activity).
        if (savedInstanceState == null && BatteryOptimisationHelper.shouldPromptOnLaunch(this)) {
            BatteryOptimisationHelper.markPrompted(this)
            BatteryOptimisationHelper.showExplanationDialog(this)
        }
    }

    /**
     * The shared Home / Alarm header. The pills switch top-level screens the way
     * the old bottom bar did (single top, each tab's state saved and restored,
     * back from Alarm lands on Home); the cog opens Settings, which brings its
     * own back arrow. Everywhere else the header is hidden.
     */
    private fun setUpHeader() {
        binding.pillHome.setOnClickListener { openTopLevel(R.id.tilesFragment) }
        binding.pillAlarm.setOnClickListener { openTopLevel(R.id.homeFragment) }
        binding.btnSettings.setOnClickListener { navController.navigate(R.id.settingsFragment) }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val id = destination.id
            binding.header.isVisible = id == R.id.tilesFragment || id == R.id.homeFragment
            binding.pillHome.isSelected = id == R.id.tilesFragment
            binding.pillAlarm.isSelected = id == R.id.homeFragment
            binding.tvHeaderAlarm.isVisible = id == R.id.tilesFragment
            renderHeaderDate()
            renderHeaderAlarm()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AlarmRepository(AppDatabase.getInstance(applicationContext))
                    .observeCurrentConfig()
                    .collect { config ->
                        alarmConfig = config
                        renderHeaderAlarm()
                    }
            }
        }
    }

    private fun openTopLevel(destinationId: Int) {
        if (navController.currentDestination?.id == destinationId) return
        navController.navigate(destinationId, null, navOptions {
            launchSingleTop = true
            restoreState = true
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        })
    }

    /** "Thursday 15 Oct". Refreshed on every start and screen change, so it rolls over. */
    private fun renderHeaderDate() {
        binding.tvHeaderDate.text = LocalDate.now().format(HEADER_DATE_FORMAT)
    }

    /**
     * Home's status line under the date: a positive dot and "Alarm 06:30
     * tomorrow" when armed, "Alarm off" in secondary text when not. Re-rendered on
     * every config change, start and screen change, so "today" rolls to
     * "tomorrow" once the time passes.
     */
    private fun renderHeaderAlarm() {
        val next = HomeTiles.nextAlarm(alarmConfig, LocalDateTime.now())
        val view = binding.tvHeaderAlarm
        if (next == null) {
            view.text = getString(R.string.tiles_alarm_off)
            view.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            return
        }
        val line = getString(
            R.string.tiles_alarm_on,
            next.time.format(ALARM_TIME_FORMAT),
            getString(if (next.tomorrow) R.string.tiles_alarm_tomorrow else R.string.tiles_alarm_today)
        )
        view.setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
        view.text = SpannableStringBuilder()
            .inSpans(ForegroundColorSpan(color(R.attr.colorPositive))) { append("\u25CF") }
            .append("  ")
            .append(line)
    }

    private fun color(attr: Int): Int = MaterialColors.getColor(binding.root, attr)

    /**
     * Flushes queued offline to-do/shopping writes when a network comes up while the
     * app is in the foreground — the "walked back into home WiFi with the app open"
     * case. Registered onStart, gone onStop: event-driven only, no background
     * polling — this is capture, not live sync, and battery beats seconds.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            flushChalkboardQueue("network available")
            flushShoppingQueue("network available")
            flushInboxQueue("network available")
        }
    }

    override fun onStart() {
        super.onStart()
        renderHeaderDate()
        renderHeaderAlarm()

        // Re-checked on every launch, not once: the grant can vanish under the app
        // (a reinstall resets it, and the user can revoke it in system settings),
        // and the failure is silent — the alarm sounds with no dismissal screen.
        ensureNotificationsEnabled()

        // App-foreground flush trigger, plus the network-change trigger for as
        // long as we stay foregrounded.
        flushChalkboardQueue("app foreground")
        flushShoppingQueue("app foreground")
        flushInboxQueue("app foreground")
        getSystemService<ConnectivityManager>()?.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStop() {
        super.onStop()
        try {
            getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            // Never registered (getSystemService returned null in onStart) — fine.
        }
    }

    /**
     * Best-effort, quiet: delivery is logged, conflicts are kept as failed queue
     * rows that the Today screen surfaces with the item text — so a flush that
     * happens while the user is elsewhere in the app still tells them later.
     */
    private fun flushChalkboardQueue(trigger: String) {
        val appContext = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sync = ChalkboardSync.getInstance(appContext)
                if (!sync.hasPending()) return@launch
                val outcome = sync.flush()
                Log.d(
                    TAG,
                    "Chalkboard queue flush ($trigger): ${outcome.delivered} delivered, " +
                        "${outcome.conflicted} conflicted, ${outcome.remaining} remaining"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Chalkboard queue flush failed ($trigger)", e)
            }
        }
    }

    /** Same trigger shape as [flushChalkboardQueue], for the shopping-list queue. */
    private fun flushShoppingQueue(trigger: String) {
        val appContext = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sync = ShoppingSync.getInstance(appContext)
                if (!sync.hasPending()) return@launch
                val outcome = sync.flush()
                Log.d(
                    TAG,
                    "Shopping queue flush ($trigger): ${outcome.delivered} delivered, " +
                        "${outcome.conflicted} conflicted, ${outcome.remaining} remaining"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Shopping queue flush failed ($trigger)", e)
            }
        }
    }

    /** Same trigger shape again, for the inbox capture queue. */
    private fun flushInboxQueue(trigger: String) {
        val appContext = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sync = InboxSync.getInstance(appContext)
                if (!sync.hasPending()) return@launch
                val outcome = sync.flush()
                Log.d(
                    TAG,
                    "Inbox queue flush ($trigger): ${outcome.delivered} delivered, " +
                        "${outcome.refused} refused, ${outcome.remaining} remaining"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Inbox queue flush failed ($trigger)", e)
            }
        }
    }

    private fun ensureNotificationsEnabled() {
        if (NotificationPermissionHelper.areNotificationsEnabled(this)) {
            notificationDialogShown = false
            return
        }
        if (NotificationPermissionHelper.shouldRequestPermission(this)) {
            NotificationPermissionHelper.markRequested(this)
            notificationPermissionLauncher.launch(NotificationPermissionHelper.PERMISSION)
        } else {
            // Already asked (or notifications were turned off in settings, where the
            // system dialog would silently no-op) — send them to settings instead.
            showNotificationsBlockedDialog()
        }
    }

    private fun showNotificationsBlockedDialog() {
        if (notificationDialogShown) return
        notificationDialogShown = true
        Log.w(TAG, "Notifications disabled — the alarm cannot show its dismissal screen")
        NotificationPermissionHelper.showBlockedDialog(this)
    }

    /**
     * Self-heals the alarm schedule on every app launch. Exact alarms are one-shot
     * and only re-arm when they fire ([AlarmReceiver]) or after a reboot
     * ([BootReceiver]); a force-stop or a single missed fire silently drops the
     * pending alarm while the saved config still reads "enabled", so the toggle
     * looks on but nothing is scheduled. Re-arming here means simply opening the
     * app guarantees the next occurrence is set. Idempotent: scheduleDailyAlarm
     * uses FLAG_UPDATE_CURRENT, so a re-arm just overwrites any existing alarm.
     */
    private fun rearmAlarmIfEnabled() {
        val appContext = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val config = AlarmRepository(AppDatabase.getInstance(appContext)).getCurrentConfig()
                if (config == null || !config.isEnabled) return@launch
                val scheduler = AlarmScheduler(appContext)
                if (scheduler.canScheduleExactAlarms()) {
                    val ok = scheduler.scheduleDailyAlarm(config.alarmTime)
                    Log.d(TAG, "Re-armed alarm on launch (alarmTime=${config.alarmTime}, ok=$ok)")
                } else {
                    // Permission revoked; HomeFragment surfaces the prompt when shown.
                    Log.w(TAG, "Alarm enabled but exact-alarm permission missing — not re-armed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-arm alarm on launch", e)
            }
        }
    }

    companion object {
        private const val TAG = "PMA"
        private val HEADER_DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault())
        private val ALARM_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
    }
}
