package com.personalmorningalarm.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.personalmorningalarm.data.entity.NoteVerb
import com.personalmorningalarm.data.entity.PendingNoteWrite
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Upgrading a phone with data on it. The old database is built from the committed
 * 8.json, seeded, then opened the way the app opens it, so Room's own schema check
 * runs as it does on the device: a table that doesn't match 9.json fails the open.
 * That check let a missing unique index through, so the last test proves it directly.
 *
 * On the JVM with Robolectric, not MigrationTestHelper: that's an instrumented test,
 * and instrumented tests never run against the A32 (see CLAUDE.md).
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbFile: File = context.getDatabasePath(DB_NAME)
    private var opened: AppDatabase? = null

    @Before
    fun setUp() {
        dbFile.parentFile?.mkdirs()
        context.deleteDatabase(DB_NAME)
        val old = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        createFromSchema(old, version = 8)
        seed(old)
        old.close()
    }

    @After
    fun tearDown() {
        opened?.close()
        context.deleteDatabase(DB_NAME)
    }

    private fun createFromSchema(db: SQLiteDatabase, version: Int) {
        val schema = Gson().fromJson(schemaFile(version).readText(), JsonObject::class.java)
            .getAsJsonObject("database")
        for (entity in schema.getAsJsonArray("entities").map { it.asJsonObject }) {
            val table = entity.get("tableName").asString
            db.execSQL(entity.get("createSql").asString.replace("\${TABLE_NAME}", table))
            entity.getAsJsonArray("indices")?.forEach {
                db.execSQL(it.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}", table))
            }
        }
        schema.getAsJsonArray("setupQueries").forEach { db.execSQL(it.asString) }
        db.version = version
    }

    private fun schemaFile(version: Int): File {
        val path = "schemas/com.personalmorningalarm.data.AppDatabase/$version.json"
        return listOf(File(path), File("app/$path")).first { it.exists() }
    }

    private fun seed(db: SQLiteDatabase) {
        db.insert("alarm_config", null, ContentValues().apply {
            put("id", 1); put("alarmTime", 390); put("isEnabled", 1); put("stage2DurationMinutes", 20)
            put("morningGoal", "PROJECT"); put("sequenceLength", 3); put("matchRoutineToGoal", 0)
            put("exerciseRoutineId", 0); put("projectRoutineId", 0); put("stage1SoundId", "bell")
            put("nuclearSoundId", "siren"); put("stage1Volume", 80); put("vibrationEnabled", 1)
            put("createdAt", 1_000L)
        })
        db.insert("nfc_tags", null, ContentValues().apply {
            put("id", 1); put("tagId", "04:AA:BB:CC"); put("label", "Invented tag")
            put("location", "Invented shelf"); put("tag_order", 1); put("isActive", 1); put("registeredAt", 1_000L)
        })
        db.insert("alarm_events", null, ContentValues().apply {
            put("id", 1); put("date", "2026-03-14"); put("stage1Success", 1); put("stage2Success", 1)
            put("stage2TimeSeconds", 310); put("nuclearTriggered", 0); put("morningGoal", "PROJECT")
            put("timestamp", 1_000L)
        })
        db.insert("pending_shopping_writes", null, ContentValues().apply {
            put("listId", "invented-list.md"); put("verb", "ADD"); put("text", "Oat milk")
            putNull("line"); put("createdAt", 1_000L); put("failed", 0)
        })
        db.insert("pending_chalkboard_writes", null, ContentValues().apply {
            put("verb", "ADD"); put("text", "Oil the gate"); putNull("line"); put("createdAt", 1_000L); put("failed", 0)
        })
        db.insert("pending_inbox_writes", null, ContentValues().apply {
            put("text", "A passing thought"); put("createdAt", 1_000L); put("failed", 1)
        })
    }

    /** Opened as AppDatabase.getInstance opens it, with nothing allowed to fall back. */
    private fun upgrade(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
            .also { opened = it }

    @Test
    fun `upgrading to 9 passes Room's schema check`() {
        val db = upgrade()

        assertEquals(9, db.openHelper.writableDatabase.version)
    }

    @Test
    fun `what was on the phone survives the upgrade`() = runBlocking {
        val db = upgrade()

        val tag = db.nfcTagDao().getAll().single()
        assertEquals("04:AA:BB:CC", tag.tagId)
        assertEquals("Invented shelf", tag.location)

        val alarm = db.openHelper.writableDatabase.query("SELECT alarmTime, stage1Volume FROM alarm_config")
        assertTrue(alarm.moveToFirst())
        assertEquals(390, alarm.getInt(0))
        assertEquals(80, alarm.getInt(1))
        alarm.close()

        val events = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM alarm_events")
        events.moveToFirst()
        assertEquals(1, events.getInt(0))
        events.close()
    }

    @Test
    fun `writes queued before the upgrade still replay after it`() = runBlocking {
        val db = upgrade()

        assertEquals("Oat milk", db.pendingShoppingWriteDao().getPending().single().text)
        assertEquals("Oil the gate", db.pendingChalkboardWriteDao().getPending().single().text)
        assertEquals("A passing thought", db.pendingInboxWriteDao().observeAll().first().single().text)
    }

    @Test
    fun `the note queue starts empty and holds one row per note`() = runBlocking {
        val dao = upgrade().pendingNoteWriteDao()
        assertEquals(0, dao.countPending())

        dao.put(PendingNoteWrite(noteId = "n", verb = NoteVerb.SAVE.name, text = "One", createdAt = 1))
        dao.put(PendingNoteWrite(noteId = "n", verb = NoteVerb.SAVE.name, text = "Two", createdAt = 2))

        assertEquals("Two", dao.find("n")?.text)
        assertEquals(1, dao.countPending())
    }

    private companion object {
        const val DB_NAME = "migration_test.db"
    }
}
