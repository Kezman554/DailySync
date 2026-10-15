package com.personalmorningalarm.ui

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.personalmorningalarm.R
import com.personalmorningalarm.challenge.CheckpointResult
import com.personalmorningalarm.challenge.ContentScreenManager
import com.personalmorningalarm.challenge.NfcCheckpointManager
import com.personalmorningalarm.challenge.ShakeChallenge
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.entity.AlarmEvent
import com.personalmorningalarm.data.entity.StretchExercise
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.ContentType
import com.personalmorningalarm.data.model.FamilyCalendar
import com.personalmorningalarm.data.model.FamilyEvent
import com.personalmorningalarm.data.model.MorningGoal
import com.personalmorningalarm.data.model.RollingTodo
import com.personalmorningalarm.data.model.ScheduleTaskDto
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.databinding.ActivityAlarmDismissalBinding
import com.personalmorningalarm.service.AlarmService
import com.personalmorningalarm.service.CountdownService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.ceil

/**
 * Stage 1 (shake) and Stage 2 (NFC checkpoint sequence). Shows over the lock
 * screen, blocks back. Stage 1: shake to fill a bar. Stage 2: tap the registered
 * NFC tags in a randomised order while the countdown runs; if none are
 * registered, a single "tap to confirm" stands in.
 */
class AlarmDismissalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmDismissalBinding
    private lateinit var shakeChallenge: ShakeChallenge
    private lateinit var repository: AlarmRepository
    private val alfredRepository: AlfredRepository by lazy { AlfredRepository(this) }

    private var vibrator: Vibrator? = null
    private val dismissRunnable = Runnable { dismissToHome() }

    // When Stage 2's countdown expires, CountdownService starts the nuclear alarm
    // and broadcasts this; finish so we don't linger behind the nuclear screen.
    private val countdownExpiredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Countdown expired — nuclear took over, finishing Stage 2")
            finish()
        }
    }

    private var nfcAdapter: NfcAdapter? = null
    private var checkpointManager: NfcCheckpointManager? = null
    private var contentScreenManager: ContentScreenManager? = null
    private var gapsShown = 0
    private var showingContent = false
    private var stretchTimer: CountDownTimer? = null
    private var contentDismissTimer: CountDownTimer? = null
    private var showingUnlockHint = false

    private val keyguardManager: KeyguardManager by lazy {
        getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }

    // Reader mode (not foreground dispatch): reliable for consecutive reads on
    // this Samsung device, where the system NFC UI stole focus after the first
    // tag and killed dispatch. Callback runs on a binder thread.
    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        val tagId = tag.id.toHex()
        runOnUiThread { if (stage == STAGE_2) onCheckpointTag(tagId) }
    }

    private var stage = STAGE_1
    private var lastLoggedBucket = -1
    private var stage2StartMs = 0L

    private var stage2DurationMinutes = DEFAULT_STAGE2_MINUTES
    private var stretchDurationMinutes = DEFAULT_STRETCH_MINUTES
    private var sequenceLength = DEFAULT_SEQUENCE_LENGTH
    private var morningGoal = MorningGoal.EXERCISE

    // Active stretch routine's exercises, stepped through on the stretch screen.
    private var stretchExercises: List<StretchExercise> = emptyList()
    private var stretchIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showWhenLockedAndTurnScreenOn()

        isSessionActive = true
        // Hardware volume buttons adjust the alarm stream while the alarm sounds, so
        // the user can turn Stage 1 down while shaking. AlarmService resets the stream
        // to the app-set level on the next fire.
        volumeControlStream = AudioManager.STREAM_ALARM
        binding = ActivityAlarmDismissalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AlarmRepository(AppDatabase.getInstance(this))
        vibrator = resolveVibrator()
        setupNfc()

        ContextCompat.registerReceiver(
            this,
            countdownExpiredReceiver,
            IntentFilter(CountdownService.ACTION_COUNTDOWN_EXPIRED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Swallowed — the alarm can't be escaped with the back button.
            }
        })

        shakeChallenge = ShakeChallenge(this).apply {
            onProgress = { progress -> onShakeProgress(progress) }
            onComplete = { onShakeComplete() }
        }
        binding.tvSeconds.text = getString(R.string.seconds_format, TARGET_SECONDS)

        binding.btnStopAlarm.setOnClickListener {
            AlarmService.stop(this)
            CountdownService.stop(this)
            finish()
        }

        lifecycleScope.launch {
            repository.getCurrentConfig()?.let { config ->
                stage2DurationMinutes = config.stage2DurationMinutes
                sequenceLength = config.sequenceLength
                morningGoal = config.morningGoal
            }
        }
    }

    override fun onResume() {
        super.onResume()
        when (stage) {
            STAGE_1 -> shakeChallenge.start()
            STAGE_2 -> {
                if (checkpointManager != null) enableNfcDispatch()
                if (showingUnlockHint && !keyguardManager.isKeyguardLocked) {
                    showingUnlockHint = false
                    binding.tvFeedback.visibility = View.GONE
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        shakeChallenge.stop()
        disableNfcDispatch()
    }

    override fun onDestroy() {
        super.onDestroy()
        stretchTimer?.cancel()
        contentDismissTimer?.cancel()
        binding.root.removeCallbacks(dismissRunnable)
        runCatching { unregisterReceiver(countdownExpiredReceiver) }
        isSessionActive = false
    }

    // --- Stage 1 ---

    private fun onShakeProgress(progress: Float) {
        val percent = (progress * 100).toInt()
        binding.shakeProgress.progress = percent

        val remaining = ceil((1f - progress) * TARGET_SECONDS).toInt().coerceAtLeast(0)
        binding.tvSeconds.text = getString(R.string.seconds_format, remaining)

        val bucket = percent / 25
        if (bucket != lastLoggedBucket) {
            lastLoggedBucket = bucket
            Log.d(TAG, "Shake progress: $percent%")
        }
    }

    private fun onShakeComplete() {
        Log.d(TAG, "Shake challenge complete — Stage 1 done")
        stage = STAGE_TRANSITION
        shakeChallenge.stop()

        binding.shakeProgress.progress = 100
        AlarmService.stop(this)
        logStage1Success()

        binding.tvTitle.text = getString(R.string.stage1_success)
        binding.tvSeconds.visibility = View.GONE
        binding.root.postDelayed({ startStage2() }, SUCCESS_DELAY_MS)
    }

    private fun logStage1Success() {
        lifecycleScope.launch {
            val today = LocalDate.now().toString()
            val existing = repository.getEventByDate(today)
            if (existing != null) {
                repository.updateEvent(existing.copy(stage1Success = true))
            } else {
                repository.recordEvent(
                    AlarmEvent(date = today, stage1Success = true, morningGoal = morningGoal)
                )
            }
            Log.d(TAG, "Stage 1 success logged for $today")
        }
    }

    // --- Stage 2 ---

    private fun startStage2() {
        stage = STAGE_2
        stage2StartMs = SystemClock.elapsedRealtime()

        binding.shakeProgress.visibility = View.GONE
        binding.tvSeconds.visibility = View.GONE
        binding.tvCountdown.visibility = View.VISIBLE

        CountdownService.start(this, stage2DurationMinutes * 60)
        observeCountdown()

        lifecycleScope.launch {
            val tags = repository.getActiveNfcTags()
            val enabledToggles = repository.getEnabledContentToggles()
            contentScreenManager = ContentScreenManager(enabledToggles.map { it.contentType })
            stretchDurationMinutes = enabledToggles
                .firstOrNull { it.contentType == ContentType.STRETCH }
                ?.durationMinutes ?: DEFAULT_STRETCH_MINUTES
            if (tags.isEmpty() || nfcAdapter?.isEnabled != true) {
                Log.d(TAG, "Stage 2: no usable NFC tags — falling back to confirm button")
                setupFallbackConfirm()
            } else {
                checkpointManager = NfcCheckpointManager(tags, sequenceLength)
                Log.d(TAG, "Stage 2: ${tags.size} tags, sequence length $sequenceLength, " +
                    "tap order randomised; content=${enabledToggles.map { it.contentType }}, " +
                    "stretch=${stretchDurationMinutes}m")
                enableNfcDispatch()
                showCheckpoint()
                promptUnlockIfNeeded()
            }
        }
    }

    private fun showCheckpoint() {
        val manager = checkpointManager ?: return
        binding.tvCheckpoint.visibility = View.VISIBLE
        binding.tvCheckpoint.text =
            getString(R.string.checkpoint_progress, manager.currentNumber, manager.total)
        binding.tvTitle.text = getString(R.string.stage2_go_to, manager.currentTag?.label.orEmpty())
        binding.tvFeedback.visibility = View.GONE
    }

    private fun onCheckpointTag(tagId: String) {
        if (showingContent) return // ignore taps while a content screen is up
        when (val result = checkpointManager?.onTagScanned(tagId)) {
            is CheckpointResult.Correct -> {
                Log.d(TAG, "Checkpoint correct — next: ${result.next.label}")
                val content = contentScreenManager?.contentForGap(gapsShown)
                gapsShown++
                if (content != null) showContentScreen(content) else showCheckpoint()
            }
            is CheckpointResult.Wrong -> {
                Log.d(TAG, "Wrong tag — still need: ${result.expected.label}")
                binding.tvFeedback.visibility = View.VISIBLE
                binding.tvFeedback.text = getString(R.string.wrong_tag, result.expected.label)
                vibrator?.vibrate(VibrationEffect.createOneShot(WRONG_PULSE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            CheckpointResult.SequenceComplete -> {
                Log.d(TAG, "All checkpoints tapped")
                onStage2Complete()
            }
            else -> { /* AlreadyComplete / null — ignore */ }
        }
    }

    private fun setupFallbackConfirm() {
        binding.tvCheckpoint.visibility = View.GONE
        binding.tvFeedback.visibility = View.GONE
        binding.tvTitle.text = getString(R.string.stage2_title)
        binding.btnConfirm.visibility = View.VISIBLE
        binding.btnConfirm.setOnClickListener { onStage2Complete() }
    }

    // --- Content screens (between checkpoints) ---

    private fun showContentScreen(type: ContentType) {
        showingContent = true
        // Freeze the Stage 2 deadline while content shows — content time shouldn't
        // count against the nuclear timer (a 10-min stretch must not fail you).
        CountdownService.pause(this)
        binding.content.visibility = View.GONE
        binding.contentPanel.visibility = View.VISIBLE
        binding.contentAuthor.visibility = View.GONE
        binding.contentNote.visibility = View.GONE
        binding.contentTimer.visibility = View.GONE
        binding.tvStretchProgress.visibility = View.GONE
        binding.contentListScroll.visibility = View.GONE
        binding.contentBody.visibility = View.VISIBLE
        stretchTimer?.cancel()
        // Reset the Continue control: enabled by default (the quote screen locks it).
        contentDismissTimer?.cancel()
        binding.tvContinueCountdown.visibility = View.GONE
        binding.btnContinue.animate().cancel()
        binding.btnContinue.scaleX = 1f
        binding.btnContinue.scaleY = 1f
        setContinueEnabled(true)
        binding.btnContinue.setOnClickListener { onContentContinue() }

        when (type) {
            ContentType.QUOTE -> showQuoteContent()
            ContentType.STRETCH -> showStretchContent()
            ContentType.DAILY_SCHEDULE -> showDailyScheduleContent()
            ContentType.CHALKBOARD -> showChalkboardContent()
        }
        Log.d(TAG, "Content screen shown: $type")
    }

    private fun showQuoteContent() {
        binding.contentHeading.text = getString(R.string.content_quote_heading)
        lifecycleScope.launch {
            val quote = repository.getRandomQuote()
            binding.contentBody.text = quote?.quoteText.orEmpty()
            val author = quote?.author
            if (!author.isNullOrBlank()) {
                binding.contentAuthor.visibility = View.VISIBLE
                binding.contentAuthor.text = getString(R.string.quote_author_format, author)
            }
        }
        startContentDismissLock() // make the user sit with the quote before continuing
    }

    /**
     * Locks the Continue button for [CONTENT_DISMISS_LOCK_MS], counting down next to
     * it, then re-enables it with a pop. Generic so other content screens can reuse
     * it; the stretch screen has its own timer and doesn't call this.
     */
    private fun startContentDismissLock() {
        setContinueEnabled(false)
        binding.tvContinueCountdown.visibility = View.VISIBLE
        contentDismissTimer = object : CountDownTimer(CONTENT_DISMISS_LOCK_MS, 1000L) {
            override fun onTick(msLeft: Long) {
                val secs = ceil(msLeft / 1000.0).toInt()
                binding.tvContinueCountdown.text = getString(R.string.content_dismiss_seconds, secs)
            }

            override fun onFinish() {
                binding.tvContinueCountdown.visibility = View.GONE
                setContinueEnabled(true)
                // Visual cue that it's now tappable: a brief scale pop.
                binding.btnContinue.animate()
                    .scaleX(1.1f).scaleY(1.1f).setDuration(150L)
                    .withEndAction {
                        binding.btnContinue.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
                    }
                    .start()
            }
        }.start()
    }

    /** Enables/disables Continue and greys it out while disabled. */
    private fun setContinueEnabled(enabled: Boolean) {
        binding.btnContinue.isEnabled = enabled
        binding.btnContinue.alpha = if (enabled) 1f else 0.4f
    }

    private fun showStretchContent() {
        binding.contentHeading.text = getString(R.string.content_stretch_heading)
        binding.contentTimer.visibility = View.VISIBLE
        // Load the active routine for this morning, then step through its exercises.
        lifecycleScope.launch {
            val routine = repository.getStretchRoutineForGoal(morningGoal)
            stretchExercises = routine?.let { repository.getExercisesForRoutine(it.id) } ?: emptyList()
            stretchIndex = 0
            if (stretchExercises.isEmpty()) {
                Log.d(TAG, "Stretch: no routine/exercises — falling back to suggestions")
                showStretchFallback()
            } else {
                Log.d(TAG, "Stretch routine '${routine?.name}', ${stretchExercises.size} exercises")
                showCurrentStretch()
            }
        }
    }

    /** Shows the current stretch (name, instructions, per-stretch countdown). */
    private fun showCurrentStretch() {
        val exercise = stretchExercises[stretchIndex]
        binding.contentHeading.text = exercise.name
        binding.tvStretchProgress.visibility = View.VISIBLE
        binding.tvStretchProgress.text =
            getString(R.string.stretch_progress, stretchIndex + 1, stretchExercises.size)
        binding.contentBody.text = exercise.instructions
        binding.contentTimer.visibility = View.VISIBLE
        stretchTimer?.cancel()
        stretchTimer = object : CountDownTimer(exercise.durationSeconds * 1000L, 1000L) {
            override fun onTick(msLeft: Long) {
                binding.contentTimer.text = CountdownService.formatTime((msLeft / 1000L).toInt())
            }
            override fun onFinish() = advanceStretch()
        }.start()
    }

    /** Moves to the next stretch, or ends the stretch screen after the last one. */
    private fun advanceStretch() {
        stretchIndex++
        if (stretchIndex < stretchExercises.size) showCurrentStretch()
        else onContentContinue()
    }

    /** Fallback when no routine is configured: the old single-timer suggestion list. */
    private fun showStretchFallback() {
        binding.contentHeading.text = getString(R.string.content_stretch_heading)
        binding.contentBody.text = getString(R.string.stretch_suggestions)
        binding.contentTimer.visibility = View.VISIBLE
        val stretchMs = stretchDurationMinutes * 60 * 1000L
        stretchTimer = object : CountDownTimer(stretchMs, 1000L) {
            override fun onTick(msLeft: Long) {
                binding.contentTimer.text = CountdownService.formatTime((msLeft / 1000L).toInt())
            }
            override fun onFinish() = onContentContinue() // auto-advance when timer ends
        }.start()
    }

    /**
     * Today's schedule from Alfred. The dismiss lock starts now rather than when
     * Alfred answers: a slow or absent Alfred must not lengthen the morning, and
     * the fetch can't fail in a way that strands this screen — the worst case is
     * "Schedule unavailable" and Continue frees up on schedule either way.
     */
    private fun showDailyScheduleContent() {
        binding.contentHeading.text = getString(R.string.content_schedule_heading)
        binding.contentBody.text = getString(R.string.schedule_loading)
        startContentDismissLock()

        // The gap this screen belongs to. If Alfred answers after the user has moved
        // on, the answer belongs to a screen that's gone — drop it rather than paint
        // it over whatever is up now.
        val gap = gapsShown
        lifecycleScope.launch {
            val result = alfredRepository.getDailySchedule()
            if (!showingContent || gapsShown != gap) {
                Log.d(TAG, "Daily schedule arrived after the screen moved on — dropped")
                return@launch
            }
            renderSchedule(result)
            layerFamilyCalendar(result, gap)
        }
    }

    /**
     * Adds today's family calendar to the schedule already on screen. Deliberately
     * a second pass: the vault plan is rendered before this runs and stands on its
     * own, so a calendar that is slow, unreachable or malformed simply doesn't
     * appear. The wake screen must render either way.
     */
    private suspend fun layerFamilyCalendar(
        schedule: AlfredResult<List<ScheduleTaskDto>>,
        gap: Int
    ) {
        val events = try {
            val result = alfredRepository.getCalendarDay(LocalDate.now())
            FamilyCalendar.byDate(
                when (result) {
                    is AlfredResult.Fresh -> result.data
                    is AlfredResult.Stale -> result.data
                    AlfredResult.Unavailable -> null
                }
            )[LocalDate.now()].orEmpty()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            // Belt and braces: the repository doesn't throw, and this screen must
            // not become the first place that matters.
            Log.w(TAG, "Family calendar failed — showing the vault schedule alone", e)
            emptyList()
        }
        if (events.isEmpty()) {
            // Logged explicitly: "nothing on today" and "the calendar broke" look
            // identical on screen, and only the log can tell them apart afterwards.
            Log.d(TAG, "Family calendar: nothing on today — vault schedule shown alone")
            return
        }
        if (!showingContent || gapsShown != gap) {
            Log.d(TAG, "Family calendar arrived after the screen moved on — dropped")
            return
        }
        renderSchedule(schedule, events)
        Log.d(TAG, "Family calendar: ${events.size} events layered onto today's schedule")
    }

    private fun renderSchedule(
        result: AlfredResult<List<ScheduleTaskDto>>,
        events: List<FamilyEvent> = emptyList()
    ) {
        if (result is AlfredResult.Stale) {
            binding.contentNote.visibility = View.VISIBLE
            binding.contentNote.text = getString(R.string.schedule_stale)
        }

        val tasks = when (result) {
            is AlfredResult.Fresh -> result.data
            is AlfredResult.Stale -> result.data
            AlfredResult.Unavailable -> {
                Log.d(TAG, "Daily schedule: Alfred unreachable and nothing cached")
                // The calendar can still carry the morning on its own.
                if (events.isEmpty()) {
                    binding.contentBody.text = getString(R.string.schedule_unavailable)
                    return
                }
                emptyList()
            }
        }

        val agenda = FamilyCalendar.agenda(tasks, events)
        if (agenda.isEmpty) {
            binding.contentBody.text = getString(R.string.schedule_empty)
            return
        }
        binding.contentBody.visibility = View.GONE
        binding.contentListScroll.visibility = View.VISIBLE
        binding.tvContentList.text = ScheduleRenderer.format(
            this,
            agenda,
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        )
        Log.d(TAG, "Daily schedule: ${agenda.groups.size} groups, ${tasks.size} tasks (stale=${result is AlfredResult.Stale})")
    }

    /**
     * The rolling to-do from Alfred. Same shape as the schedule screen: the dismiss
     * lock starts now, not when Alfred answers, so a slow or absent Alfred can't
     * lengthen the morning or strand the screen.
     */
    private fun showChalkboardContent() {
        binding.contentHeading.text = getString(R.string.content_chalkboard_heading)
        binding.contentBody.text = getString(R.string.chalkboard_loading)
        startContentDismissLock()

        val gap = gapsShown
        lifecycleScope.launch {
            val result = alfredRepository.getChalkboard()
            if (!showingContent || gapsShown != gap) {
                Log.d(TAG, "Chalkboard arrived after the screen moved on — dropped")
                return@launch
            }
            renderChalkboard(result)
        }
    }

    private fun renderChalkboard(result: AlfredResult<List<ChalkboardTaskDto>>) {
        if (result is AlfredResult.Stale) {
            binding.contentNote.visibility = View.VISIBLE
            binding.contentNote.text = getString(R.string.chalkboard_stale)
        }

        val tasks = when (result) {
            is AlfredResult.Fresh -> result.data
            is AlfredResult.Stale -> result.data
            AlfredResult.Unavailable -> {
                Log.d(TAG, "Chalkboard: Alfred unreachable and nothing cached")
                binding.contentBody.text = getString(R.string.chalkboard_unavailable)
                return
            }
        }

        val items = RollingTodo.items(tasks)
        if (items.isEmpty()) {
            binding.contentBody.text = getString(R.string.chalkboard_empty)
            return
        }
        binding.contentBody.visibility = View.GONE
        binding.contentListScroll.visibility = View.VISIBLE
        binding.tvContentList.text = ChalkboardRenderer.format(
            items,
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        Log.d(TAG, "Chalkboard: ${items.size} items (stale=${result is AlfredResult.Stale})")
    }

    private fun onContentContinue() {
        stretchTimer?.cancel()
        stretchTimer = null
        contentDismissTimer?.cancel()
        contentDismissTimer = null
        showingContent = false
        binding.contentPanel.visibility = View.GONE
        binding.content.visibility = View.VISIBLE
        CountdownService.resume(this) // restart the Stage 2 deadline
        showCheckpoint()
    }

    private fun onStage2Complete() {
        val seconds = ((SystemClock.elapsedRealtime() - stage2StartMs) / 1000L).toInt()
        stage = STAGE_DONE
        // Keep reader mode engaged (taps now ignored, since stage != STAGE_2) until
        // the activity pauses — otherwise a lingering chip gets grabbed by the
        // system's own NFC popup. onPause/onDestroy disables it.
        CountdownService.stop(this)
        clearAlarmNotifications()

        // Base success screen; the streak line fills in once it's computed.
        binding.tvCountdown.visibility = View.GONE
        binding.tvFeedback.visibility = View.GONE
        binding.btnConfirm.visibility = View.GONE
        binding.tvCheckpoint.visibility = View.GONE
        binding.tvTitle.text = getString(R.string.success_title)

        lifecycleScope.launch {
            recordStage2Success(seconds)
            val streak = repository.getCurrentStreak()
            binding.tvSeconds.visibility = View.VISIBLE
            binding.tvSeconds.text = getString(R.string.streak_format, streak)
            binding.tvFeedback.visibility = View.VISIBLE
            binding.tvFeedback.text = goalReminder()
            Log.d(TAG, "Stage 2 success — streak now $streak")
        }

        // Auto-dismiss after 5s. Tap-to-dismiss only activates after a short grace
        // period so a stray touch right after the final tag doesn't skip the screen.
        binding.root.postDelayed(dismissRunnable, SUCCESS_SCREEN_MS)
        binding.root.postDelayed({
            binding.root.setOnClickListener {
                binding.root.removeCallbacks(dismissRunnable)
                dismissToHome()
            }
        }, TAP_GRACE_MS)
    }

    private suspend fun recordStage2Success(seconds: Int) {
        val today = LocalDate.now().toString()
        val existing = repository.getEventByDate(today)
        if (existing != null) {
            repository.updateEvent(
                existing.copy(stage2Success = true, stage2TimeSeconds = seconds)
            )
        } else {
            repository.recordEvent(
                AlarmEvent(
                    date = today,
                    stage1Success = true,
                    stage2Success = true,
                    stage2TimeSeconds = seconds,
                    morningGoal = morningGoal
                )
            )
        }
        Log.d(TAG, "Stage 2 success logged ($seconds s)")
    }

    private fun goalReminder(): String = when (morningGoal) {
        MorningGoal.EXERCISE -> getString(R.string.goal_reminder_exercise)
        MorningGoal.PROJECT -> getString(R.string.goal_reminder_project)
    }

    private fun clearAlarmNotifications() {
        getSystemService(NotificationManager::class.java).cancelAll()
    }

    private fun dismissToHome() {
        if (!isFinishing) finish()
    }

    private fun observeCountdown() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CountdownService.remainingSeconds.collect { seconds ->
                    binding.tvCountdown.text = CountdownService.formatTime(seconds)
                }
            }
        }
    }

    // --- NFC ---

    private fun setupNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    }

    /**
     * NFC won't deliver taps to the app while the secure keyguard is up, so if
     * Stage 2 starts locked we prompt the user to unlock and show a hint.
     */
    private fun promptUnlockIfNeeded() {
        if (keyguardManager.isKeyguardLocked) {
            showingUnlockHint = true
            binding.tvFeedback.visibility = View.VISIBLE
            binding.tvFeedback.text = getString(R.string.nfc_unlock_hint)
            keyguardManager.requestDismissKeyguard(this, null)
        }
    }

    private fun enableNfcDispatch() {
        runCatching { nfcAdapter?.enableReaderMode(this, readerCallback, READER_FLAGS, null) }
    }

    private fun disableNfcDispatch() {
        runCatching { nfcAdapter?.disableReaderMode(this) }
    }

    // --- Window flags / helpers ---

    private fun showWhenLockedAndTurnScreenOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    companion object {
        private const val TAG = "PMA"

        /** True while a wake/dismissal session is on screen, so a re-fired alarm is ignored. */
        @Volatile
        var isSessionActive = false
            private set

        private const val STAGE_1 = 1
        private const val STAGE_TRANSITION = 2
        private const val STAGE_2 = 3
        private const val STAGE_DONE = 4

        private const val TARGET_SECONDS =
            (ShakeChallenge.DEFAULT_TARGET_DURATION_MS / 1000).toInt()
        private const val DEFAULT_STAGE2_MINUTES = 10
        private const val DEFAULT_STRETCH_MINUTES = 5
        private const val DEFAULT_SEQUENCE_LENGTH = 5

        /** How long a content screen locks its Continue button (quote, and future
         *  fixed-duration screens). Stretch is excluded — it uses its own duration. */
        private const val CONTENT_DISMISS_LOCK_MS = 15_000L
        private const val SUCCESS_DELAY_MS = 1200L
        private const val SUCCESS_SCREEN_MS = 5000L
        private const val TAP_GRACE_MS = 1500L
        private const val WRONG_PULSE_MS = 150L

        private const val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
    }
}
