package com.personalmorningalarm.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import android.text.format.DateFormat
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.personalmorningalarm.R
import com.personalmorningalarm.challenge.CheckpointResult
import com.personalmorningalarm.challenge.ContentScreenManager
import com.personalmorningalarm.challenge.NfcCheckpointManager
import com.personalmorningalarm.challenge.ShakeChallenge
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.entity.AlarmEvent
import com.personalmorningalarm.data.entity.ChalkboardVerb
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.ContentType
import com.personalmorningalarm.data.model.FamilyCalendar
import com.personalmorningalarm.data.model.FamilyEvent
import com.personalmorningalarm.data.model.MorningGoal
import com.personalmorningalarm.data.model.RollingTodo
import com.personalmorningalarm.data.model.RollingTodoItem
import com.personalmorningalarm.data.model.ScheduleTaskDto
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.data.remote.AlfredRepository
import com.personalmorningalarm.data.remote.AlfredResult
import com.personalmorningalarm.data.remote.AlfredWriteResult
import com.personalmorningalarm.data.remote.ChalkboardSync
import com.personalmorningalarm.databinding.ActivityAlarmDismissalBinding
import com.personalmorningalarm.databinding.ItemAlarmStretchRowBinding
import com.personalmorningalarm.databinding.ItemAlarmTodoRowBinding
import com.personalmorningalarm.service.AlarmService
import com.personalmorningalarm.service.CountdownService
import com.personalmorningalarm.util.MarkdownRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/**
 * Stage 1 (shake) and Stage 2 (NFC checkpoint sequence). Shows over the lock
 * screen, blocks back. Stage 1: shake to fill the ring. Stage 2: tap the registered
 * NFC tags in a randomised order while the countdown runs; if none are
 * registered, a single "tap to confirm" stands in.
 *
 * One layout serves every stage: a label row (with the time-left chip in Stage 2),
 * a big heading, two stats, the ring and a "what's next" card, each filled by the
 * render functions below.
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
    /** The running timer of whichever content step is up (quote, stretch, schedule, to-do). */
    private var stepTimer: CountDownTimer? = null
    /** Which stretch the stretch list was last drawn for (-1: not drawn). */
    private var stretchRowsShown = -1
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

    /** When this alarm session started (it fired), for the done screen's "Up in". */
    private var alarmStartMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showWhenLockedAndTurnScreenOn()

        isSessionActive = true
        alarmStartMs = SystemClock.elapsedRealtime()
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
        renderStage1()
        binding.continueRing.makeSmall()

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
            if (stage == STAGE_1) renderStage1Info()
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
                    binding.tvNote.visibility = View.GONE
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
        stepTimer?.cancel()
        binding.root.removeCallbacks(dismissRunnable)
        runCatching { unregisterReceiver(countdownExpiredReceiver) }
        isSessionActive = false
    }

    // --- Stage 1 ---

    /**
     * "STAGE 1 OF 2 · FRI 16 OCT", the time at 72sp, Shaken / Left, the ring, and
     * the card saying what Stage 2 will ask for. The time shown is the time the
     * alarm went off (now), which is the alarm time on a real morning.
     */
    private fun renderStage1() {
        binding.tvStageLabel.text = getString(
            R.string.alarm_stage1_label,
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        )
        binding.tvTimeLeft.isVisible = false
        binding.tvBigLabel.isVisible = false
        setBig(LocalTime.now().format(clockFormat()), BIG_TIME_SP)
        binding.noticeWrongTag.isVisible = false

        binding.tvStat1Label.setText(R.string.alarm_stat_shaken)
        binding.tvStat1Value.setTextColor(color(R.attr.colorPositive))
        binding.tvStat2Label.setText(R.string.alarm_stat_left)
        binding.stat2.isVisible = true
        renderShake(0f)

        binding.ivRingIcon.isVisible = false
        setRingText(getString(R.string.shake_instruction), RING_TEXT_SP)
        renderStage1Info()
    }

    private fun renderShake(progress: Float) {
        binding.ring.setProgress(progress)
        binding.tvStat1Value.text = getString(R.string.alarm_stat_shaken_value, (progress * 100).toInt())
        val remaining = ceil((1f - progress) * TARGET_SECONDS).toInt().coerceAtLeast(0)
        binding.tvStat2Value.text = getString(R.string.seconds_format, remaining)
    }

    /** Next: N NFC checkpoints / Time to finish: 15:00 / If you don't: Nuclear alarm. */
    private fun renderStage1Info() {
        setInfoRows(
            getString(R.string.alarm_info_next) to
                resources.getQuantityString(R.plurals.alarm_info_next_value, sequenceLength, sequenceLength),
            getString(R.string.alarm_info_time_to_finish) to
                CountdownService.formatTime(stage2DurationMinutes * 60),
            getString(R.string.alarm_info_if_not) to null
        )
    }

    private fun onShakeProgress(progress: Float) {
        val percent = (progress * 100).toInt()
        renderShake(progress)

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

        renderShake(1f)
        AlarmService.stop(this)
        logStage1Success()

        binding.tvRingText.setText(R.string.stage1_success)
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

        binding.tvTimeLeft.isVisible = true

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

    private fun showCheckpoint() = renderCheckpoint(wrong = null)

    /**
     * "STAGE 2 · CHECKPOINT n OF N", GO TO + the tag, Done / Then, the segmented
     * ring and what's left. With [wrong] set, the heading reads "WRONG TAG — FIND"
     * and the error notice names the tag that was tapped.
     */
    private fun renderCheckpoint(wrong: CheckpointResult.Wrong?) {
        val manager = checkpointManager ?: return
        val number = manager.currentNumber
        val total = manager.total
        val target = manager.currentTag?.label.orEmpty()

        binding.tvStageLabel.text = getString(R.string.alarm_stage2_label, number, total)
        binding.tvBigLabel.isVisible = true
        binding.tvBigLabel.setText(if (wrong != null) R.string.alarm_wrong_find else R.string.alarm_go_to)
        setBig(target, nameSizeSp(target))

        binding.noticeWrongTag.isVisible = wrong != null
        if (wrong != null) {
            binding.tvNotice.text = wrong.tapped?.let { getString(R.string.wrong_tag_notice, it.label, target) }
                ?: getString(R.string.wrong_tag_notice_unregistered, target)
        }

        binding.statsRow.isVisible = true
        binding.tvStat1Label.setText(R.string.alarm_stat_done)
        binding.tvStat1Value.text = getString(R.string.alarm_stat_done_value, number - 1, total)
        binding.tvStat1Value.setTextColor(color(R.attr.colorPositive))
        binding.stat2.isVisible = true
        binding.tvStat2Label.setText(R.string.alarm_stat_then)
        binding.tvStat2Value.text = thenLabel(number, total)

        binding.ring.isVisible = true
        binding.ring.setSegments(total, number - 1)
        binding.ringCentre.isVisible = true
        binding.ivRingIcon.isVisible = true
        setRingText(getString(R.string.alarm_hold_phone), RING_HINT_SP)

        val after = total - number
        setInfoRows(
            getString(R.string.alarm_info_still_to_find) to
                if (after == 0) getString(R.string.alarm_info_last)
                else resources.getQuantityString(R.plurals.alarm_info_still_value, after, after),
            getString(R.string.alarm_info_if_time_runs_out) to null
        )
    }

    /** What follows this checkpoint: the content screen for this gap, the next tag, or the finish. */
    private fun thenLabel(number: Int, total: Int): String {
        if (number >= total) return getString(R.string.alarm_then_finish)
        return when (contentScreenManager?.contentForGap(gapsShown)) {
            ContentType.QUOTE -> getString(R.string.alarm_tab_content_quote)
            ContentType.STRETCH -> getString(R.string.alarm_tab_content_stretch)
            ContentType.DAILY_SCHEDULE -> getString(R.string.alarm_tab_content_schedule)
            ContentType.CHALKBOARD -> getString(R.string.alarm_tab_content_todo)
            null -> getString(R.string.alarm_then_next_tag)
        }
    }

    /**
     * The wrong-tag notice wobbles once as it appears (-12, +10, -8, +6, -3, 0dp
     * over about half a second) — skipped when the system has animations off.
     */
    private fun wobbleNotice() {
        val notice = binding.noticeWrongTag
        notice.animate().cancel()
        notice.translationX = 0f
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val dp = resources.displayMetrics.density
        ObjectAnimator.ofFloat(
            notice, View.TRANSLATION_X,
            0f, -12f * dp, 10f * dp, -8f * dp, 6f * dp, -3f * dp, 0f
        ).apply {
            duration = WOBBLE_MS
            start()
        }
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
                Log.d(TAG, "Wrong tag (${result.tapped?.label ?: "unregistered"}) — still need: ${result.expected.label}")
                renderCheckpoint(wrong = result)
                wobbleNotice()
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
        binding.tvStageLabel.setText(R.string.alarm_stage2_fallback_label)
        binding.tvBigLabel.isVisible = false
        setBig(getString(R.string.stage2_title), FALLBACK_TITLE_SP)
        binding.noticeWrongTag.isVisible = false
        binding.statsRow.isVisible = false
        binding.ring.isVisible = false
        binding.ringCentre.isVisible = false
        setInfoRows(getString(R.string.alarm_info_if_time_runs_out) to null)
        binding.btnConfirm.visibility = View.VISIBLE
        binding.btnConfirm.setOnClickListener { onStage2Complete() }
    }

    // --- Content screens (between checkpoints) ---

    private fun showContentScreen(type: ContentType) {
        showingContent = true
        binding.tvStageLabel.text =
            getString(R.string.alarm_between_taps, gapsShown, contentScreenManager?.count ?: gapsShown)
        // Freeze the Stage 2 deadline while content shows — content time shouldn't
        // count against the nuclear timer (a 10-min stretch must not fail you).
        CountdownService.pause(this)
        binding.content.visibility = View.GONE
        binding.contentPanel.visibility = View.VISIBLE
        resetContentPanel()

        when (type) {
            ContentType.QUOTE -> showQuoteContent()
            ContentType.STRETCH -> showStretchContent()
            ContentType.DAILY_SCHEDULE -> showDailyScheduleContent()
            ContentType.CHALKBOARD -> showChalkboardContent()
        }
        Log.d(TAG, "Content screen shown: $type")
    }

    /** Back to a blank frame: every screen-specific block hidden, Continue reset. */
    private fun resetContentPanel() {
        stepTimer?.cancel()
        stepTimer = null
        with(binding) {
            tvContentTitle.isVisible = false
            tvContentSubtitle.isVisible = false
            contentFreshness.root.isVisible = false
            contentAuthor.isVisible = false
            stretchRingArea.isVisible = false
            stretchList.isVisible = false
            stretchList.removeAllViews()
            scheduleCard.isVisible = false
            todoList.isVisible = false
            todoList.removeAllViews()
            contentScroll.scrollTo(0, 0)
            btnContinue.animate().cancel()
            btnContinue.scaleX = 1f
            btnContinue.scaleY = 1f
            btnContinue.setOnClickListener { onContentContinue() }
        }
        stretchRowsShown = -1
        setBody(null)
    }

    /**
     * The scrolling block's main text: the quote itself at 36sp bold, or a one-line
     * state ("Checking with Alfred…", "Nothing scheduled today") in secondary text.
     * Null hides it.
     */
    private fun setBody(text: CharSequence?, quote: Boolean = false) {
        binding.contentBody.isVisible = text != null
        binding.contentBody.text = text
        binding.contentBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (quote) QUOTE_SP else STATUS_SP)
        binding.contentBody.setTextColor(
            color(if (quote) MaterialR.attr.colorOnSurface else MaterialR.attr.colorOnSurfaceVariant)
        )
    }

    /**
     * The timed Continue shared by every timed step: the button shows from the
     * start but stays disabled (surface 2, secondary text) while the 56dp ring
     * beside it fills in accent and counts the seconds down; when it's full the
     * ring turns positive with a ✓ and Continue enables with the accent fill.
     */
    private fun startTimedContinue(durationMs: Long) {
        binding.continueTimer.isVisible = true
        binding.btnContinue.isEnabled = false
        binding.continueRing.setProgress(0f)
        renderContinueSeconds(durationMs)
        stepTimer = object : CountDownTimer(durationMs, TICK_MS) {
            override fun onTick(msLeft: Long) {
                binding.continueRing.setProgress(1f - msLeft.toFloat() / durationMs)
                renderContinueSeconds(msLeft)
            }

            override fun onFinish() {
                binding.continueRing.setComplete()
                binding.tvContinueSeconds.setText(R.string.status_done)
                binding.tvContinueSeconds.setTextColor(color(R.attr.colorPositive))
                enableContinue()
            }
        }.start()
    }

    private fun renderContinueSeconds(msLeft: Long) {
        binding.tvContinueSeconds.text =
            getString(R.string.content_continue_seconds, ceil(msLeft / 1000.0).toInt())
        binding.tvContinueSeconds.setTextColor(color(MaterialR.attr.colorPrimary))
    }

    /** Continue becomes tappable, with a brief scale pop so the change is noticed. */
    private fun enableContinue() {
        binding.btnContinue.isEnabled = true
        binding.btnContinue.animate()
            .scaleX(1.04f).scaleY(1.04f).setDuration(150L)
            .withEndAction {
                binding.btnContinue.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
            }
            .start()
    }

    private fun showQuoteContent() {
        binding.tvContentLabel.setText(R.string.content_quote_heading)
        lifecycleScope.launch {
            val quote = repository.getRandomQuote()
            setBody(quote?.quoteText?.let { "“$it”" }.orEmpty(), quote = true)
            val author = quote?.author
            if (!author.isNullOrBlank()) {
                binding.contentAuthor.visibility = View.VISIBLE
                binding.contentAuthor.text = getString(R.string.quote_author_format, author)
            }
        }
        startTimedContinue(CONTENT_DISMISS_LOCK_MS) // sit with the quote before continuing
    }

    /** One stretch in the session: what to do, and for how long. */
    private data class StretchStep(val name: String, val instructions: String?, val seconds: Int)

    /**
     * The stretch: one timer for the whole session, the 210dp ring counting it down
     * (mm:ss over "of 5:00"), and the list underneath marking which stretch is up.
     * Continue is gated by the big ring, so the small timer hides here. With no
     * routine set, the built-in suggestions share the configured stretch time.
     */
    private fun showStretchContent() {
        binding.tvContentLabel.setText(R.string.content_stretch_heading)
        binding.continueTimer.isVisible = false
        binding.btnContinue.isEnabled = false
        binding.stretchRingArea.isVisible = true
        binding.stretchRing.setProgress(0f)
        binding.stretchList.isVisible = true
        val gap = gapsShown
        lifecycleScope.launch {
            val routine = repository.getStretchRoutineForGoal(morningGoal)
            val exercises = routine?.let { repository.getExercisesForRoutine(it.id) }.orEmpty()
            val steps = if (exercises.isEmpty()) {
                Log.d(TAG, "Stretch: no routine/exercises — using the suggestions")
                val names = getString(R.string.stretch_suggestions).lines()
                    .map { it.removePrefix("•").trim() }
                    .filter { it.isNotEmpty() }
                val each = (stretchDurationMinutes * 60 / names.size.coerceAtLeast(1)).coerceAtLeast(1)
                names.map { StretchStep(it, null, each) }
            } else {
                Log.d(TAG, "Stretch routine '${routine?.name}', ${exercises.size} exercises")
                exercises.map { StretchStep(it.name, it.instructions, it.durationSeconds.coerceAtLeast(1)) }
            }
            if (!showingContent || gapsShown != gap) return@launch
            runStretch(steps)
        }
    }

    private fun runStretch(steps: List<StretchStep>) {
        val totalSeconds = steps.sumOf { it.seconds }
        val totalMs = totalSeconds * 1000L
        binding.tvStretchOf.text = getString(R.string.content_stretch_of, CountdownService.formatTime(totalSeconds))
        renderStretch(steps, msLeft = totalMs, totalMs = totalMs)
        stepTimer = object : CountDownTimer(totalMs, TICK_MS) {
            override fun onTick(msLeft: Long) = renderStretch(steps, msLeft, totalMs)

            override fun onFinish() {
                binding.stretchRing.setComplete()
                binding.tvStretchTime.setText(R.string.status_done)
                binding.tvStretchTime.setTextColor(color(R.attr.colorPositive))
                binding.tvContentLabel.text = getString(R.string.content_stretch_label, steps.size, steps.size)
                renderStretchList(steps, current = steps.size)
                enableContinue()
            }
        }.start()
    }

    private fun renderStretch(steps: List<StretchStep>, msLeft: Long, totalMs: Long) {
        binding.stretchRing.setProgress(1f - msLeft.toFloat() / totalMs)
        binding.tvStretchTime.text = CountdownService.formatTime(ceil(msLeft / 1000.0).toInt())
        binding.tvStretchTime.setTextColor(color(MaterialR.attr.colorPrimary))
        // Which stretch is up: the first whose end hasn't been reached yet.
        val elapsedSeconds = (totalMs - msLeft) / 1000L
        var end = 0L
        val current = steps.indexOfFirst { end += it.seconds; elapsedSeconds < end }.let {
            if (it < 0) steps.lastIndex else it
        }
        binding.tvContentLabel.text = getString(R.string.content_stretch_label, current + 1, steps.size)
        renderStretchList(steps, current)
    }

    /**
     * Done stretches ✓ in positive with secondary text, the current one ● in accent
     * and bold (with its instructions), upcoming ones ○. Rebuilt only when the
     * current stretch changes.
     */
    private fun renderStretchList(steps: List<StretchStep>, current: Int) {
        if (current == stretchRowsShown) return
        stretchRowsShown = current
        val list = binding.stretchList
        list.removeAllViews()
        steps.forEachIndexed { i, step ->
            if (i > 0) {
                list.addView(
                    View(this, null, 0, R.style.DailySync_Hairline),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.hairline))
                )
            }
            val row = ItemAlarmStretchRowBinding.inflate(layoutInflater, list, true)
            row.tvName.text = step.name
            when {
                i < current -> {
                    row.tvMark.setText(R.string.status_done)
                    row.tvMark.setTextColor(color(R.attr.colorPositive))
                    row.tvName.setTextColor(color(MaterialR.attr.colorOnSurfaceVariant))
                    row.tvName.setTypeface(row.tvName.typeface, android.graphics.Typeface.NORMAL)
                }
                i == current -> {
                    row.tvMark.setText(R.string.status_current)
                    row.tvMark.setTextColor(color(MaterialR.attr.colorPrimary))
                    row.tvName.setTextColor(color(MaterialR.attr.colorOnSurface))
                    row.tvName.setTypeface(row.tvName.typeface, android.graphics.Typeface.BOLD)
                    row.tvInstructions.isVisible = !step.instructions.isNullOrBlank()
                    row.tvInstructions.text = step.instructions
                }
                else -> {
                    row.tvMark.setText(R.string.status_pending)
                    row.tvMark.setTextColor(color(MaterialR.attr.colorOnSurfaceVariant))
                    row.tvName.setTextColor(color(MaterialR.attr.colorOnSurface))
                }
            }
        }
    }

    /**
     * Today's schedule from Alfred: the date, any all-day markers, and the same
     * period strip as Home. The timed Continue starts now rather than when Alfred
     * answers: a slow or absent Alfred must not lengthen the morning, and the fetch
     * can't fail in a way that strands this screen — the worst case is "Schedule
     * unavailable" and Continue frees up on schedule either way.
     */
    private fun showDailyScheduleContent() {
        binding.tvContentLabel.setText(R.string.content_schedule_heading)
        binding.tvContentTitle.isVisible = true
        binding.tvContentTitle.text = LocalDate.now().format(contentDateFormat())
        setBody(getString(R.string.schedule_loading))
        startTimedContinue(CONTENT_DISMISS_LOCK_MS)

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
     * The age of what's on the content screen, from every feed it was built from —
     * the oldest wins. No watch or retry here: the screen lives for one timed step.
     */
    private fun renderContentAge(vararg results: AlfredResult<*>?): Freshness.Age? {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val age = Freshness.worst(*results.map { Freshness.of(it, now, zone) }.toTypedArray())
        FreshnessView.render(binding.contentFreshness, age, AlfredReachability.state.value)
        return age
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
        var calendar: AlfredResult<*>? = null
        val events = try {
            val result = alfredRepository.getCalendarDay(LocalDate.now())
            calendar = result
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
        if (!showingContent || gapsShown != gap) {
            Log.d(TAG, "Family calendar arrived after the screen moved on — dropped")
            return
        }
        // A saved calendar with nothing on today is still a claim about today, so
        // its age counts even when there's nothing to layer.
        if (schedule !is AlfredResult.Unavailable) renderContentAge(schedule, calendar)
        if (events.isEmpty()) {
            // Logged explicitly: "nothing on today" and "the calendar broke" look
            // identical on screen, and only the log can tell them apart afterwards.
            Log.d(TAG, "Family calendar: nothing on today — vault schedule shown alone")
            return
        }
        renderSchedule(schedule, events, calendar)
        Log.d(TAG, "Family calendar: ${events.size} events layered onto today's schedule")
    }

    private fun renderSchedule(
        result: AlfredResult<List<ScheduleTaskDto>>,
        events: List<FamilyEvent> = emptyList(),
        calendar: AlfredResult<*>? = null
    ) {
        val age = renderContentAge(result, calendar)
        // The 44sp date is the first thing read. A plan saved on an earlier day is
        // that day's plan, so the title names that day rather than claiming today.
        val planDay = Freshness.of(result, System.currentTimeMillis(), ZoneId.systemDefault())
            ?.takeIf { it.daysAgo >= 1 }
            ?.let { Instant.ofEpochMilli(it.fetchedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate() }
        binding.tvContentTitle.text = (planDay ?: LocalDate.now()).format(contentDateFormat())

        val tasks = when (result) {
            is AlfredResult.Fresh -> result.data
            is AlfredResult.Stale -> result.data
            AlfredResult.Unavailable -> {
                Log.d(TAG, "Daily schedule: Alfred unreachable and nothing cached")
                // The calendar can still carry the morning on its own.
                if (events.isEmpty()) {
                    setBody(getString(R.string.schedule_unavailable))
                    return
                }
                emptyList()
            }
        }

        val agenda = FamilyCalendar.agenda(tasks, events)
        // All-day markers belong to no period: they describe the day ("WFH day").
        val dayType = agenda.allDay.joinToString(" · ") { it.summary }
        binding.tvContentSubtitle.isVisible = dayType.isNotEmpty()
        binding.tvContentSubtitle.text = dayType

        val columns = HomeTiles.scheduleColumns(agenda, LocalTime.now())
        if (columns.isEmpty()) {
            binding.scheduleCard.isVisible = false
            setBody(getString(R.string.schedule_empty))
            return
        }
        setBody(null)
        binding.scheduleCard.isVisible = true
        ScheduleStrip.render(binding.scheduleStrip, binding.scheduleScroll, columns)
        Log.d(
            TAG,
            "Daily schedule: ${agenda.groups.size} groups, ${tasks.size} tasks " +
                "(stale=${result is AlfredResult.Stale}, age=${age?.level}, planDay=$planDay)"
        )
    }

    /**
     * The rolling to-do from Alfred, as positive checkboxes. Same shape as the
     * schedule screen: the timed Continue starts now, not when Alfred answers, so
     * a slow or absent Alfred can't lengthen the morning or strand the screen.
     */
    private fun contentDateFormat(): DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault())

    private fun showChalkboardContent() {
        binding.tvContentLabel.setText(R.string.content_chalkboard_heading)
        binding.tvContentTitle.isVisible = true
        binding.tvContentTitle.setText(R.string.content_todo_title)
        setBody(getString(R.string.chalkboard_loading))
        startTimedContinue(CONTENT_DISMISS_LOCK_MS)

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
        val age = renderContentAge(result)

        val tasks = when (result) {
            is AlfredResult.Fresh -> result.data
            is AlfredResult.Stale -> result.data
            AlfredResult.Unavailable -> {
                Log.d(TAG, "Chalkboard: Alfred unreachable and nothing cached")
                setBody(getString(R.string.chalkboard_unavailable))
                return
            }
        }

        // Still to do only — ticked items wait for the overnight sweep.
        val items = RollingTodo.items(tasks).filterNot { it.done }
        if (items.isEmpty()) {
            setBody(getString(R.string.chalkboard_empty))
            return
        }
        setBody(null)
        val list = binding.todoList
        list.isVisible = true
        list.removeAllViews()
        items.forEachIndexed { i, item ->
            if (i > 0) {
                list.addView(
                    View(this, null, 0, R.style.DailySync_Hairline),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.hairline))
                )
            }
            val row = ItemAlarmTodoRowBinding.inflate(layoutInflater, list, true)
            row.cbItem.text = MarkdownRenderer.render(item.task)
            // Pre-line cached items have nothing to target a tick at.
            row.cbItem.isEnabled = item.line != null
            row.cbItem.setOnCheckedChangeListener { box, checked ->
                if (!checked) return@setOnCheckedChangeListener
                box.isEnabled = false // a tick is final here, as on the Today screen
                box.paintFlags = box.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                box.setTextColor(color(MaterialR.attr.colorOnSurfaceVariant))
                tickTodo(item, fresh = result is AlfredResult.Fresh)
            }
        }
        Log.d(TAG, "Chalkboard: ${items.size} open items (stale=${result is AlfredResult.Stale}, age=${age?.level})")
    }

    /**
     * Ticks a rolling to-do item through to Alfred, the same two ways the Today
     * screen does: straight to the API while the list is fresh, into the offline
     * queue (flushed when Alfred is back) when it's a saved copy or the write
     * finds Alfred gone.
     */
    private fun tickTodo(item: RollingTodoItem, fresh: Boolean) {
        val line = item.line ?: return
        lifecycleScope.launch {
            if (!fresh) {
                ChalkboardSync.getInstance(applicationContext).enqueue(ChalkboardVerb.TICK, item.task, line)
                Log.d(TAG, "Chalkboard tick queued (saved copy on screen)")
                return@launch
            }
            when (alfredRepository.tickChalkboardItem(line)) {
                AlfredWriteResult.Done -> Log.d(TAG, "Chalkboard tick landed")
                is AlfredWriteResult.StaleTarget ->
                    Log.d(TAG, "Chalkboard tick: the list had changed on Alfred — not ticked")
                AlfredWriteResult.Unreachable -> {
                    ChalkboardSync.getInstance(applicationContext).enqueue(ChalkboardVerb.TICK, item.task, line)
                    Log.d(TAG, "Chalkboard tick didn't land — queued")
                }
            }
        }
    }

    private fun onContentContinue() {
        stepTimer?.cancel()
        stepTimer = null
        showingContent = false
        binding.contentPanel.visibility = View.GONE
        binding.content.visibility = View.VISIBLE
        CountdownService.resume(this) // restart the Stage 2 deadline
        showCheckpoint()
    }

    /**
     * The done screen: "FRI 16 OCT · ALARM FINISHED", Great job!, how long it took
     * from the alarm firing (Up in), the tags (Tags) and the streak, a full ring
     * with a ✓, and "Start the day" to close the alarm.
     */
    private fun onStage2Complete() {
        val seconds = ((SystemClock.elapsedRealtime() - stage2StartMs) / 1000L).toInt()
        val upInSeconds = ((SystemClock.elapsedRealtime() - alarmStartMs) / 1000L).toInt()
        stage = STAGE_DONE
        // Keep reader mode engaged (taps now ignored, since stage != STAGE_2) until
        // the activity pauses — otherwise a lingering chip gets grabbed by the
        // system's own NFC popup. onPause/onDestroy disables it.
        CountdownService.stop(this)
        clearAlarmNotifications()

        binding.tvTimeLeft.isVisible = false
        binding.tvStageLabel.text = getString(
            R.string.alarm_done_label,
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        )
        binding.tvBigLabel.isVisible = false
        setBig(getString(R.string.success_title), DONE_TITLE_SP)
        binding.noticeWrongTag.isVisible = false
        binding.btnConfirm.visibility = View.GONE
        binding.infoCard.isVisible = false

        binding.statsRow.isVisible = true
        binding.tvStat1Label.setText(R.string.alarm_stat_up_in)
        binding.tvStat1Value.text = CountdownService.formatTime(upInSeconds)
        binding.tvStat1Value.setTextColor(color(MaterialR.attr.colorOnSurface))
        val tags = checkpointManager?.total ?: 0
        binding.stat2.isVisible = tags > 0
        binding.tvStat2Label.setText(R.string.alarm_stat_tags)
        binding.tvStat2Value.text = getString(R.string.alarm_stat_tags_value, tags, tags)
        binding.tvStat2Value.setTextColor(color(R.attr.colorPositive))
        binding.stat3.isVisible = false

        binding.ring.isVisible = true
        binding.ring.setComplete()
        binding.ringCentre.isVisible = true
        binding.ivRingIcon.setImageResource(R.drawable.ic_done_check)
        binding.ivRingIcon.contentDescription = getString(R.string.alarm_all_done)
        binding.ivRingIcon.layoutParams = binding.ivRingIcon.layoutParams.apply {
            val size = resources.getDimensionPixelSize(R.dimen.done_check_size)
            width = size
            height = size
        }
        binding.ivRingIcon.isVisible = true
        binding.tvRingText.isVisible = false
        binding.tvNote.isVisible = true
        binding.tvNote.text = goalReminder()

        binding.btnStopAlarm.isVisible = false
        binding.btnStartDay.isVisible = true
        binding.btnStartDay.setOnClickListener {
            binding.root.removeCallbacks(dismissRunnable)
            dismissToHome()
        }

        lifecycleScope.launch {
            recordStage2Success(seconds)
            val streak = repository.getCurrentStreak()
            binding.stat3.isVisible = true
            binding.tvStat3Value.text = getString(
                R.string.alarm_stat_streak_value,
                resources.getQuantityString(R.plurals.alarm_streak_days, streak, streak)
            )
            Log.d(TAG, "Stage 2 success — streak now $streak, up in ${upInSeconds}s")
        }

        // "Start the day" is the way out; this only stops a forgotten phone keeping
        // its screen on (FLAG_KEEP_SCREEN_ON) indefinitely.
        binding.root.postDelayed(dismissRunnable, DONE_SCREEN_TIMEOUT_MS)
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
                    binding.tvTimeLeft.text =
                        getString(R.string.alarm_time_left, CountdownService.formatTime(seconds))
                }
            }
        }
    }

    // --- Shared rendering ---

    /** The big heading: [text] at [sizeSp]. */
    private fun setBig(text: String, sizeSp: Float) {
        binding.tvBig.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        binding.tvBig.text = text
    }

    /** The ring's centre text: 22sp for the shake instruction, 16sp under the NFC icon. */
    private fun setRingText(text: String, sizeSp: Float) {
        binding.tvRingText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        binding.tvRingText.text = text
    }

    /** 60sp for a short tag name, stepping down so a long one still fits in two lines. */
    private fun nameSizeSp(name: String): Float = when {
        name.length <= 9 -> 60f
        name.length <= 14 -> 46f
        else -> 36f
    }

    /**
     * Fills the "what's next" card with up to three label/value rows, hairlines
     * between. A null value means "Nuclear alarm" in the error colour — the only
     * red on this screen.
     */
    private fun setInfoRows(vararg rows: Pair<String, String?>) {
        binding.infoCard.isVisible = true
        val views = listOf(
            Triple(binding.infoRow1, binding.tvInfo1Label, binding.tvInfo1Value),
            Triple(binding.infoRow2, binding.tvInfo2Label, binding.tvInfo2Value),
            Triple(binding.infoRow3, binding.tvInfo3Label, binding.tvInfo3Value)
        )
        views.forEachIndexed { i, (row, label, value) ->
            val content = rows.getOrNull(i)
            row.isVisible = content != null
            if (content == null) return@forEachIndexed
            label.text = content.first
            value.text = content.second ?: getString(R.string.alarm_info_nuclear)
            value.setTextColor(
                color(if (content.second == null) MaterialR.attr.colorError else MaterialR.attr.colorOnSurface)
            )
        }
        binding.infoDivider2.isVisible = rows.size > 1
        binding.infoDivider3.isVisible = rows.size > 2
    }

    private fun color(attr: Int): Int = MaterialColors.getColor(binding.root, attr)

    private fun clockFormat(): DateTimeFormatter = DateTimeFormatter.ofPattern(
        if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm",
        Locale.getDefault()
    )

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
            binding.tvNote.visibility = View.VISIBLE
            binding.tvNote.text = getString(R.string.nfc_unlock_hint)
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
        /** The done screen closes itself after this if "Start the day" isn't tapped. */
        private const val DONE_SCREEN_TIMEOUT_MS = 120_000L
        /** Timer granularity, so the rings move smoothly rather than in 1s steps. */
        private const val TICK_MS = 100L
        private const val WRONG_PULSE_MS = 150L
        private const val WOBBLE_MS = 500L

        private const val BIG_TIME_SP = 72f
        private const val FALLBACK_TITLE_SP = 40f
        private const val RING_TEXT_SP = 22f
        private const val RING_HINT_SP = 16f
        private const val DONE_TITLE_SP = 60f
        private const val QUOTE_SP = 36f
        private const val STATUS_SP = 18f

        private const val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
    }
}
