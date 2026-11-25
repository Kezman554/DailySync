package com.personalmorningalarm.data.phone

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Lists and notes kept on the phone, in a database file of their own beside the
 * alarm's ([com.personalmorningalarm.data.AppDatabase]).
 *
 * A separate file for two reasons. The alarm database holds registered NFC tags and
 * alarm history, and adding phone lists to it would have meant a migration on it for
 * no gain. And the backup rules (res/xml/data_extraction_rules.xml, backup_rules.xml)
 * can name this file outright, which a test checks.
 *
 * Nothing here is sent by itself. The one way a phone list reaches the hub is the
 * user sharing it, which uploads it and then deletes it here (list_moves records the
 * move while it's under way; see ShoppingShare).
 */
@Database(
    entities = [PhoneList::class, PhoneListItem::class, PhoneNote::class, ListMove::class],
    version = 2,
    exportSchema = true
)
abstract class PhoneListDatabase : RoomDatabase() {

    abstract fun dao(): PhoneListDao

    companion object {
        /** Named in the backup rules: change both together. */
        const val DB_NAME = "phone_lists.db"

        // v2: list_moves, a list part-way between the phone and the hub. Nothing
        // existing changes; CREATE copied from the exported 2.json.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `list_moves` (`phoneListId` TEXT NOT NULL, " +
                        "`direction` TEXT NOT NULL, `title` TEXT NOT NULL, `hubId` TEXT, " +
                        "`hubIdsBefore` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`phoneListId`))"
                )
            }
        }

        /** Every migration, in order. */
        internal val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        @Volatile
        private var INSTANCE: PhoneListDatabase? = null

        fun getInstance(context: Context): PhoneListDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhoneListDatabase::class.java,
                    DB_NAME
                ).addMigrations(*MIGRATIONS)
                    .build().also { INSTANCE = it }
            }
    }
}
