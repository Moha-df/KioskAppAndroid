package com.example.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

class SleepReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SleepReceiver"
        private const val PREFS_NAME = "KioskSettings"
        private const val KEY_WAKE_END_HOUR = "wake_end_hour"
        private const val KEY_WAKE_END_MINUTE = "wake_end_minute"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        try {
            Log.d(TAG, "SleepReceiver déclenché à ${Calendar.getInstance().time}")

            context?.let { ctx ->
                // Envoyer un broadcast à l'activité principale
                val sleepIntent = Intent("com.example.kiosk.SLEEP_TIME")
                try {
                    ctx.sendBroadcast(sleepIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur envoi broadcast sleep", e)
                }

                // Reprogrammer l'alarme pour le lendemain
                scheduleNextAlarm(ctx)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur dans onReceive", e)
        }
    }

    private fun scheduleNextAlarm(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val wakeEndHour = prefs.getInt(KEY_WAKE_END_HOUR, 18)
            val wakeEndMinute = prefs.getInt(KEY_WAKE_END_MINUTE, 0)

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // Créer le calendrier pour demain à la même heure
            val nextAlarm = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, 1) // Demain
                set(Calendar.HOUR_OF_DAY, wakeEndHour)
                set(Calendar.MINUTE, wakeEndMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val intent = Intent(context, SleepReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Programmer l'alarme exacte pour demain
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextAlarm.timeInMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    nextAlarm.timeInMillis,
                    pendingIntent
                )
            }

            Log.d(TAG, "Prochaine alarme de sommeil programmée pour : ${nextAlarm.time}")

        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de la programmation de la prochaine alarme", e)
        }
    }
}