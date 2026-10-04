package com.shijiannote.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shijiannote.app.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Read acknowledgements and notifications are independent and survive process restarts. */
object DiaryRecallReminder {
    const val ACTION_OPEN_RECALL = "com.shijiannote.app.OPEN_DIARY_RECALL"
    const val EXTRA_RECALL_DATE = "diary_recall_date"
    private const val WORK_NAME = "daily_diary_recall"
    private const val CHANNEL_ID = "diary_recall"
    private const val NOTIFICATION_ID = 3_000_001
    private const val ENABLED = "diaryRecallEnabled"
    private const val AVAILABLE_DATE = "diaryRecallAvailableDate"
    private const val CALENDAR_READ_DATE = "diaryRecallCalendarReadDate"
    private const val RECALL_READ_DATE = "diaryRecallReadDate"
    private const val NOTIFIED_DATE = "diaryRecallNotifiedDate"
    private val refreshGate = Mutex()
    private val notificationGate = Any()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun preferences(context: Context): SharedPreferences = appPreferences(context.applicationContext)
    fun enabled(context: Context): Boolean = preferences(context).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        synchronized(notificationGate) {
            preferences(context).edit().putBoolean(ENABLED, enabled).apply()
            if (!enabled) cancelNotification(context)
        }
        if (enabled) refreshInBackground(context.applicationContext)
    }

    fun calendarUnread(context: Context, today: LocalDate = LocalDate.now()): Boolean {
        val prefs = preferences(context)
        return DiaryRecallRules.unread(enabled(context), prefs.getString(AVAILABLE_DATE, null), prefs.getString(CALENDAR_READ_DATE, null), today)
    }

    fun recallUnread(context: Context, today: LocalDate = LocalDate.now()): Boolean {
        val prefs = preferences(context)
        return DiaryRecallRules.unread(enabled(context), prefs.getString(AVAILABLE_DATE, null), prefs.getString(RECALL_READ_DATE, null), today)
    }

    fun markCalendarRead(context: Context, today: LocalDate = LocalDate.now()) {
        preferences(context).edit().putString(CALENDAR_READ_DATE, today.toString()).apply()
    }

    fun markRecallRead(context: Context, today: LocalDate = LocalDate.now()) {
        synchronized(notificationGate) {
            preferences(context).edit().putString(RECALL_READ_DATE, today.toString()).apply()
            cancelNotification(context)
        }
    }

    fun initialize(context: Context) {
        val app = context.applicationContext
        scheduleNextDay(app, ExistingWorkPolicy.KEEP)
        refreshInBackground(app)
    }

    private fun refreshInBackground(context: Context) {
        background.launch {
            try { refresh(context) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* The next foreground/day check retries unavailable storage. */ }
        }
    }

    suspend fun refresh(context: Context) = refreshGate.withLock {
        val app = context.applicationContext
        val entries = AppDatabase.get(app).noteDao().nodes()
        // Capture today after the database read, so a read straddling midnight uses the new date.
        val today = LocalDate.now()
        val available = DiaryRecallRules.hasPreviousYear(entries, today)
        val prefs = preferences(app)
        prefs.edit().putString(AVAILABLE_DATE, if (available) today.toString() else "").apply()
        if (!available || !enabled(app)) {
            cancelNotification(app)
            return@withLock
        }
        if (DiaryRecallRules.shouldNotify(true, prefs.getString(AVAILABLE_DATE, null), prefs.getString(NOTIFIED_DATE, null), prefs.getString(RECALL_READ_DATE, null), today) && showNotification(app, today)) {
            prefs.edit().putString(NOTIFIED_DATE, today.toString()).apply()
        }
    }

    internal fun scheduleNextDay(context: Context, policy: ExistingWorkPolicy) {
        val work = OneTimeWorkRequestBuilder<DiaryRecallWorker>()
            .setInitialDelay(DiaryRecallRules.delayUntilNextDay(System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, work)
    }

    internal fun resetClock(context: Context) {
        scheduleNextDay(context.applicationContext, ExistingWorkPolicy.REPLACE)
    }

    private fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun showNotification(context: Context, today: LocalDate): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "往年今日", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_RECALL)
            .putExtra(EXTRA_RECALL_DATE, today.toString())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle("往年今日")
            .setContentText("往年的今天有日记，来回顾一下吧")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        // Permission can change while a refresh is running; never request permission here.
        return synchronized(notificationGate) {
            val prefs = preferences(context)
            if (!DiaryRecallRules.shouldNotify(enabled(context), prefs.getString(AVAILABLE_DATE, null), prefs.getString(NOTIFIED_DATE, null), prefs.getString(RECALL_READ_DATE, null), today)) return@synchronized false
            try { manager.notify(NOTIFICATION_ID, notification); true }
            catch (_: SecurityException) { false }
        }
    }
}

class DiaryRecallWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        DiaryRecallReminder.refresh(applicationContext)
        // Append a new local-midnight check after this work; a fixed 24-hour interval drifts at DST.
        DiaryRecallReminder.scheduleNextDay(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        Result.success()
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { Result.retry() }
}

class DiaryRecallClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED)) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DiaryRecallReminder.resetClock(context)
                DiaryRecallReminder.refresh(context)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* WorkManager and the next foreground refresh retry. */
            } finally { result.finish() }
        }
    }
}
