package com.personalmorningalarm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.personalmorningalarm.data.dao.AlarmConfigDao
import com.personalmorningalarm.data.dao.AlarmEventDao
import com.personalmorningalarm.data.dao.BundledQuoteDao
import com.personalmorningalarm.data.dao.ContentToggleDao
import com.personalmorningalarm.data.dao.NfcTagDao
import com.personalmorningalarm.data.dao.PendingChalkboardWriteDao
import com.personalmorningalarm.data.dao.PendingInboxWriteDao
import com.personalmorningalarm.data.dao.PendingNoteWriteDao
import com.personalmorningalarm.data.dao.PendingShoppingWriteDao
import com.personalmorningalarm.data.dao.StretchExerciseDao
import com.personalmorningalarm.data.dao.StretchRoutineDao
import com.personalmorningalarm.data.entity.AlarmConfig
import com.personalmorningalarm.data.entity.AlarmEvent
import com.personalmorningalarm.data.entity.BundledQuote
import com.personalmorningalarm.data.entity.ContentToggle
import com.personalmorningalarm.data.entity.NfcTag
import com.personalmorningalarm.data.entity.PendingChalkboardWrite
import com.personalmorningalarm.data.entity.PendingInboxWrite
import com.personalmorningalarm.data.entity.PendingNoteWrite
import com.personalmorningalarm.data.entity.PendingShoppingWrite
import com.personalmorningalarm.data.entity.StretchExercise
import com.personalmorningalarm.data.entity.StretchRoutine

@Database(
    entities = [
        AlarmConfig::class,
        AlarmEvent::class,
        NfcTag::class,
        ContentToggle::class,
        BundledQuote::class,
        StretchRoutine::class,
        StretchExercise::class,
        PendingChalkboardWrite::class,
        PendingShoppingWrite::class,
        PendingInboxWrite::class,
        PendingNoteWrite::class
    ],
    version = 9,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun alarmConfigDao(): AlarmConfigDao
    abstract fun alarmEventDao(): AlarmEventDao
    abstract fun nfcTagDao(): NfcTagDao
    abstract fun contentToggleDao(): ContentToggleDao
    abstract fun bundledQuoteDao(): BundledQuoteDao
    abstract fun stretchRoutineDao(): StretchRoutineDao
    abstract fun stretchExerciseDao(): StretchExerciseDao
    abstract fun pendingChalkboardWriteDao(): PendingChalkboardWriteDao
    abstract fun pendingShoppingWriteDao(): PendingShoppingWriteDao
    abstract fun pendingInboxWriteDao(): PendingInboxWriteDao
    abstract fun pendingNoteWriteDao(): PendingNoteWriteDao

    companion object {
        private const val DB_NAME = "personal_morning_alarm.db"

        // v2: stretch duration on content_toggles. Non-destructive so registered
        // NFC tags and seeded quotes survive the upgrade.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE content_toggles " +
                        "ADD COLUMN duration_minutes INTEGER NOT NULL DEFAULT 5"
                )
            }
        }

        // v3: configurable NFC checkpoint count on alarm_config. Non-destructive
        // so the saved alarm, tags, and quotes survive the upgrade.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE alarm_config " +
                        "ADD COLUMN sequenceLength INTEGER NOT NULL DEFAULT 5"
                )
            }
        }

        // v4: stretch routines/exercises + per-goal routine mapping on alarm_config.
        // Non-destructive. CREATE statements are copied verbatim from the exported
        // Room schema (app/schemas/.../4.json) so they match what Room validates.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stretch_routines` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`isActive` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stretch_exercises` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `routineId` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, " +
                        "`instructions` TEXT NOT NULL, `displayOrder` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`routineId`) REFERENCES `stretch_routines`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_stretch_exercises_routineId` " +
                        "ON `stretch_exercises` (`routineId`)"
                )
                db.execSQL("ALTER TABLE alarm_config ADD COLUMN matchRoutineToGoal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alarm_config ADD COLUMN exerciseRoutineId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alarm_config ADD COLUMN projectRoutineId INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v5: Stage 1/nuclear sound selection, Stage 1 volume, vibration toggle on
        // alarm_config. Non-destructive ALTERs with sensible defaults.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarm_config ADD COLUMN stage1SoundId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE alarm_config ADD COLUMN nuclearSoundId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE alarm_config ADD COLUMN stage1Volume INTEGER NOT NULL DEFAULT 100")
                db.execSQL("ALTER TABLE alarm_config ADD COLUMN vibrationEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        // v6: offline store-and-forward queue for rolling to-do writes.
        // Non-destructive; CREATE copied verbatim from the exported 6.json.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_chalkboard_writes` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `verb` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, `line` TEXT, `createdAt` INTEGER NOT NULL, " +
                        "`failed` INTEGER NOT NULL)"
                )
            }
        }

        // v7: offline store-and-forward queue for shopping-list writes, one shared
        // FIFO outbox across every list (mirrors MIGRATION_5_6's chalkboard queue).
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_shopping_writes` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `listId` TEXT NOT NULL, " +
                        "`verb` TEXT NOT NULL, `text` TEXT NOT NULL, `line` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, `failed` INTEGER NOT NULL)"
                )
            }
        }

        // v8: offline store-and-forward queue for inbox captures. Non-destructive;
        // no listId or line — a capture creates a file rather than targeting one.
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_inbox_writes` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `failed` INTEGER NOT NULL)"
                )
            }
        }

        // v9: offline store-and-forward queue for notes. Non-destructive. The unique
        // index is what keeps it to one row per note.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_note_writes` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` TEXT NOT NULL, " +
                        "`verb` TEXT NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`failed` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_note_writes_noteId` " +
                        "ON `pending_note_writes` (`noteId`)"
                )
            }
        }

        /** Every migration, in order: what the app opens with, and what the migration test opens with. */
        internal val MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9
        )

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                ).addMigrations(*MIGRATIONS)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
