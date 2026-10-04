package com.shijiannote.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ScheduleEvent::class, TodoBoard::class, TodoItem::class, DailyTodoPreferences::class, DiaryEntry::class, MemoryCategory::class, MemoryEntry::class, NoteNode::class, NoteVersion::class, LegacyTodoAlarm::class],
    version = 12,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE schedule_events ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE schedule_events ADD COLUMN reminderTriggered INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE todo_boards ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val migration2To3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE todo_boards ADD COLUMN boardType TEXT NOT NULL DEFAULT 'LIST'")
                database.execSQL("ALTER TABLE todo_items ADD COLUMN important INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE todo_items ADD COLUMN reminderAt INTEGER")
                database.execSQL("ALTER TABLE todo_items ADD COLUMN reminderHours INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE todo_items ADD COLUMN reminderMinutes INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE todo_items ADD COLUMN reminderTriggered INTEGER NOT NULL DEFAULT 0")
                database.execSQL("CREATE TABLE IF NOT EXISTS daily_todo_preferences (id INTEGER NOT NULL, showToday INTEGER NOT NULL DEFAULT 1, showTomorrow INTEGER NOT NULL DEFAULT 1, lastRolloverDay INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(id))")
            }
        }
        private val migration3To4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE todo_boards ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val migration4To5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE memory_categories ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE memory_entries ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val migration5To6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE todo_boards ADD COLUMN reminderAt INTEGER")
                database.execSQL("ALTER TABLE todo_boards ADD COLUMN reminderDays INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE todo_boards ADD COLUMN reminderHours INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE todo_boards ADD COLUMN reminderMinutes INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val migration6To7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE todo_boards ADD COLUMN reminderTriggered INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val migration7To8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE todo_boards ADD COLUMN reminderRule TEXT")
                database.execSQL("ALTER TABLE todo_boards ADD COLUMN reminderBaseAt INTEGER")
                database.execSQL("ALTER TABLE todo_boards ADD COLUMN reminderCustomDays INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val migration8To9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE schedule_events ADD COLUMN reminderEnabled INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE schedule_events SET reminderEnabled = 1 WHERE reminderDays > 0 OR reminderHours > 0 OR reminderMinutes > 0")
            }
        }

        internal val migration9To10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS note_nodes (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, parentId TEXT, title TEXT NOT NULL, text TEXT NOT NULL, document TEXT NOT NULL, day INTEGER, position INTEGER NOT NULL, pinned INTEGER NOT NULL, favorite INTEGER NOT NULL, tags TEXT NOT NULL, mood TEXT NOT NULL, imageDisplay TEXT NOT NULL, imageStorage TEXT NOT NULL, deletedAt INTEGER, deleteGroup TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_note_nodes_parentId ON note_nodes(parentId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_note_nodes_day ON note_nodes(day)")
                database.execSQL("CREATE TABLE IF NOT EXISTS note_versions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, nodeId TEXT NOT NULL, snapshot TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_note_versions_nodeId ON note_versions(nodeId)")
                database.execSQL("INSERT INTO note_nodes SELECT 'c' || id, 'folder', NULL, name, '', '', NULL, position, 0, 0, '', '', 'inherit', 'inherit', NULL, NULL, createdAt, createdAt FROM memory_categories")
                database.execSQL("INSERT INTO note_nodes SELECT 'm' || id, 'memory', 'c' || categoryId, title, content, '', NULL, position, 0, 0, '', '', 'inherit', 'inherit', NULL, NULL, createdAt, createdAt FROM memory_entries")
                database.execSQL("INSERT INTO note_nodes SELECT 'd' || id, 'diary', NULL, summary, content, '', day, 0, 0, 0, '', '', 'inherit', 'inherit', NULL, NULL, updatedAt, updatedAt FROM diary_entries")
                database.execSQL("ALTER TABLE todo_boards ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE todo_boards ADD COLUMN deletedAt INTEGER")
                database.execSQL("ALTER TABLE schedule_events ADD COLUMN deletedAt INTEGER")
                database.execSQL("ALTER TABLE todo_items ADD COLUMN plannedDay INTEGER")
                database.execSQL("ALTER TABLE todo_items ADD COLUMN dueAt INTEGER")
                database.execSQL("ALTER TABLE todo_items ADD COLUMN repeatDays INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE todo_items ADD COLUMN repeatSpawned INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE todo_items ADD COLUMN deletedAt INTEGER")
                // Preserve old daily-page memberships without moving or dropping the actual tasks.
                val date = java.time.LocalDate.now()
                val zone = java.time.ZoneId.systemDefault()
                val today = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val tomorrow = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val yesterday = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                database.execSQL("UPDATE todo_items SET plannedDay = $today WHERE boardId IN (SELECT id FROM todo_boards WHERE boardType = 'TODAY' AND archived = 0)")
                database.execSQL("UPDATE todo_items SET plannedDay = $tomorrow WHERE boardId IN (SELECT id FROM todo_boards WHERE boardType = 'TOMORROW' AND archived = 0)")
                database.execSQL("UPDATE todo_items SET plannedDay = $yesterday WHERE boardId IN (SELECT id FROM todo_boards WHERE boardType = 'OVERDUE' AND archived = 0)")
                database.execSQL("UPDATE todo_boards SET boardType = 'LIST' WHERE archived = 0")
            }
        }

        internal val migration10To11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE note_nodes ADD COLUMN forceChildren INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE schedule_events ADD COLUMN important INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val migration11To12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS legacy_todo_alarms (kind TEXT NOT NULL, legacyId INTEGER NOT NULL, PRIMARY KEY(kind, legacyId))")
                database.execSQL("INSERT OR IGNORE INTO legacy_todo_alarms SELECT 'board', id FROM todo_boards")
                database.execSQL("INSERT OR IGNORE INTO legacy_todo_alarms SELECT 'item', id FROM todo_items")
                database.execSQL("DELETE FROM todo_items")
                database.execSQL("DELETE FROM todo_boards")
                database.execSQL("DELETE FROM daily_todo_preferences")
                database.execSQL("ALTER TABLE todo_boards ADD COLUMN timeMode TEXT NOT NULL DEFAULT 'UNIFIED'")
                database.execSQL("ALTER TABLE todo_boards ADD COLUMN reminderSkipAt INTEGER")
                database.execSQL("ALTER TABLE todo_items ADD COLUMN reminderRule TEXT")
                database.execSQL("ALTER TABLE todo_items ADD COLUMN reminderBaseAt INTEGER")
                database.execSQL("ALTER TABLE todo_items ADD COLUMN reminderCustomDays INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE todo_items ADD COLUMN reminderSkipAt INTEGER")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            val app = context.applicationContext
            instance ?: Room.databaseBuilder(app, AppDatabase::class.java, "shijian_note.db")
                .addMigrations(migration1To2, migration2To3, migration3To4, migration4To5, migration5To6, migration6To7, migration7To8, migration8To9, migration9To10, migration10To11, migration11To12)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.query("SELECT kind, legacyId FROM legacy_todo_alarms").use { rows ->
                            while (rows.moveToNext()) {
                                val id = rows.getLong(1)
                                if (rows.getString(0) == "board") com.shijiannote.app.ReminderScheduler.cancelTodoBoard(app, id)
                                else com.shijiannote.app.ReminderScheduler.cancelTodo(app, id)
                            }
                        }
                        db.execSQL("DELETE FROM legacy_todo_alarms")
                    }
                })
                .build()
                .also { instance = it }
        }
    }
}
