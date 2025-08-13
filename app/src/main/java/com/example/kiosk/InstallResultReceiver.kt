package com.example.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class InstallResultReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val status = intent.getIntExtra("android.content.pm.extra.STATUS", Integer.MIN_VALUE)
		val message = intent.getStringExtra("android.content.pm.extra.STATUS_MESSAGE")
		Log.i("InstallResultReceiver", "Install status=${'$'}status message=${'$'}message")

		// Optionally relaunch MainActivity on success
		if (status == 0) {
			try {
				val launch = Intent(context, MainActivity::class.java).apply {
					addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				}
				context.startActivity(launch)
			} catch (_: Exception) {}
		}
	}
} 