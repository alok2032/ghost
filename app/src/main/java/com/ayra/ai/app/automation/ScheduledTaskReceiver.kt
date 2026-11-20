package com.ayra.ai.app.automation

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ayra.ai.app.MainActivity
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.voice.AyraVoiceAnnouncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * Autonomous broadcast receiver triggered by AlarmManager to execute:
 * 1. Scheduled phone calls and speak the requested message aloud
 * 2. Scheduled SMS / WhatsApp messages with automatic offline fallback
 * 3. Timed voice and audio reminders
 */
class ScheduledTaskReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // 1. Re-arm pending tasks upon device reboot or clock/timezone changes (M7)
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    AyraTaskScheduler(context).rearmAllPendingTasks()
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }

        // Only proceed if explicit scheduled task execution action
        if (action != "com.ayra.ai.app.ACTION_EXECUTE_SCHEDULED_TASK") {
            return
        }

        val taskId = intent.getLongExtra("TASK_ID", -1L)
        val targetApp = intent.getStringExtra("TARGET_APP") ?: "WhatsApp"
        val recipient = intent.getStringExtra("RECIPIENT") ?: "Friend"
        val messageText = intent.getStringExtra("MESSAGE_TEXT") ?: "Hello from Ayra AI!"
        val phoneNumber = intent.getStringExtra("PHONE_NUMBER") ?: ""

        // 2. Acquire safe WakeLock to process task
        var wakeLock: PowerManager.WakeLock? = null
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ayra:scheduled_task_wake"
            )
            wakeLock?.acquire(15000)
        } catch (_: Exception) {}

        executeScheduledTaskNotification(
            context = context,
            taskId = taskId,
            targetApp = targetApp,
            recipient = recipient,
            messageText = messageText,
            phoneNumber = phoneNumber,
            playSoundAndVoice = true
        )

        // 4. Update task status in database to FIRED
        if (taskId > 0) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ChatDatabase.getDatabase(context).scheduledTaskDao().updateStatus(taskId, "FIRED")
                } catch (_: Exception) {
                } finally {
                    try {
                        wakeLock?.release()
                    } catch (_: Exception) {}
                    pendingResult.finish()
                }
            }
        } else {
            try {
                wakeLock?.release()
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val CHANNEL_ID = "ayra_scheduled_routines"

        internal fun buildWhatsAppUri(targetPhone: String, messageText: String): Uri {
            val encoded = URLEncoder.encode(messageText, "UTF-8")
            val digits = targetPhone.filter { it.isDigit() }
            val url = if (digits.length >= 7) {
                "https://wa.me/$digits?text=$encoded"
            } else {
                "https://wa.me/?text=$encoded"
            }
            return Uri.parse(url)
        }

        private fun ensureChannel(context: Context): NotificationManager? {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Ayra AI Autonomous Tasks & Calls",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Autonomous task execution, scheduled phone calls, and spoken reminders"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }
            return nm
        }

        fun executeScheduledTaskNotification(
            context: Context,
            taskId: Long,
            targetApp: String,
            recipient: String,
            messageText: String,
            phoneNumber: String,
            playSoundAndVoice: Boolean = true
        ) {
            val nm = ensureChannel(context)
            val isBengali = messageText.any { it in '\u0980'..'\u09FF' } || recipient.any { it in '\u0980'..'\u09FF' }
            val notifId = ((taskId % 100000L).toInt().coerceAtLeast(1)) + 2000

            when {
                targetApp.equals("CALL", ignoreCase = true) -> {
                    if (playSoundAndVoice) AyraVoiceAnnouncer.playAlarmChime(context)
                    val targetNumber = if (phoneNumber.isNotBlank()) phoneNumber else recipient
                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$targetNumber")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        context,
                        (taskId % 50000L).toInt().coerceAtLeast(101),
                        dialIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.sym_action_call)
                        .setContentTitle("📞 Scheduled Call Reminder for $recipient")
                        .setContentText("Tap to call: $targetNumber")
                        .setStyle(NotificationCompat.BigTextStyle().bigText("Scheduled call for $recipient ($targetNumber).\nMessage note: \"$messageText\"\n\nTap to open the dialer."))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .addAction(android.R.drawable.sym_action_call, "Open Dialer", pendingIntent)
                        .build()
                    nm?.notify(notifId, notif)
                }

                targetApp.equals("REMINDER", ignoreCase = true) -> {
                    if (playSoundAndVoice) {
                        AyraVoiceAnnouncer.playAlarmChime(context)
                        val speech = if (isBengali) {
                            "রিমাইন্ডার! Ayra AI আপনাকে মনে করিয়ে দিচ্ছে: $messageText"
                        } else {
                            "Reminder! Ayra AI is reminding you to: $messageText"
                        }
                        AyraVoiceAnnouncer.speakAloud(context, speech)
                    }
                    val openIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        context,
                        (taskId % 50000L).toInt().coerceAtLeast(102),
                        openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_popup_reminder)
                        .setContentTitle("⏰ Ayra AI Reminder")
                        .setContentText(messageText)
                        .setStyle(NotificationCompat.BigTextStyle().bigText("⏰ Scheduled Reminder:\n\n$messageText"))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .build()
                    nm?.notify(notifId, notif)
                }

                else -> {
                    val targetPhone = if (phoneNumber.isNotBlank()) phoneNumber else recipient
                    val sendIntent = if (targetApp.contains("WhatsApp", ignoreCase = true)) {
                        try {
                            Intent(Intent.ACTION_VIEW, buildWhatsAppUri(targetPhone, messageText)).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                        } catch (_: Exception) {
                            Intent(context, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        }
                    } else {
                        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${if (targetPhone.matches(Regex("\\+?[0-9]+"))) targetPhone else ""}")).apply {
                            putExtra("sms_body", messageText)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        context,
                        (taskId % 50000L).toInt().coerceAtLeast(103),
                        sendIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val notifTitle = "✉️ Scheduled Message Ready for $recipient"
                    val notifBody = "Message for $recipient on $targetApp:\n\"$messageText\"\n\nTap to review and send."
                    val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_email)
                        .setContentTitle(notifTitle)
                        .setContentText(notifBody)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(notifBody))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .addAction(android.R.drawable.ic_menu_send, "Review & Send", pendingIntent)
                        .build()
                    nm?.notify(notifId, notif)
                }
            }
        }

        fun notifyMissedTask(
            context: Context,
            task: com.ayra.ai.app.data.ScheduledTask
        ) {
            val nm = ensureChannel(context) ?: return
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                ((task.id % 50000L).toInt().coerceAtLeast(1)) + 3000,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val body = "${task.title} (${task.formattedTime}): ${task.messageText}"
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Missed reminder")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText("Missed reminder while device was offline/off:\n\n$body"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            val notifId = ((task.id % 100000L).toInt().coerceAtLeast(1)) + 4000
            nm.notify(notifId, notif)
        }
    }
}
