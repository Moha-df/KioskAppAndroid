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
        private const val CHECK_INTERVAL = 300000L // 5 minutes
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var isDestroyed = false

    // Variables pour suivre l'état précédent et éviter les logs répétitifs
    private var lastAdbEnabled = -1
    private var lastAdbWifiEnabled = -1
    private var lastWifiEnabled: Boolean? = null

    private val checkAdbRunnable = object : Runnable {
        override fun run() {
            try {
                if (isRunning && !isDestroyed) {
                    checkAndMaintainAdb()
                    handler.postDelayed(this, CHECK_INTERVAL)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur dans checkAdbRunnable", e)
                // Continuer malgré l'erreur
                if (isRunning && !isDestroyed) {
                    handler.postDelayed(this, CHECK_INTERVAL)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            Log.d(TAG, "Service ADB Monitor créé")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur dans onCreate", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            Log.d(TAG, "Service ADB Monitor démarré")

            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)

            isRunning = true
            handler.post(checkAdbRunnable)

            // Service persistant
            return START_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "Erreur dans onStartCommand", e)
            return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service ADB Monitor arrêté")
        isDestroyed = true
        isRunning = false
        try {
            handler.removeCallbacks(checkAdbRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur dans onDestroy", e)
        }
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

            // Log conditionnel : seulement si l'état a changé
            if (adbEnabled != lastAdbEnabled || adbWifiEnabled != lastAdbWifiEnabled) {
                Log.d(TAG, "État ADB changé: général=$adbEnabled, wifi=$adbWifiEnabled")
                lastAdbEnabled = adbEnabled
                lastAdbWifiEnabled = adbWifiEnabled
            }

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

            // Ajout : tenter de réactiver le WiFi si besoin
            ensureWifiEnabled()

        } catch (e: SecurityException) {
            Log.e(TAG, "Erreur de permission lors de la vérification ADB", e)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de la vérification ADB", e)
        }
    }

    // Ajout : fonction pour réactiver le WiFi si besoin
    private fun ensureWifiEnabled() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val currentWifiEnabled = wifiManager.isWifiEnabled
            
            // Log conditionnel : seulement si l'état WiFi a changé
            if (currentWifiEnabled != lastWifiEnabled) {
                Log.d(TAG, "État WiFi changé: $currentWifiEnabled")
                lastWifiEnabled = currentWifiEnabled
            }
            
            if (!currentWifiEnabled) {
                Log.d(TAG, "WiFi désactivé - tentative de réactivation (service)")
                val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val componentName = ComponentName(this, KioskDeviceAdminReceiver::class.java)
                val isDeviceOwner = devicePolicyManager.isDeviceOwnerApp(packageName)
                Log.d(TAG, "isDeviceOwner=$isDeviceOwner")
                if (isDeviceOwner) {
                    @Suppress("DEPRECATION")
                    wifiManager.isWifiEnabled = true
                    Log.d(TAG, "wifiManager.isWifiEnabled = true exécuté (service)")
                } else {
                    Log.d(TAG, "App n'est pas Device Owner, impossible de réactiver le WiFi (service)")
                }
            }
            // Suppression du log "WiFi déjà activé" pour réduire le spam
        } catch (e: Exception) {
            Log.e(TAG, "Erreur réactivation WiFi", e)
        }
    }

    private fun createNotificationChannel() {
        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "Erreur création notification channel", e)
        }
    }

    private fun createNotification(): Notification {
        return try {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Kiosk ADB Monitor")
                .setContentText("Surveillance du débogage ADB active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur création notification", e)
            // Notification de fallback
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Kiosk ADB Monitor")
                .setContentText("Service actif")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        }
    }
}