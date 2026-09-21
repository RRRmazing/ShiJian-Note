package com.shijiannote.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shijiannote.app.data.AppDatabase
import com.shijiannote.app.data.DailyTodoPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Daily state is checkpointed in Room, so this is correct even when Android delays the worker,
 * the process is killed, or the phone is powered off overnight.
 */
object DailyTodoRollover {
    suspend fun synchronize(context: Context) {
        val dao = AppDatabase.get(context).appDao()
        val today = LocalDate.now()
        val todayMillis = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val preferences = dao.getDailyTodoPreferences()
        if (preferences == null || preferences.lastRolloverDay == 0L) {
            dao.saveDailyTodoPreferences((preferences ?: DailyTodoPreferences()).copy(lastRolloverDay = todayMillis))
            dao.prepareDailyPages(todayMillis)
            return
        }
        var day = preferences.lastRolloverDay
        while (day < todayMillis) {
            val date = Instant.ofEpochMilli(day).atZone(ZoneId.systemDefault()).toLocalDate()
            dao.rollDailyPages("今天 · ${date.year}.${date.monthValue}.${date.dayOfMonth}")
            day = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            dao.saveDailyTodoPreferences(preferences.copy(lastRolloverDay = day))
        }
        dao.prepareDailyPages(todayMillis)
    }
}

class DailyTodoRolloverWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        DailyTodoRollover.synchronize(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

object DailyTodoRolloverScheduler {
    private const val UNIQUE_WORK = "daily_todo_rollover"

    fun ensureScheduled(context: Context) {
        val work = PeriodicWorkRequestBuilder<DailyTodoRolloverWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, work)
    }
}