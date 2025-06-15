package com.example.kiosk

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import android.app.admin.DevicePolicyManager
import android.content.ComponentName

class AdbMonitorService : Service() {

    companion object {
        private const val TAG = "AdbMonitorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "adb_monitor_channel"
        private const val CHECK_INTERVAL = 30000L // 30 secondes
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    private val checkAdbRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                checkAndMaintainAdb()
                handler.postDelayed(this, CHECK_INTERVAL)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Service ADB Monitor créé")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service ADB Monitor démarré")

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        isRunning = true
        handler.post(checkAdbRunnable)

        // Service persistant
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service ADB Monitor arrêté")
        isRunning = false
        handler.removeCallbacks(checkAdbRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkAndMaintainAdb() {
        try {
            // Lecture de l'état ADB
            val adbEnabled = Settings.Global.getInt(contentResolver,
                Settings.Global.ADB_ENABLED, 0)

            val adbWifiEnabled = try {
                Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0)
            } catch (e: Exception) { 0 }

            Log.d(TAG, "État ADB: général=$adbEnabled, wifi=$adbWifiEnabled")

            // Si ADB général désactivé, essayer de le réactiver avec DevicePolicyManager
            if (adbEnabled == 0) {
                Log.w(TAG, "ADB général désactivé - Tentative de réactivation...")

                try {
                    // Méthode 1: DevicePolicyManager (Device Owner)
                    val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val componentName = ComponentName(this, KioskDeviceAdminReceiver::class.java)

                    if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
                        // Utiliser DevicePolicyManager pour modifier les settings globaux
                        devicePolicyManager.setGlobalSetting(
                            componentName,
                            Settings.Global.ADB_ENABLED,
                            "1"
                        )
                        Log.i(TAG, "ADB réactivé via DevicePolicyManager")
                    }

                } catch (e: Exception) {
                    Log.w(TAG, "DevicePolicyManager échoué, essai méthode directe", e)

                    try {
                        // Méthode 2: Settings direct (fallback)
                        Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, 1)
                        Log.i(TAG, "ADB réactivé via Settings direct")
                    } catch (e2: SecurityException) {
                        Log.w(TAG, "Aucune méthode disponible pour réactiver ADB général")
                    }
                }
            }

            // Pour ADB WiFi, essayer les mêmes méthodes
            if (adbWifiEnabled == 0) {
                Log.w(TAG, "ADB WiFi désactivé - Tentative de réactivation...")
                try {
                    val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val componentName = ComponentName(this, KioskDeviceAdminReceiver::class.java)

                    if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
                        devicePolicyManager.setGlobalSetting(
                            componentName,
                            "adb_wifi_enabled",
                            "1"
                        )
                        Log.i(TAG, "ADB WiFi réactivé via DevicePolicyManager")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Impossible de réactiver ADB WiFi", e)
                }
            }

            // Tentative de maintenir le port 5555
            try {
                Runtime.getRuntime().exec("setprop service.adb.tcp.port 5555")
                Runtime.getRuntime().exec("setprop service.adb.tcp.enable 1")
            } catch (e: Exception) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Commandes TCP port échouées (normal)", e)
                }
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Erreur de permission lors de la vérification ADB", e)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de la vérification ADB", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ADB Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Surveillance et maintenance du débogage ADB"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kiosk ADB Monitor")
            .setContentText("Surveillance du débogage ADB active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}