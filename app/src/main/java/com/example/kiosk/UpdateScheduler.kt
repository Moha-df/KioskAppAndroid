package com.example.kiosk

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import java.util.concurrent.TimeUnit

object UpdateScheduler {
	private const val UNIQUE_WORK_NAME = "auto_update_worker"

	fun schedule(context: Context) {
		val constraints = Constraints.Builder()
			.setRequiredNetworkType(NetworkType.CONNECTED)
			.build()

		val periodic = PeriodicWorkRequestBuilder<AutoUpdateWorker>(6, TimeUnit.HOURS)
			.setConstraints(constraints)
			.build()

		WorkManager.getInstance(context)
			.enqueueUniquePeriodicWork(
				UNIQUE_WORK_NAME,
				ExistingPeriodicWorkPolicy.UPDATE,
				periodic
			)

		val immediate = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
			.setConstraints(constraints)
			.build()

		WorkManager.getInstance(context)
			.enqueueUniqueWork(
				UNIQUE_WORK_NAME + "_immediate",
				ExistingWorkPolicy.KEEP,
				immediate
			)
	}
} 