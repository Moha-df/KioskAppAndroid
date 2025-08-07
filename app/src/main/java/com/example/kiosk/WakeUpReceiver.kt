package com.example.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import java.util.Calendar

class WakeUpReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WakeUpReceiver"
        private const val PREFS_NAME = "KioskSettings"
        private const val KEY_WAKE_START_HOUR = "wake_start_hour"
        private const val KEY_WAKE_START_MINUTE = "wake_start_minute"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        try {
            Log.d(TAG, "WakeUpReceiver déclenché à ${Calendar.getInstance().time}")

            context?.let { ctx ->
                // Réveiller l'écran immédiatement
                val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager

                // Wake lock puissant pour forcer le réveil
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                    "KioskApp:WakeUpReceiver"
                )
                
                try {
                    wakeLock.acquire(10000) // 10 secondes pour assurer le réveil
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur acquisition WakeLock", e)
                }

                // Démarrer l'activité principale si elle n'est pas déjà au premier plan
                val mainIntent = Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("WAKE_UP_TRIGGER", true)
                }
                
                try {
                    ctx.startActivity(mainIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur démarrage MainActivity", e)
                }

                // Reprogrammer l'alarme pour le lendemain
                scheduleNextAlarm(ctx)
                
                // Libérer le WakeLock
                try {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                    else {
                        //rien
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur libération WakeLock", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur dans onReceive", e)
        }
    }

    private fun scheduleNextAlarm(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val wakeStartHour = prefs.getInt(KEY_WAKE_START_HOUR, 8)
            val wakeStartMinute = prefs.getInt(KEY_WAKE_START_MINUTE, 0)

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // Créer le calendrier pour demain à la même heure
            val nextAlarm = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, 1) // Demain
                set(Calendar.HOUR_OF_DAY, wakeStartHour)
                set(Calendar.MINUTE, wakeStartMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val intent = Intent(context, WakeUpReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
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

            Log.d(TAG, "Prochaine alarme programmée pour : ${nextAlarm.time}")

        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de la programmation de la prochaine alarme", e)
        }
    }
}