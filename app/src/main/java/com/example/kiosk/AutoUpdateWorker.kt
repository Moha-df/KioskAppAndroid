package com.example.kiosk

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class AutoUpdateWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

	companion object {
		private const val TAG = "AutoUpdateWorker"
		private const val UPDATE_URL = "https://moha-df.github.io/kiosk-apk-update/update.json"
	}

	private val client: OkHttpClient by lazy {
		val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
		OkHttpClient.Builder().addInterceptor(logging).build()
	}

	override suspend fun doWork(): Result {
		return try {
			if (!isDeviceOwner()) {
				Log.w(TAG, "App is not device owner, skipping auto-update")
				return Result.success()
			}

			val json = fetchUpdateJson() ?: return Result.retry()
			val remoteVersionCode = json.optInt("versionCode", 0)
			val apkUrl = json.optString("apkUrl", "")

			if (remoteVersionCode <= 0 || apkUrl.isBlank()) {
				Log.w(TAG, "Invalid update json: $json")
				return Result.retry()
			}

			val currentVersionCode = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).longVersionCode
			if (remoteVersionCode.toLong() <= currentVersionCode) {
				Log.d(TAG, "No update needed by JSON. Remote=$remoteVersionCode, current=$currentVersionCode")
				return Result.success()
			}

			val apkFile = downloadApk(apkUrl) ?: return Result.retry()

			// Robustesse: vérifier la version réelle de l'APK téléchargée
			val pm = applicationContext.packageManager
			val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
			val downloadedVersion = when {
				archiveInfo == null -> -1L
				archiveInfo.longVersionCode > 0 -> archiveInfo.longVersionCode
				else -> archiveInfo.versionCode.toLong()
			}
			if (downloadedVersion <= 0) {
				Log.w(TAG, "Cannot read downloaded APK version, skipping install")
				return Result.retry()
			}
			if (downloadedVersion <= currentVersionCode) {
				Log.w(TAG, "Downloaded APK version ($downloadedVersion) is not higher than current ($currentVersionCode). Skipping.")
				return Result.success()
			}

			val installed = installSilentlyWithSession(apkFile)
			if (installed) Result.success() else Result.retry()
		} catch (e: Exception) {
			Log.e(TAG, "Error in auto-update", e)
			Result.retry()
		}
	}

	private fun isDeviceOwner(): Boolean {
		return try {
			val dpm = applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
			dpm.isDeviceOwnerApp(applicationContext.packageName)
		} catch (e: Exception) { false }
	}

	private fun fetchUpdateJson(): JSONObject? {
		return try {
			val request = Request.Builder().url(UPDATE_URL).header("Cache-Control", "no-cache").build()
			client.newCall(request).execute().use { resp ->
				if (!resp.isSuccessful) {
					Log.w(TAG, "HTTP ${'$'}{resp.code}")
					return null
				}
				val body = resp.body?.string() ?: return null
				JSONObject(body)
			}
		} catch (e: Exception) {
			Log.e(TAG, "fetchUpdateJson failed", e)
			null
		}
	}

	private fun downloadApk(url: String): File? {
		return try {
			val req = Request.Builder().url(url).build()
			client.newCall(req).execute().use { resp ->
				if (!resp.isSuccessful) {
					Log.w(TAG, "Download failed HTTP ${'$'}{resp.code}")
					return null
				}
				val targetDir = File(applicationContext.filesDir, "updates").apply { mkdirs() }
				val outFile = File(targetDir, "update.apk")
				resp.body?.byteStream()?.use { input ->
					FileOutputStream(outFile).use { output -> input.copyTo(output) }
				}
				outFile
			}
		} catch (e: Exception) {
			Log.e(TAG, "downloadApk failed", e)
			null
		}
	}

	private fun installSilentlyWithSession(apk: File): Boolean {
		return try {
			val packageInstaller: PackageInstaller = applicationContext.packageManager.packageInstaller
			val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
			params.setAppPackageName(applicationContext.packageName)
			val sessionId = packageInstaller.createSession(params)
			val session = packageInstaller.openSession(sessionId)

			FileInputStream(apk).use { input ->
				session.openWrite("base.apk", 0, apk.length()).use { out ->
					input.copyTo(out)
					session.fsync(out)
				}
			}

			val intent = Intent(applicationContext, InstallResultReceiver::class.java)
			val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
			val pendingIntent = PendingIntent.getBroadcast(applicationContext, sessionId, intent, flags)
			session.commit(pendingIntent.intentSender)
			session.close()

			Log.i(TAG, "PackageInstaller session committed (silent)")
			true
		} catch (e: Exception) {
			Log.e(TAG, "installSilentlyWithSession failed", e)
			false
		}
	}
} 