package com.personalmorningalarm.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.personalmorningalarm.R
import com.personalmorningalarm.challenge.MathChallenge
import com.personalmorningalarm.challenge.ShakeChallenge
import com.personalmorningalarm.challenge.TypingChallenge
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.entity.AlarmEvent
import com.personalmorningalarm.data.model.MorningGoal
import com.personalmorningalarm.databinding.ActivityNuclearDismissalBinding
import com.personalmorningalarm.service.NuclearAlarmService
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The nuclear-alarm dismissal gauntlet (Stage 2 failure). Shows all three
 * challenges at once — extended 30s shake, an arithmetic problem and a typed
 * confirmation phrase — each with a ✓/○ status. They can be done in
 * any order; the alarm is only silenced once ALL three are complete.
 *
 * Reaching this screen IS the failure, so the failed wake-up is logged (and the
 * streak reset) on entry, not on completion.
 *
 * Hard to escape: back/recents are swallowed and the sound/vibration/strobe run
 * in [NuclearAlarmService] independent of this activity. HOME can't be blocked on
 * stock Android (see progress log).
 */
class NuclearDismissalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNuclearDismissalBinding
    private lateinit var repository: AlarmRepository

    private lateinit var shakeChallenge: ShakeChallenge
    private val mathChallenge = MathChallenge()
    private lateinit var typingChallenge: TypingChallenge

    private var shakeDone = false
    private var mathDone = false
    private var typeDone = false
    private var dismissing = false

    private var morningGoal = MorningGoal.EXERCISE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showWhenLockedAndTurnScreenOn()

        binding = ActivityNuclearDismissalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AlarmRepository(AppDatabase.getInstance(this))
        typingChallenge = TypingChallenge(getString(R.string.nuclear_phrase))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Swallowed — nuclear can't be escaped with the back button.
            }
        })

        setupShake()
        setupMath()
        setupTyping()
        renderCards()

        // Reaching the nuclear screen is the failure: log it and reset the streak
        // up front, so it stands even if the user never completes the gauntlet.
        lifecycleScope.launch {
            morningGoal = repository.getCurrentConfig()?.morningGoal ?: MorningGoal.EXERCISE
            recordNuclearFailure()
            Log.d(TAG, "Nuclear failure recorded; current streak now ${repository.getCurrentStreak()}")
        }
    }

    override fun onResume() {
        super.onResume()
        if (!shakeDone) shakeChallenge.start()
    }

    override fun onPause() {
        super.onPause()
        shakeChallenge.stop()
    }

    // --- Challenge setup ---

    private fun setupShake() {
        shakeChallenge = ShakeChallenge(this, targetDurationMs = SHAKE_DURATION_MS).apply {
            onProgress = { binding.shakeProgress.progress = (it * 100).toInt() }
            onComplete = {
                binding.shakeProgress.progress = 100
                shakeDone = true
                markDone()
                Log.d(TAG, "Nuclear shake complete")
            }
        }
    }

    private fun setupMath() {
        binding.tvMathQuestion.text = getString(R.string.math_question_format, mathChallenge.questionText)
        binding.btnMathSubmit.setOnClickListener {
            if (mathChallenge.isCorrect(binding.etMath.text.toString())) {
                mathDone = true
                binding.etMath.isEnabled = false
                binding.btnMathSubmit.isEnabled = false
                markDone()
                Log.d(TAG, "Nuclear math correct")
            } else {
                binding.etMath.error = getString(R.string.math_wrong)
            }
        }
    }

    private fun setupTyping() {
        binding.tvTypePhrase.text = typingChallenge.phrase
        binding.btnTypeSubmit.setOnClickListener {
            if (typingChallenge.matches(binding.etType.text.toString())) {
                typeDone = true
                binding.etType.isEnabled = false
                binding.btnTypeSubmit.isEnabled = false
                markDone()
                Log.d(TAG, "Nuclear typing correct")
            } else {
                binding.etType.error = getString(R.string.type_wrong)
            }
        }
    }

    // --- Completion ---

    /** Called once a challenge's flag is set: redraws the cards, then checks for the finish. */
    private fun markDone() {
        renderCards()
        maybeFinish()
    }

    /**
     * Each challenge card in one of three states: done (positive ✓), current
     * (error ●, error outline — the first one not yet done, in screen order), or
     * still to do (secondary ○). They can be done in any order; "current" only
     * points at where to look next.
     */
    private fun renderCards() {
        val cards = listOf(
            Triple(binding.cardShake, binding.statusShake, shakeDone),
            Triple(binding.cardMath, binding.statusMath, mathDone),
            Triple(binding.cardType, binding.statusType, typeDone)
        )
        val current = cards.indexOfFirst { !it.third }
        cards.forEachIndexed { i, (card, status, done) ->
            when {
                done -> {
                    card.setBackgroundResource(R.drawable.bg_card)
                    status.setText(R.string.status_done)
                    status.setTextColor(MaterialColors.getColor(status, R.attr.colorPositive))
                }
                i == current -> {
                    card.setBackgroundResource(R.drawable.bg_card_error)
                    status.setText(R.string.status_current)
                    status.setTextColor(MaterialColors.getColor(status, com.google.android.material.R.attr.colorError))
                }
                else -> {
                    card.setBackgroundResource(R.drawable.bg_card)
                    status.setText(R.string.status_pending)
                    status.setTextColor(
                        MaterialColors.getColor(status, com.google.android.material.R.attr.colorOnSurfaceVariant)
                    )
                }
            }
        }
    }

    private fun maybeFinish() {
        if (shakeDone && mathDone && typeDone && !dismissing) {
            dismissing = true
            Log.d(TAG, "All nuclear challenges complete — dismissing")
            NuclearAlarmService.stop(this)
            finish()
        }
    }

    private suspend fun recordNuclearFailure() {
        val today = LocalDate.now().toString()
        val existing = repository.getEventByDate(today)
        if (existing != null) {
            repository.updateEvent(
                existing.copy(stage2Success = false, nuclearTriggered = true)
            )
        } else {
            // Reaching Stage 2 means Stage 1 was passed.
            repository.recordEvent(
                AlarmEvent(
                    date = today,
                    stage1Success = true,
                    stage2Success = false,
                    nuclearTriggered = true,
                    morningGoal = morningGoal
                )
            )
        }
        Log.d(TAG, "Logged nuclear failure for $today")
    }

    // --- Escape blocking / window flags ---

    /**
     * Belt-and-suspenders back blocking (the dispatcher handles the modern path,
     * this catches the legacy key path); also swallow recents. HOME can't be
     * intercepted on stock Android.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isBlockedKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    // Volume keys are handled on key-up too; swallow both so the nuclear alarm
    // can't be turned down or muted with the hardware buttons.
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isBlockedKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isBlockedKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE -> true
        else -> false
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!dismissing && NuclearAlarmService.isRunning) {
            Log.d(TAG, "User left nuclear screen — relaunching")
            startActivity(
                Intent(this, NuclearDismissalActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }
    }

    private fun showWhenLockedAndTurnScreenOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                .requestDismissKeyguard(this, null)
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

    companion object {
        private const val TAG = "PMA"
        private const val SHAKE_DURATION_MS = 30_000L

    }
}
