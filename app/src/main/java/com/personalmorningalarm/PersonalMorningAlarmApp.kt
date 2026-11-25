package com.personalmorningalarm

import android.app.Application
import android.util.Log
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import com.personalmorningalarm.data.QuoteSeedData
import com.personalmorningalarm.data.StretchSeedData
import com.personalmorningalarm.data.entity.ContentToggle
import com.personalmorningalarm.data.entity.StretchExercise
import com.personalmorningalarm.data.entity.StretchRoutine
import com.personalmorningalarm.data.model.ContentType
import com.personalmorningalarm.data.profile.ProfileSettings
import com.personalmorningalarm.util.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Seeds bundled quotes and default content toggles on first launch (idempotent). */
class PersonalMorningAlarmApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Apply the saved theme before any activity is created.
        ThemeManager(this).applySaved()

        // Before anything opens the database: whether it is already on disk is how
        // an install from before profiles is told from a fresh one (ProfileSettings).
        ProfileSettings(this).settle(existingInstall = getDatabasePath(AppDatabase.DB_NAME).exists())

        val repository = AlarmRepository(AppDatabase.getInstance(this))
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (repository.getQuoteCount() == 0) {
                repository.addQuotes(QuoteSeedData.quotes)
                Log.d(TAG, "Seeded ${QuoteSeedData.quotes.size} bundled quotes")
            }
            seedMissingContentToggles(repository)
            if (repository.getRoutineCount() == 0) {
                seedStretchRoutines(repository)
                Log.d(TAG, "Seeded ${StretchSeedData.routines.size} stretch routines")
            }
        }
    }

    /**
     * Inserts a toggle row for any content type that hasn't got one, rather than
     * seeding only into an empty table — that way a content type added in a later
     * version reaches existing installs too, instead of having no row for settings
     * to switch on. Types already present keep the user's saved state.
     */
    private suspend fun seedMissingContentToggles(repository: AlarmRepository) {
        val existing = repository.getAllContentToggles().map { it.contentType }.toSet()
        val missing = DEFAULT_CONTENT_TOGGLES.filterNot { it.contentType in existing }
        if (missing.isEmpty()) return
        repository.saveContentToggles(missing)
        Log.d(TAG, "Seeded content toggles: ${missing.map { it.contentType }}")
    }

    /**
     * Inserts each preset routine, then its exercises with the new routine id and a
     * display order. The default routine (StretchSeedData.DEFAULT_ACTIVE_INDEX) is
     * marked active.
     */
    private suspend fun seedStretchRoutines(repository: AlarmRepository) {
        StretchSeedData.routines.forEachIndexed { index, seed ->
            val routineId = repository.addRoutine(
                StretchRoutine(
                    name = seed.name,
                    isActive = index == StretchSeedData.DEFAULT_ACTIVE_INDEX
                )
            )
            repository.addExercises(
                seed.exercises.mapIndexed { order, ex ->
                    StretchExercise(
                        routineId = routineId,
                        name = ex.name,
                        durationSeconds = ex.durationSeconds,
                        instructions = ex.instructions,
                        displayOrder = order
                    )
                }
            )
        }
    }

    companion object {
        private const val TAG = "PMA"

        /**
         * Default state for every content screen. Daily Schedule is off by default:
         * it needs an Alfred address configured before it can show anything.
         */
        private val DEFAULT_CONTENT_TOGGLES = listOf(
            ContentToggle(contentType = ContentType.QUOTE, isEnabled = true, displayOrder = 0),
            ContentToggle(contentType = ContentType.STRETCH, isEnabled = true, displayOrder = 1),
            ContentToggle(contentType = ContentType.DAILY_SCHEDULE, isEnabled = false, displayOrder = 2),
            ContentToggle(contentType = ContentType.CHALKBOARD, isEnabled = false, displayOrder = 3)
        )
    }
}
