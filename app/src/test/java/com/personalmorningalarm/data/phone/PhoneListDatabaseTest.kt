package com.personalmorningalarm.data.phone

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The phone-list database as a phone will have it: built from the committed 1.json,
 * seeded, then opened the way the app opens it, so the migrations run and Room's
 * schema check compares the result with 2.json, as on the device. The way
 * [com.personalmorningalarm.data.MigrationTest] works for AppDatabase.
 * On the JVM, because instrumented tests never run against the A32 (see CLAUDE.md).
 */
@RunWith(RobolectricTestRunner::class)
class PhoneListDatabaseTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbFile: File = context.getDatabasePath(PhoneListDatabase.DB_NAME)
    private var opened: PhoneListDatabase? = null

    @Before
    fun setUp() {
        dbFile.parentFile?.mkdirs()
        context.deleteDatabase(PhoneListDatabase.DB_NAME)
        val old = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        createFromSchema(old, version = 1)
        seed(old)
        old.close()
    }

    @After
    fun tearDown() {
        opened?.close()
        context.deleteDatabase(PhoneListDatabase.DB_NAME)
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
        val path = "schemas/com.personalmorningalarm.data.phone.PhoneListDatabase/$version.json"
        return listOf(File(path), File("app/$path")).first { it.exists() }
    }

    private fun seed(db: SQLiteDatabase) {
        db.insert("phone_lists", null, ContentValues().apply {
            put("id", "invented-list"); put("kind", "SHOPPING"); put("title", "Camping"); put("createdAt", 1_000L)
        })
        db.insert("phone_list_items", null, ContentValues().apply {
            put("listId", "invented-list"); put("text", "Tent pegs"); put("addedOn", 20_000L); putNull("tickedOn")
        })
        db.insert("phone_notes", null, ContentValues().apply {
            put("id", "invented-note"); put("text", "Boiler questions"); put("updatedAt", 1_000L)
        })
    }

    /** Opened as PhoneListDatabase.getInstance opens it, with nothing allowed to fall back. */
    private fun open(): PhoneListDatabase =
        Room.databaseBuilder(context, PhoneListDatabase::class.java, PhoneListDatabase.DB_NAME)
            .addMigrations(*PhoneListDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
            .also { opened = it }

    @Test
    fun `upgrading to 2 passes Room's schema check, and what was kept is still there`() = runBlocking {
        val db = open()

        assertEquals(2, db.openHelper.writableDatabase.version)
        assertEquals(listOf("Tent pegs"), db.dao().observeItems("invented-list", today = 0).first().map { it.text })
        assertEquals("Boiler questions", db.dao().note("invented-note")?.text)
    }

    @Test
    fun `deleting a list deletes its items, with foreign keys on as Room opens it`() = runBlocking {
        val db = open()

        db.dao().deleteList("invented-list")

        assertEquals(emptyList<PhoneListItem>(), db.dao().allItems("invented-list"))
    }

    @Test
    fun `after the upgrade a list can be moved, and a moving list is hidden from the menu`() = runBlocking {
        val db = open()
        assertEquals(emptyList<ListMove>(), db.dao().moves())

        db.dao().insertMove(
            ListMove("invented-list", MoveDirection.SHARE.name, "Camping", hubId = "6-life/shopping/camping.md", startedAt = 1)
        )

        assertEquals(emptyList<PhoneListSummary>(), db.dao().observeSummaries("SHOPPING", today = 0).first())
        db.dao().finishShare("invented-list")
        assertEquals(emptyList<ListMove>(), db.dao().moves())
        assertEquals(emptyList<PhoneListItem>(), db.dao().allItems("invented-list"))
    }
}
