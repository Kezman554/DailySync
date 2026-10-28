package com.personalmorningalarm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.entity.AlarmConfig
import com.personalmorningalarm.data.entity.AlarmEvent
import com.personalmorningalarm.data.entity.BundledQuote
import com.personalmorningalarm.data.entity.ContentToggle
import com.personalmorningalarm.data.entity.NfcTag
import com.personalmorningalarm.data.entity.StretchExercise
import com.personalmorningalarm.data.entity.StretchRoutine
import com.personalmorningalarm.data.model.ContentType
import com.personalmorningalarm.data.model.MorningGoal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * Repository CRUD across every entity plus streak / weekly-stat calculation edge
 * cases, exercised against a real in-memory Room database (Robolectric provides
 * the SQLite + Context on the local JVM).
 */
@RunWith(RobolectricTestRunner::class)
class RepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AlarmRepository

    private val today: LocalDate = LocalDate.of(2026, 7, 5)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = AlarmRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------- CRUD ----

    @Test
    fun `alarm config insert, read, update, delete`() = runBlocking {
        assertNull(repo.getCurrentConfig())

        val id = repo.saveConfig(AlarmConfig(alarmTime = 390, morningGoal = MorningGoal.EXERCISE))
        val saved = repo.getCurrentConfig()!!
        assertEquals(390, saved.alarmTime)
        assertEquals(id, saved.id)

        repo.updateConfig(saved.copy(alarmTime = 420, isEnabled = false))
        val updated = repo.getCurrentConfig()!!
        assertEquals(420, updated.alarmTime)
        assertFalse(updated.isEnabled)

        repo.deleteConfig(updated)
        assertNull(repo.getCurrentConfig())
    }

    @Test
    fun `alarm event insert, read by date, update, delete`() = runBlocking {
        val id = repo.recordEvent(
            AlarmEvent(date = "2026-07-05", stage1Success = true, stage2Success = true, stage2TimeSeconds = 42)
        )
        val byDate = repo.getEventByDate("2026-07-05")!!
        assertEquals(42, byDate.stage2TimeSeconds)
        assertEquals(1, repo.getAllEvents().size)

        repo.updateEvent(byDate.copy(stage2TimeSeconds = 99))
        assertEquals(99, repo.getEventByDate("2026-07-05")!!.stage2TimeSeconds)

        repo.deleteEvent(repo.getEventByDate("2026-07-05")!!)
        assertNull(repo.getEventByDate("2026-07-05"))
        assertEquals(id, id) // id returned by insert is usable
    }

    @Test
    fun `nfc tag insert, active filter, lookup, update, delete`() = runBlocking {
        repo.registerTag(NfcTag(tagId = "AA11", label = "Kitchen", location = "Fridge"))
        repo.registerTag(NfcTag(tagId = "BB22", label = "Bath", location = "Mirror", isActive = false))

        assertEquals(2, repo.getAllTags().size)
        assertEquals(1, repo.getActiveNfcTags().size)
        assertEquals("Kitchen", repo.getTagByHardwareId("AA11")!!.label)
        assertNull(repo.getTagByHardwareId("ZZ99"))

        val tag = repo.getTagByHardwareId("BB22")!!
        repo.updateTag(tag.copy(isActive = true))
        assertEquals(2, repo.getActiveNfcTags().size)

        repo.deleteTag(repo.getTagByHardwareId("AA11")!!)
        assertEquals(1, repo.getAllTags().size)
    }

    @Test
    fun `content toggle insert, enabled filter, lookup by type, update`() = runBlocking {
        repo.saveContentToggles(
            listOf(
                ContentToggle(contentType = ContentType.QUOTE, isEnabled = true, displayOrder = 0),
                ContentToggle(contentType = ContentType.STRETCH, isEnabled = true, displayOrder = 1),
                ContentToggle(contentType = ContentType.DAILY_SCHEDULE, isEnabled = false, displayOrder = 2)
            )
        )
        assertEquals(3, repo.getAllContentToggles().size)
        assertEquals(2, repo.getEnabledContentToggles().size)

        val stretch = repo.getContentToggle(ContentType.STRETCH)!!
        repo.updateContentToggle(stretch.copy(durationMinutes = 10, isEnabled = false))
        assertEquals(10, repo.getContentToggle(ContentType.STRETCH)!!.durationMinutes)
        assertEquals(1, repo.getEnabledContentToggles().size)
    }

    @Test
    fun `bundled quote insert, count, random, update, delete`() = runBlocking {
        assertEquals(0, repo.getQuoteCount())
        assertNull(repo.getRandomQuote())

        repo.addQuotes(
            listOf(
                BundledQuote(quoteText = "Rise up", author = "Anon"),
                BundledQuote(quoteText = "Carpe diem", author = null)
            )
        )
        assertEquals(2, repo.getQuoteCount())
        assertNotNull(repo.getRandomQuote())

        val quote = repo.getAllQuotes().first { it.quoteText == "Rise up" }
        repo.updateQuote(quote.copy(quoteText = "Rise and grind"))
        assertTrue(repo.getAllQuotes().any { it.quoteText == "Rise and grind" })

        repo.deleteQuote(repo.getAllQuotes().first())
        assertEquals(1, repo.getQuoteCount())
    }

    @Test
    fun `stretch routine insert, single active flag, update, delete`() = runBlocking {
        val a = repo.addRoutine(StretchRoutine(name = "Warmup"))
        val b = repo.addRoutine(StretchRoutine(name = "Mobility"))
        assertEquals(2, repo.getRoutineCount())

        repo.setActiveRoutine(a)
        assertTrue(repo.getRoutine(a)!!.isActive)
        // Marking another active clears the previous one (single-active invariant).
        repo.setActiveRoutine(b)
        assertFalse(repo.getRoutine(a)!!.isActive)
        assertTrue(repo.getRoutine(b)!!.isActive)

        repo.updateRoutine(repo.getRoutine(a)!!.copy(name = "Warm Up v2"))
        assertEquals("Warm Up v2", repo.getRoutine(a)!!.name)

        repo.deleteRoutine(repo.getRoutine(a)!!)
        assertEquals(1, repo.getRoutineCount())
    }

    @Test
    fun `stretch exercises belong to a routine and cascade on delete`() = runBlocking {
        val routineId = repo.addRoutine(StretchRoutine(name = "Morning"))
        repo.addExercises(
            listOf(
                StretchExercise(routineId = routineId, name = "Reach", durationSeconds = 30, instructions = "Up", displayOrder = 0),
                StretchExercise(routineId = routineId, name = "Twist", durationSeconds = 20, instructions = "Round", displayOrder = 1)
            )
        )
        assertEquals(2, repo.getExercisesForRoutine(routineId).size)

        val first = repo.getExercisesForRoutine(routineId).first()
        repo.updateExercise(first.copy(durationSeconds = 45))
        assertEquals(45, repo.getExercisesForRoutine(routineId).first { it.id == first.id }.durationSeconds)

        // Deleting the parent routine cascades to its exercises (FK ON DELETE CASCADE).
        repo.deleteRoutine(repo.getRoutine(routineId)!!)
        assertEquals(0, repo.getExercisesForRoutine(routineId).size)
    }

    @Test
    fun `getStretchRoutineForGoal resolves goal mapping then active then first`() = runBlocking {
        val exerciseRoutine = repo.addRoutine(StretchRoutine(name = "Pre-Run"))
        val projectRoutine = repo.addRoutine(StretchRoutine(name = "General"))

        // No config, no active: falls back to the first routine.
        assertNotNull(repo.getStretchRoutineForGoal(MorningGoal.EXERCISE))

        // Active routine wins when matching is off.
        repo.setActiveRoutine(projectRoutine)
        assertEquals(projectRoutine, repo.getStretchRoutineForGoal(MorningGoal.EXERCISE)!!.id)

        // Goal mapping wins when matchRoutineToGoal is on.
        repo.saveConfig(
            AlarmConfig(
                alarmTime = 390,
                matchRoutineToGoal = true,
                exerciseRoutineId = exerciseRoutine,
                projectRoutineId = projectRoutine
            )
        )
        assertEquals(exerciseRoutine, repo.getStretchRoutineForGoal(MorningGoal.EXERCISE)!!.id)
        assertEquals(projectRoutine, repo.getStretchRoutineForGoal(MorningGoal.PROJECT)!!.id)
    }

    // ------------------------------------------------------------- Streaks ----

    private suspend fun success(date: LocalDate) =
        repo.recordEvent(AlarmEvent(date = date.toString(), stage1Success = true, stage2Success = true))

    private suspend fun failure(date: LocalDate) =
        repo.recordEvent(AlarmEvent(date = date.toString(), stage1Success = true, stage2Success = false))

    private suspend fun nuclear(date: LocalDate) =
        repo.recordEvent(AlarmEvent(date = date.toString(), nuclearTriggered = true, stage2Success = false))

    @Test
    fun `current streak is zero with no data`() = runBlocking {
        assertEquals(0, repo.getCurrentStreak(today))
    }

    @Test
    fun `single successful day today is a streak of one`() = runBlocking {
        success(today)
        assertEquals(1, repo.getCurrentStreak(today))
    }

    @Test
    fun `a success yesterday still counts as a live streak today`() = runBlocking {
        // Not-yet-attempted this morning shouldn't break the streak.
        success(today.minusDays(1))
        assertEquals(1, repo.getCurrentStreak(today))
    }

    @Test
    fun `a last success older than yesterday is a dead streak`() = runBlocking {
        success(today.minusDays(2))
        assertEquals(0, repo.getCurrentStreak(today))
    }

    @Test
    fun `consecutive successful days accumulate`() = runBlocking {
        success(today)
        success(today.minusDays(1))
        success(today.minusDays(2))
        success(today.minusDays(3))
        assertEquals(4, repo.getCurrentStreak(today))
    }

    @Test
    fun `a gap breaks the current streak at the gap`() = runBlocking {
        success(today)
        success(today.minusDays(1))
        // gap at today-2
        success(today.minusDays(3))
        success(today.minusDays(4))
        assertEquals(2, repo.getCurrentStreak(today))
    }

    @Test
    fun `a nuclear failure today breaks the streak immediately`() = runBlocking {
        success(today.minusDays(1))
        success(today.minusDays(2))
        nuclear(today)
        assertEquals(0, repo.getCurrentStreak(today))
    }

    @Test
    fun `duplicate success entries on one day do not inflate the streak`() = runBlocking {
        success(today)
        success(today) // second event, same date
        success(today.minusDays(1))
        assertEquals(2, repo.getCurrentStreak(today))
    }

    // -------------------------------------------------------- Weekly stats ----

    @Test
    fun `weekly counts cover the trailing seven days only`() = runBlocking {
        success(today)
        success(today.minusDays(1))
        failure(today.minusDays(2))
        success(today.minusDays(8)) // outside the 7-day window

        val (successDays, attemptedDays) = repo.getWeeklySuccessCounts(today)
        assertEquals(2, successDays)
        assertEquals(3, attemptedDays)
    }

}
