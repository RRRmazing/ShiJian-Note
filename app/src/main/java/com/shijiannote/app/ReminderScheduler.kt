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
import com.shijiannote.app.data.ScheduleEvent

object ReminderScheduler {
    private const val CHANNEL_ID = "schedule_reminders"

    fun schedule(context: Context, event: ScheduleEvent) {
        if (event.reminderDays == 0 && event.reminderHours == 0 && event.reminderMinutes == 0) return
        requestNotificationPermission(context)
        val remindAt = event.eventAt - event.reminderDays * 86_400_000L - event.reminderHours * 3_600_000L - event.reminderMinutes * 60_000L
        if (remindAt <= System.currentTimeMillis()) return
        val intent = Intent(context, ReminderReceiver::class.java).putExtra(ReminderReceiver.TITLE, event.title).putExtra(ReminderReceiver.NOTE, event.note).putExtra(ReminderReceiver.ID, event.id)
        val pending = PendingIntent.getBroadcast(context, event.id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val manager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, pending)
        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, pending)
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
        val notification = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(note.ifBlank { "时间表任务提醒" }).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        context.getSystemService(NotificationManager::class.java).notify(id.toInt(), notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ReminderScheduler.show(context, intent.getStringExtra(TITLE).orEmpty(), intent.getStringExtra(NOTE).orEmpty(), intent.getLongExtra(ID, 0))
    companion object { const val TITLE = "title"; const val NOTE = "note"; const val ID = "id" }
}
