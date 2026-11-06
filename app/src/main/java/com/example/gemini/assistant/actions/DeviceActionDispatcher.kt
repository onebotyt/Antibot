package com.example.gemini.assistant.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

sealed class ActionResult {
    data class Success(val message: String, val extraData: String? = null) : ActionResult()
    data class Error(val reason: String) : ActionResult()
}

object DeviceActionDispatcher {

    /**
     * Parse natural language command and execute system actions.
     * Returns ActionResult if an action was handled, or null if the prompt is general coding/conversation.
     */
    suspend fun tryExecuteAction(context: Context, prompt: String): ActionResult? = withContext(Dispatchers.Main) {
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Set Alarm (e.g. "set alarm for morning 8 am", "alarm at 7:30", "wake me up at 6 am")
        if (lower.contains("alarm") || lower.contains("wake me up")) {
            val alarmParsed = parseAlarmTime(lower)
            if (alarmParsed != null) {
                return@withContext setAlarm(
                    context = context,
                    hour = alarmParsed.first,
                    minute = alarmParsed.second,
                    label = alarmParsed.third
                )
            }
        }

        // 2. Set Timer (e.g. "set timer for 15 minutes", "timer 5 mins", "set a 10 min timer")
        if (lower.contains("timer")) {
            val seconds = parseTimerSeconds(lower)
            if (seconds != null) {
                return@withContext setTimer(context, seconds, "AntiBot Timer")
            }
        }

        // 3. Make Phone Call (e.g. "call mom", "call 9876543210", "make a phone call to John")
        if (lower.startsWith("call ") || lower.contains("phone call to ") || lower.contains("make a call to ")) {
            val target = lower.removePrefix("call ")
                .removePrefix("make a phone call to ")
                .removePrefix("make a call to ")
                .removePrefix("phone ")
                .trim()
            if (target.isNotEmpty()) {
                return@withContext makeCall(context, target)
            }
        }

        // 4. Send Message / SMS (e.g. "send message to Rahul: I am coming", "sms to mom hello")
        if (lower.startsWith("send message to ") || lower.startsWith("send sms to ") || lower.startsWith("message ")) {
            val after = lower.removePrefix("send message to ")
                .removePrefix("send sms to ")
                .removePrefix("message ")
                .trim()
            val splitColon = after.split(":", limit = 2)
            val recipient = splitColon[0].trim()
            val msgBody = if (splitColon.size > 1) splitColon[1].trim() else ""
            if (recipient.isNotEmpty()) {
                return@withContext sendSms(context, recipient, msgBody)
            }
        }

        // 5. Flashlight (e.g. "turn on flashlight", "turn off torch")
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val enable = !lower.contains("off") && !lower.contains("disable")
            return@withContext toggleFlashlight(context, enable)
        }

        // 6. Open App (e.g. "open youtube", "open spotify", "launch telegram")
        if (lower.startsWith("open ") || lower.startsWith("launch ")) {
            val appName = lower.removePrefix("open ").removePrefix("launch ").trim()
            if (appName.isNotEmpty() && !appName.contains("file") && !appName.contains("terminal")) {
                return@withContext openApp(context, appName)
            }
        }

        return@withContext null
    }

    fun setAlarm(context: Context, hour: Int, minute: Int, label: String = "AntiBot Alarm"): ActionResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val formattedTime = String.format(Locale.US, "%02d:%02d", hour, minute)
            ActionResult.Success("⏰ Alarm set for $formattedTime ($label)")
        } catch (e: Exception) {
            ActionResult.Error("Failed to set alarm: ${e.localizedMessage ?: e.message}")
        }
    }

    fun setTimer(context: Context, lengthSeconds: Int, label: String = "AntiBot Timer"): ActionResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, lengthSeconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val minutes = lengthSeconds / 60
            val sec = lengthSeconds % 60
            val dur = if (minutes > 0) "$minutes min ${if (sec > 0) "$sec sec" else ""}" else "$sec seconds"
            ActionResult.Success("⏱️ Timer started for $dur")
        } catch (e: Exception) {
            ActionResult.Error("Failed to start timer: ${e.localizedMessage ?: e.message}")
        }
    }

    suspend fun makeCall(context: Context, recipient: String): ActionResult {
        val cleanDigits = recipient.filter { it.isDigit() || it == '+' }
        val finalNumber = if (cleanDigits.length >= 3) {
            cleanDigits
        } else {
            val match = ContactResolver.findContactByName(context, recipient)
            match?.phoneNumber
        }

        if (finalNumber.isNullOrBlank()) {
            return ActionResult.Error("Could not find contact '$recipient' or valid phone number.")
        }

        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$finalNumber")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ActionResult.Success("📞 Initiated call to $recipient ($finalNumber)")
        } catch (e: Exception) {
            ActionResult.Error("Failed to start phone call: ${e.localizedMessage ?: e.message}")
        }
    }

    suspend fun sendSms(context: Context, recipient: String, message: String): ActionResult {
        val cleanDigits = recipient.filter { it.isDigit() || it == '+' }
        val finalNumber = if (cleanDigits.length >= 3) {
            cleanDigits
        } else {
            val match = ContactResolver.findContactByName(context, recipient)
            match?.phoneNumber ?: recipient
        }

        return try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$finalNumber")).apply {
                if (message.isNotBlank()) {
                    putExtra("sms_body", message)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ActionResult.Success("💬 Composing message to $recipient: \"$message\"")
        } catch (e: Exception) {
            ActionResult.Error("Failed to send message: ${e.localizedMessage ?: e.message}")
        }
    }

    fun toggleFlashlight(context: Context, enable: Boolean = true): ActionResult {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val cameraId = cameraManager?.cameraIdList?.firstOrNull() ?: return ActionResult.Error("No camera flash available.")
            cameraManager.setTorchMode(cameraId, enable)
            ActionResult.Success(if (enable) "🔦 Flashlight turned ON" else "🔦 Flashlight turned OFF")
        } catch (e: Exception) {
            ActionResult.Error("Could not control flashlight: ${e.localizedMessage ?: e.message}")
        }
    }

    fun openApp(context: Context, appQuery: String): ActionResult {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        val match = packages.firstOrNull { pkg ->
            val label = pkg.applicationInfo?.loadLabel(pm)?.toString()?.lowercase(Locale.ROOT) ?: ""
            label.contains(appQuery) || pkg.packageName.lowercase(Locale.ROOT).contains(appQuery)
        }

        if (match != null) {
            val launchIntent = pm.getLaunchIntentForPackage(match.packageName)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
                val label = match.applicationInfo?.loadLabel(pm)?.toString() ?: match.packageName
                return ActionResult.Success("🚀 Opened $label")
            }
        }
        return ActionResult.Error("App '$appQuery' is not installed or cannot be opened.")
    }

    // Helper: parse alarm time from strings like "set alarm for 8 am", "alarm at 7:30 pm"
    private fun parseAlarmTime(text: String): Triple<Int, Int, String>? {
        val pattern = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            var hour = matcher.group(1)?.toIntOrNull() ?: return null
            val minute = matcher.group(2)?.toIntOrNull() ?: 0
            val ampm = matcher.group(3)?.lowercase(Locale.ROOT)

            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0

            val label = if (text.contains("morning")) "Morning Alarm" else "AntiBot Alarm"
            return Triple(hour, minute, label)
        }
        return null
    }

    // Helper: parse timer duration in seconds
    private fun parseTimerSeconds(text: String): Int? {
        val minPattern = Pattern.compile("(\\d+)\\s*(?:min|minute)", Pattern.CASE_INSENSITIVE)
        val minMatcher = minPattern.matcher(text)
        if (minMatcher.find()) {
            val mins = minMatcher.group(1)?.toIntOrNull() ?: return null
            return mins * 60
        }
        val secPattern = Pattern.compile("(\\d+)\\s*(?:sec|second)", Pattern.CASE_INSENSITIVE)
        val secMatcher = secPattern.matcher(text)
        if (secMatcher.find()) {
            return secMatcher.group(1)?.toIntOrNull()
        }
        return null
    }
}
