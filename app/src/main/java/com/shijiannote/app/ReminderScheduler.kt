package com.shijiannote.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.shijiannote.app.data.AppDatabase
import com.shijiannote.app.data.ScheduleEvent
import com.shijiannote.app.data.TodoBoard
import com.shijiannote.app.data.TodoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

object ReminderScheduler {
    private const val CHANNEL_ID = "schedule_reminders"
    private const val TODO_OFFSET = 1_000_000
    private const val TODO_BOARD_OFFSET = 2_000_000

    const val RULE_WEEKDAYS = "WEEKDAYS"
    const val RULE_WEEKENDS = "WEEKENDS"
    const val RULE_DAILY = "DAILY"
    const val RULE_EVERY_3_DAYS = "EVERY_3_DAYS"
    const val RULE_WEEKLY = "WEEKLY"
    const val RULE_SEMI_MONTHLY = "SEMI_MONTHLY"
    const val RULE_MONTHLY = "MONTHLY"
    const val RULE_YEARLY = "YEARLY"
    const val RULE_CUSTOM_DAYS = "CUSTOM_DAYS"

    fun schedule(context: Context, event: ScheduleEvent) {
        if (event.reminderDays == 0 && event.reminderHours == 0 && event.reminderMinutes == 0) return
        val remindAt = event.eventAt - event.reminderDays * 86_400_000L - event.reminderHours * 3_600_000L - event.reminderMinutes * 60_000L
        if (remindAt <= System.currentTimeMillis()) return
        requestNotificationPermission(context)
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.TITLE, event.title)
            .putExtra(ReminderReceiver.NOTE, event.note)
            .putExtra(ReminderReceiver.ID, event.id)
            .putExtra(ReminderReceiver.TYPE, ReminderReceiver.SCHEDULE)
        scheduleAlarm(context, event.id.toInt(), intent, remindAt)
    }

    fun scheduleTodo(context: Context, item: TodoItem) {
        val taskTime = item.reminderAt ?: return
        val remindAt = taskTime - item.reminderHours * 3_600_000L - item.reminderMinutes * 60_000L
        if (remindAt <= System.currentTimeMillis()) return
        requestNotificationPermission(context)
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.TITLE, item.text.lineSequence().firstOrNull().orEmpty())
            .putExtra(ReminderReceiver.NOTE, "待办提醒")
            .putExtra(ReminderReceiver.ID, item.id)
            .putExtra(ReminderReceiver.TYPE, ReminderReceiver.TODO)
        scheduleAlarm(context, TODO_OFFSET + item.id.toInt(), intent, remindAt)
    }

    /** Schedules one alarm. For repeats, the receiver schedules the next occurrence after it fires. */
    fun scheduleTodoBoard(context: Context, board: TodoBoard, afterTime: Long = System.currentTimeMillis()) {
        if (board.reminderAt == null) return
        val deadlineTime = board.dueDate
        val remindAt = if (board.reminderRule == null) {
            val deadline = deadlineTime ?: return
            deadline - board.reminderDays * 86_400_000L - board.reminderHours * 3_600_000L - board.reminderMinutes * 60_000L
        } else {
            nextRepeatAt(board, afterTime) ?: return
        }
        if (remindAt <= afterTime || deadlineTime?.let { remindAt >= it } == true) return
        requestNotificationPermission(context)
        val note = if (board.reminderRule == null) "待办框截止提醒" else "待办框重复提醒"
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.TITLE, board.summary)
            .putExtra(ReminderReceiver.NOTE, note)
            .putExtra(ReminderReceiver.ID, board.id)
            .putExtra(ReminderReceiver.TYPE, ReminderReceiver.TODO_BOARD)
        scheduleAlarm(context, TODO_BOARD_OFFSET + board.id.toInt(), intent, remindAt)
    }

    private fun nextRepeatAt(board: TodoBoard, afterTime: Long): Long? {
        val baseTime = board.reminderBaseAt ?: return null
        val deadlineTime = board.dueDate
        val rule = board.reminderRule ?: return null
        val calendar = Calendar.getInstance().apply { timeInMillis = baseTime }
        var guard = 0
        when (rule) {
            RULE_WEEKDAYS, RULE_WEEKENDS -> {
                while (calendar.timeInMillis <= afterTime || !matchesDayType(calendar, rule)) {
                    calendar.add(Calendar.DATE, 1)
                    if (++guard > 10_000) return null
                }
            }
            else -> {
                while (calendar.timeInMillis <= afterTime) {
                    when (rule) {
                        RULE_DAILY -> calendar.add(Calendar.DATE, 1)
                        RULE_EVERY_3_DAYS -> calendar.add(Calendar.DATE, 3)
                        RULE_WEEKLY -> calendar.add(Calendar.DATE, 7)
                        RULE_SEMI_MONTHLY -> calendar.add(Calendar.DATE, 15)
                        RULE_MONTHLY -> calendar.add(Calendar.MONTH, 1)
                        RULE_YEARLY -> calendar.add(Calendar.YEAR, 1)
                        RULE_CUSTOM_DAYS -> calendar.add(Calendar.DATE, board.reminderCustomDays.coerceAtLeast(1))
                        else -> return null
                    }
                    if (++guard > 10_000) return null
                }
            }
        }
        return calendar.timeInMillis.takeIf { deadlineTime == null || it < deadlineTime }
    }

    private fun matchesDayType(calendar: Calendar, rule: String): Boolean {
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        return if (rule == RULE_WEEKDAYS) day in Calendar.MONDAY..Calendar.FRIDAY else day == Calendar.SATURDAY || day == Calendar.SUNDAY
    }

    fun cancelTodoBoard(context: Context, boardId: Long) {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(context, TODO_BOARD_OFFSET + boardId.toInt(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pending != null) context.getSystemService(AlarmManager::class.java).cancel(pending)
    }

    fun cancelTodo(context: Context, itemId: Long) {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(context, TODO_OFFSET + itemId.toInt(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pending != null) context.getSystemService(AlarmManager::class.java).cancel(pending)
    }

    fun cancel(context: Context, eventId: Long) {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(context, eventId.toInt(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pending != null) context.getSystemService(AlarmManager::class.java).cancel(pending)
    }

    private fun scheduleAlarm(context: Context, requestCode: Int, intent: Intent, at: Long) {
        val pending = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val manager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    private fun requestNotificationPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context is Activity && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            context.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4101)
        }
    }

    fun show(context: Context, title: String, note: String, id: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "时间表提醒", NotificationManager.IMPORTANCE_HIGH))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(note.ifBlank { "时间表任务提醒" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id.toInt(), notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ID, 0)
        val type = intent.getStringExtra(TYPE) ?: SCHEDULE
        val notificationId = when (type) {
            TODO -> 1_000_000 + id
            TODO_BOARD -> 2_000_000 + id
            else -> id
        }
        ReminderScheduler.show(context, intent.getStringExtra(TITLE).orEmpty(), intent.getStringExtra(NOTE).orEmpty(), notificationId)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.get(context).appDao()
                when (type) {
                    TODO -> dao.markTodoReminded(id)
                    TODO_BOARD -> {
                        val board = dao.getTodoBoard(id)
                        if (board?.reminderRule == null) dao.markTodoBoardReminded(id)
                        else ReminderScheduler.scheduleTodoBoard(context, board, System.currentTimeMillis())
                    }
                    SCHEDULE -> dao.markScheduleReminded(id)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val TITLE = "title"
        const val NOTE = "note"
        const val ID = "id"
        const val TYPE = "type"
        const val SCHEDULE = "schedule"
        const val TODO = "todo"
        const val TODO_BOARD = "todo_board"
    }
}