package com.shijiannote.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ScheduleEvent::class, TodoBoard::class, TodoItem::class, DiaryEntry::class, MemoryCategory::class, MemoryEntry::class],
    version = 2,
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

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "shijian_note.db")
                .addMigrations(migration1To2)
                .build()
                .also { instance = it }
        }
    }
}
