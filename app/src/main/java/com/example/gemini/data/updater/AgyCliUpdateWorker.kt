package com.example.gemini.data.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.gemini.R
import com.example.gemini.data.remote.services.AgyCliAnalysisReport
import com.example.gemini.data.remote.services.AgyCliUpdateTracker
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "agy_cli_analysis_check"
private const val CHANNEL_ID = "agy_cli_updates"
private const val NOTIFICATION_ID = 7401
private const val PREFS = "agy_cli_analysis_prefs"
private const val KEY_LAST_NOTIFIED_SIGNATURE = "last_notified_signature"

/**
 * Periodically checks the Antigravity CLI for new releases and newly introduced
 * models, and posts a notification when something genuinely new appears.
 * Each distinct change set is notified only once.
 */
class AgyCliUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val report = AgyCliUpdateTracker(applicationContext)
                .checkUpdatesAndAnalyzeModels(forceRefresh = false)

            if (!report.hasCliUpdate && report.newModelsDetected.isEmpty()) return Result.success()

            val signature = buildSignature(report)
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_LAST_NOTIFIED_SIGNATURE, null) == signature) {
                return Result.success()
            }

            if (showNotification(report)) {
                prefs.edit().putString(KEY_LAST_NOTIFIED_SIGNATURE, signature).apply()
            }
            Result.success()
        } catch (_: Exception) {
            // Never retry aggressively; the next periodic run will pick it up.
            Result.success()
        }
    }

    private fun buildSignature(report: AgyCliAnalysisReport): String {
        val versions = "${report.currentCliVersion}->${report.latestCliVersion ?: report.currentCliVersion}"
        val newModels = report.newModelsDetected.map { it.id }.sorted().joinToString(",")
        return "$versions|$newModels"
    }

    private fun showNotification(report: AgyCliAnalysisReport): Boolean {
        val context = applicationContext
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Antigravity CLI & Models",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "New Antigravity CLI releases and newly available AI models"
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val title = when {
            report.newModelsDetected.isNotEmpty() ->
                "${report.newModelsDetected.size} new Antigravity model" +
                    if (report.newModelsDetected.size == 1) "" else "s"
            report.hasCliUpdate -> "Antigravity CLI ${report.latestCliVersion ?: "update"} available"
            else -> "Antigravity CLI analysis updated"
        }

        val lines = mutableListOf<String>()
        if (report.newModelsDetected.isNotEmpty()) {
            lines += report.newModelsDetected.map { "• ${it.displayName}" }
        }
        if (report.hasCliUpdate) {
            lines += "CLI ${report.currentCliVersion} → ${report.latestCliVersion ?: "newer"}"
        }
        if (lines.isEmpty()) {
            lines += "Tap to view the latest capability analysis"
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = launchIntent?.let {
            android.app.PendingIntent.getActivity(
                context,
                0,
                it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AgyCliUpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
