package com.example.kiosk

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class AutoUpdateWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

	companion object {
		private const val TAG = "AutoUpdateWorker"
		private const val UPDATE_URL = "https://moha-df.github.io/kiosk-apk-update/update.json"
	}

	private val client: OkHttpClient by lazy {
		val logging = HttpLoggingInterceptor().apply {
			level = HttpLoggingInterceptor.Level.BASIC
		}
		OkHttpClient.Builder()
			.addInterceptor(logging)
			.build()
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
				Log.d(TAG, "No update needed. Remote=$remoteVersionCode, current=$currentVersionCode")
				return Result.success()
			}

			val apkFile = downloadApk(apkUrl) ?: return Result.retry()
			val installed = installSilently(apkFile)
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
			val request = Request.Builder()
				.url(UPDATE_URL)
				.header("Cache-Control", "no-cache")
				.build()
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
					FileOutputStream(outFile).use { output ->
						input.copyTo(output)
					}
				}
				outFile
			}
		} catch (e: Exception) {
			Log.e(TAG, "downloadApk failed", e)
			null
		}
	}

	private fun installSilently(apk: File): Boolean {
		return try {
			val dpm = applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
			val component = ComponentName(applicationContext, KioskDeviceAdminReceiver::class.java)

			if (Build.VERSION.SDK_INT >= 23) {
				// Device Owner silent install via PackageInstaller session through DPM is allowed when setInstallPackage
				val packageUri: Uri = FileProvider.getUriForFile(
					applicationContext,
					applicationContext.packageName + ".fileprovider",
					apk
				)

				val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
				applicationContext.grantUriPermission("com.android.packageinstaller", packageUri, flags)

				val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
					setDataAndType(packageUri, "application/vnd.android.package-archive")
					addFlags(flags)
					putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, applicationContext.packageName)
					putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
					putExtra(Intent.EXTRA_RETURN_RESULT, true)
				}

				installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				applicationContext.startActivity(installIntent)
				Log.i(TAG, "Triggered installer UI")
				return true
			} else {
				false
			}
		} catch (e: Exception) {
			Log.e(TAG, "installSilently failed", e)
			false
		}
	}
} 