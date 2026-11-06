package com.example.gemini.assistant.tasks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.gemini.MainActivity
import com.example.gemini.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmartTaskReminderReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "antibot_task_reminders"
        const val CHANNEL_NAME = "AntiBot Task Reminders"
        const val ACTION_COMPLETE = "com.antibot.action.COMPLETE_TASK"
        const val ACTION_SNOOZE = "com.antibot.action.SNOOZE_TASK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(SmartTaskAlarmScheduler.EXTRA_TASK_ID) ?: return
        val title = intent.getStringExtra(SmartTaskAlarmScheduler.EXTRA_TASK_TITLE) ?: "Task Reminder"
        val notes = intent.getStringExtra(SmartTaskAlarmScheduler.EXTRA_TASK_NOTES) ?: ""

        when (intent.action) {
            ACTION_COMPLETE -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val repo = SmartTaskRepository(context)
                    repo.toggleComplete(taskId)
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.cancel(taskId.hashCode())
                }
                return
            }
            ACTION_SNOOZE -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val repo = SmartTaskRepository(context)
                    val task = repo.getTaskById(taskId)
                    if (task != null) {
                        val snoozeTime = System.currentTimeMillis() + (15 * 60 * 1000L) // 15 mins
                        repo.updateTask(task.copyWith(dueAt = snoozeTime))
                    }
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.cancel(taskId.hashCode())
                }
                return
            }
        }

        // Show Heads-Up Notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders for scheduled Smart Tasks and To-Do items"
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Tap Intent
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "tasks")
        }
        val tapPending = PendingIntent.getActivity(
            context,
            taskId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Complete Action Intent
        val completeIntent = Intent(context, SmartTaskReminderReceiver::class.java).apply {
            action = ACTION_COMPLETE
            putExtra(SmartTaskAlarmScheduler.EXTRA_TASK_ID, taskId)
        }
        val completePending = PendingIntent.getBroadcast(
            context,
            taskId.hashCode() + 1,
            completeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Snooze Action Intent
        val snoozeIntent = Intent(context, SmartTaskReminderReceiver::class.java).apply {
            action = ACTION_SNOOZE
            putExtra(SmartTaskAlarmScheduler.EXTRA_TASK_ID, taskId)
        }
        val snoozePending = PendingIntent.getBroadcast(
            context,
            taskId.hashCode() + 2,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_antibot_logo)
            .setContentTitle("📋 $title")
            .setContentText(notes.ifBlank { "Smart Task is due now" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(notes.ifBlank { "Smart Task is due now" }))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(tapPending)
            .setAutoCancel(true)
            .addAction(0, "✓ Mark Done", completePending)
            .addAction(0, "⏱️ Snooze 15m", snoozePending)
            .build()

        notificationManager.notify(taskId.hashCode(), notification)
    }
}
