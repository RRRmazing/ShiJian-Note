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
        if (!event.reminderEnabled || event.archived || event.deletedAt != null || event.reminderTriggered) return
        val remindAt = event.eventAt - event.reminderDays * 86_400_000L - event.reminderHours * 3_600_000L - event.reminderMinutes * 60_000L
        if (remindAt <= System.currentTimeMillis()) return
        requestNotificationPermission(context)
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.TITLE, event.title)
            .putExtra(ReminderReceiver.NOTE, event.note)
            .putExtra(ReminderReceiver.ID, event.id)
            .putExtra(ReminderReceiver.TYPE, ReminderReceiver.SCHEDULE)
            .putExtra(ReminderReceiver.AT, remindAt)
        scheduleAlarm(context, event.id.toInt(), intent, remindAt)
    }

    fun scheduleTodo(context: Context, item: TodoItem) {
        CoroutineScope(Dispatchers.IO).launch { rescheduleTodo(context, item.id) }
    }

    suspend fun rescheduleTodo(context: Context, itemId: Long, afterTime: Long = System.currentTimeMillis()) {
        val dao = AppDatabase.get(context).appDao()
        cancelTodo(context, itemId)
        val item = dao.getTodoItem(itemId) ?: return
        val board = dao.getTodoBoard(item.boardId) ?: return
        if (isUnifiedBoard(board)) rescheduleTodoBoard(context, board.id, afterTime)
        else scheduleTaskAlarm(context, item, board, afterTime)
    }

    private fun scheduleTaskAlarm(context: Context, item: TodoItem, board: TodoBoard, afterTime: Long = System.currentTimeMillis()) {
        if (isUnifiedBoard(board)) return
        val remindAt = nextTaskReminderAt(item, board, afterTime) ?: return
        requestNotificationPermission(context)
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.ID, item.id)
            .putExtra(ReminderReceiver.TYPE, ReminderReceiver.TODO)
            .putExtra(ReminderReceiver.AT, remindAt)
        scheduleAlarm(context, TODO_OFFSET + item.id.toInt(), intent, remindAt)
    }

    fun scheduleTodoBoard(context: Context, board: TodoBoard, afterTime: Long = System.currentTimeMillis()) {
        cancelTodoBoard(context, board.id)
        CoroutineScope(Dispatchers.IO).launch { rescheduleTodoBoard(context, board.id, afterTime) }
    }

    /** Always read current rows, including completion and trash state, before scheduling. */
    suspend fun rescheduleTodoBoard(context: Context, boardId: Long, afterTime: Long = System.currentTimeMillis()) {
        val dao = AppDatabase.get(context).appDao()
        val board = dao.getTodoBoard(boardId) ?: return
        val items = dao.getTodoItems(boardId)
        cancelTodoBoard(context, boardId)
        items.forEach { cancelTodo(context, it.id) }
        if (board.archived || board.deletedAt != null) return
        if (isUnifiedBoard(board)) {
            if (items.none { !it.completed && it.deletedAt == null }) return
            val remindAt = nextBoardReminderAt(board, afterTime) ?: return
            requestNotificationPermission(context)
            val intent = Intent(context, ReminderReceiver::class.java)
                .putExtra(ReminderReceiver.ID, board.id)
                .putExtra(ReminderReceiver.TYPE, ReminderReceiver.TODO_BOARD)
                .putExtra(ReminderReceiver.AT, remindAt)
            scheduleAlarm(context, TODO_BOARD_OFFSET + board.id.toInt(), intent, remindAt)
        } else items.forEach { scheduleTaskAlarm(context, it, board, afterTime) }
    }

    fun cancelTodoBoard(context: Context, boardId: Long) {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(context, TODO_BOARD_OFFSET + boardId.toInt(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pending != null) { context.getSystemService(AlarmManager::class.java).cancel(pending); pending.cancel() }
    }

    fun cancelTodo(context: Context, itemId: Long) {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(context, TODO_OFFSET + itemId.toInt(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pending != null) { context.getSystemService(AlarmManager::class.java).cancel(pending); pending.cancel() }
    }

    fun cancel(context: Context, eventId: Long) {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(context, eventId.toInt(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pending != null) { context.getSystemService(AlarmManager::class.java).cancel(pending); pending.cancel() }
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
        val alarmAt = intent.getLongExtra(AT, Long.MIN_VALUE)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.get(context).appDao()
                val now = System.currentTimeMillis()
                when (type) {
                    TODO -> {
                        val item = dao.getTodoItem(id) ?: return@launch
                        val board = dao.getTodoBoard(item.boardId) ?: return@launch
                        val deadline = effectiveTaskDeadline(item, board)
                        val valid = alarmAt != Long.MIN_VALUE && alarmAt <= now && !isUnifiedBoard(board) &&
                            (deadline == null || deadline >= now) && nextTaskReminderAt(item, board, alarmAt - 1) == alarmAt
                        if (valid) {
                            ReminderScheduler.show(context, item.text.lineSequence().firstOrNull().orEmpty(), "待办提醒", 1_000_000 + id)
                            if (item.reminderRule == null) dao.markTodoReminded(id)
                            else if (item.reminderSkipAt?.let { it <= now } == true) dao.updateTodoItem(item.copy(reminderSkipAt = null))
                        }
                        ReminderScheduler.rescheduleTodo(context, item.id, now)
                    }
                    TODO_BOARD -> {
                        val board = dao.getTodoBoard(id) ?: return@launch
                        val items = dao.getTodoItems(id)
                        val valid = alarmAt != Long.MIN_VALUE && alarmAt <= now &&
                            (board.dueDate == null || board.dueDate >= now) &&
                            items.any { !it.completed && it.deletedAt == null } && nextBoardReminderAt(board, alarmAt - 1) == alarmAt
                        if (valid) {
                            ReminderScheduler.show(context, board.summary, "待办框提醒", 2_000_000 + id)
                            if (board.reminderRule == null) dao.markTodoBoardReminded(id)
                            else if (board.reminderSkipAt?.let { it <= now } == true) dao.updateTodoBoard(board.copy(reminderSkipAt = null))
                        }
                        ReminderScheduler.rescheduleTodoBoard(context, board.id, now)
                    }
                    SCHEDULE -> {
                        val event = dao.allSchedule().firstOrNull { it.id == id } ?: return@launch
                        val expected = event.eventAt - event.reminderDays * 86_400_000L - event.reminderHours * 3_600_000L - event.reminderMinutes * 60_000L
                        if (!event.archived && event.deletedAt == null && event.reminderEnabled && !event.reminderTriggered &&
                            expected <= now && (alarmAt == Long.MIN_VALUE || alarmAt == expected)) {
                            ReminderScheduler.show(context, event.title, event.note, id)
                            dao.markScheduleReminded(id)
                        }
                    }
                }
            } finally { pendingResult.finish() }
        }
    }

    companion object {
        const val TITLE = "title"
        const val NOTE = "note"
        const val ID = "id"
        const val TYPE = "type"
        const val AT = "at"
        const val SCHEDULE = "schedule"
        const val TODO = "todo"
        const val TODO_BOARD = "todo_board"
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.get(context).appDao()
                dao.allSchedule().filter { !it.archived && it.deletedAt == null }.forEach { ReminderScheduler.schedule(context, it) }
                dao.allTodos().forEach { ReminderScheduler.rescheduleTodoBoard(context, it.board.id) }
            } finally { result.finish() }
        }
    }
}
