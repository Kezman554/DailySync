package com.personalmorningalarm.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.personalmorningalarm.data.entity.NoteVerb
import com.personalmorningalarm.data.entity.PendingInboxWrite
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
 * schema (8.json by default), seeded, then opened the way the app opens it, so Room's
 * own schema check runs as it does on the device: a table that doesn't match 12.json
 * fails the open.
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
    fun setUp() = startFrom(version = 8)

    /** A seeded database at [version], replacing whatever the test had. */
    private fun startFrom(version: Int) {
        dbFile.parentFile?.mkdirs()
        context.deleteDatabase(DB_NAME)
        val old = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        createFromSchema(old, version)
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
        db.insert("pending_inbox_writes", null, ContentValues().apply {
            put("text", "Another passing thought"); put("createdAt", 2_000L); put("failed", 0)
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
    fun `upgrading to 12 passes Room's schema check`() {
        val db = upgrade()

        assertEquals(12, db.openHelper.writableDatabase.version)
    }

    @Test
    fun `from 11, the A32's version, tags, alarm events and settings are untouched`() = runBlocking {
        startFrom(version = 11)
        val before = snapshot("SELECT * FROM nfc_tags") + snapshot("SELECT * FROM alarm_events") +
            snapshot("SELECT * FROM alarm_config")
        val db = upgrade()

        assertEquals(12, db.openHelper.writableDatabase.version)
        val sql = db.openHelper.writableDatabase
        val after = snapshot(sql, "SELECT * FROM nfc_tags") + snapshot(sql, "SELECT * FROM alarm_events") +
            snapshot(sql, "SELECT * FROM alarm_config")
        assertEquals(before, after)
        assertEquals("04:AA:BB:CC", db.nfcTagDao().getAll().single().tagId)
    }

    @Test
    fun `from 11, every queued add gets its own key and every capture keeps its id`() = runBlocking {
        startFrom(version = 11)
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            old.insert("pending_chalkboard_writes", null, ContentValues().apply {
                put("verb", "TICK"); put("text", "Oil the gate"); put("line", "- [ ] Oil the gate")
                put("createdAt", 2_000L); put("failed", 0)
            })
            old.insert("pending_shopping_writes", null, ContentValues().apply {
                put("listId", "invented-list.md"); put("verb", "ADD"); put("text", "Bread")
                putNull("line"); put("createdAt", 2_000L); put("failed", 0)
            })
            // Sent once under v11 with no answer: the column goes, the row stays.
            old.execSQL("UPDATE pending_inbox_writes SET attemptedAt = 5000 WHERE text = 'Another passing thought'")
        }
        val idsBefore = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { old ->
            old.rawQuery("SELECT id FROM pending_inbox_writes ORDER BY id", null).use { c ->
                generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList()
            }
        }
        val db = upgrade()

        val chalkboard = db.pendingChalkboardWriteDao().getPending()
        assertEquals(listOf("ADD", "TICK"), chalkboard.map { it.verb })
        assertTrue(UUID_SHAPE.matches(chalkboard[0].idempotencyKey!!))
        assertEquals(null, chalkboard[1].idempotencyKey)

        val shopping = db.pendingShoppingWriteDao().getPending()
        assertEquals(listOf("Oat milk", "Bread"), shopping.map { it.text })
        shopping.forEach { assertTrue(UUID_SHAPE.matches(it.idempotencyKey!!)) }

        val captures = db.pendingInboxWriteDao().observeAll().first()
        assertEquals(idsBefore, captures.map { it.id })
        assertEquals(listOf(true, false), captures.map { it.failed })
        captures.forEach { assertTrue(UUID_SHAPE.matches(it.idempotencyKey)) }

        val keys = chalkboard.mapNotNull { it.idempotencyKey } + shopping.mapNotNull { it.idempotencyKey } +
            captures.map { it.idempotencyKey }
        assertEquals(keys.size, keys.toSet().size)

        // A capture made after the upgrade doesn't reuse a migrated row's id.
        val next = db.pendingInboxWriteDao().insert(PendingInboxWrite(text = "New", createdAt = 3, idempotencyKey = "k"))
        assertTrue(next > idsBefore.max())
    }

    /** Every row [query] returns, as text, from the database file before it's opened by Room. */
    private fun snapshot(query: String): List<String> =
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { snapshotOf(it.rawQuery(query, null)) }

    private fun snapshot(db: androidx.sqlite.db.SupportSQLiteDatabase, query: String): List<String> =
        snapshotOf(db.query(query))

    private fun snapshotOf(cursor: android.database.Cursor): List<String> = cursor.use { c ->
        generateSequence {
            if (!c.moveToNext()) null
            else (0 until c.columnCount).joinToString("|") { "${c.getColumnName(it)}=${c.getString(it)}" }
        }.toList()
    }

    @Test
    fun `a chalkboard write queued before there were two lists replays to Nick's`() = runBlocking {
        val db = upgrade()

        assertEquals("nick", db.pendingChalkboardWriteDao().getPending().single().list)
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
        val captures = db.pendingInboxWriteDao().observeAll().first()
        assertEquals(listOf("A passing thought", "Another passing thought"), captures.map { it.text })
        assertEquals(2, captures.map { it.idempotencyKey }.toSet().size)
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
        val UUID_SHAPE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
