package com.shijiannote.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ScheduleEvent::class, TodoBoard::class, TodoItem::class, DailyTodoPreferences::class, DiaryEntry::class, MemoryCategory::class, MemoryEntry::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

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
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "shijian_note.db")
                .addMigrations(migration1To2, migration2To3, migration3To4, migration4To5)
                .build()
                .also { instance = it }
        }
    }
}
