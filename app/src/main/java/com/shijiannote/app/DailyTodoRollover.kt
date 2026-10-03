package com.shijiannote.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager

/** Kept for already-enqueued v9 workers. Smart views do not move or delete tasks. */
object DailyTodoRollover {
    suspend fun synchronize(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("daily_todo_rollover")
    }
}
class DailyTodoRolloverWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result { DailyTodoRollover.synchronize(applicationContext); return Result.success() }
}
object DailyTodoRolloverScheduler {
    fun ensureScheduled(context: Context) { WorkManager.getInstance(context).cancelUniqueWork("daily_todo_rollover") }
}
