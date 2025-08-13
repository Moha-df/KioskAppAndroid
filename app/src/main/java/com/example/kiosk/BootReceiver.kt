package com.example.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		try {
			if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
				Log.d("BootReceiver", "Boot terminé - Lancement MainActivity et planification des mises à jour")

				// Planifier les mises à jour périodiques
				try {
					UpdateScheduler.schedule(context)
				} catch (e: Exception) {
					Log.e("BootReceiver", "Erreur planification WorkManager", e)
				}

				val startIntent = Intent(context, MainActivity::class.java).apply {
					flags = Intent.FLAG_ACTIVITY_NEW_TASK
				}
				
				try {
					context.startActivity(startIntent)
				} catch (e: Exception) {
					Log.e("BootReceiver", "Erreur démarrage MainActivity", e)
				}
			}
		} catch (e: Exception) {
			Log.e("BootReceiver", "Erreur dans onReceive", e)
		}
	}
}