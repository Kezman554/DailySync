package com.personalmorningalarm.data.phone

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Lists and notes kept on the phone, in a database file of their own beside the
 * alarm's ([com.personalmorningalarm.data.AppDatabase]).
 *
 * A separate file for two reasons. The alarm database holds registered NFC tags and
 * alarm history, and adding phone lists to it would have meant a migration on it for
 * no gain. And the backup rules (res/xml/data_extraction_rules.xml, backup_rules.xml)
 * can name this file outright, which a test checks.
 *
 * No table here has anything to do with Alfred: nothing in this file is ever sent.
 */
@Database(
    entities = [PhoneList::class, PhoneListItem::class, PhoneNote::class],
    version = 1,
    exportSchema = true
)
abstract class PhoneListDatabase : RoomDatabase() {

    abstract fun dao(): PhoneListDao

    companion object {
        /** Named in the backup rules: change both together. */
        const val DB_NAME = "phone_lists.db"

        /** Every migration, in order. None yet: version 1. */
        internal val MIGRATIONS = emptyArray<androidx.room.migration.Migration>()

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
