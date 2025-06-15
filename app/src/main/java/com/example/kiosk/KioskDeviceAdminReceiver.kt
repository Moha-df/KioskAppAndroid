package com.example.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.provider.Settings
import android.util.Log
import java.io.IOException

class KioskDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "KioskDeviceAdmin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device Admin activé")

        val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, KioskDeviceAdminReceiver::class.java)

        if (devicePolicyManager.isDeviceOwnerApp(context.packageName)) {
            Log.d(TAG, "Device Owner détecté - Configuration ADB")

            // Activer et maintenir ADB
            enableAndMaintainAdb(context)

            // Démarrer le service de monitoring
            val serviceIntent = Intent(context, AdbMonitorService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "Device Admin désactivé")

        // Arrêter le service de monitoring
        val serviceIntent = Intent(context, AdbMonitorService::class.java)
        context.stopService(serviceIntent)
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        //Toast.makeText(context, "Entrée en mode Lock Task", Toast.LENGTH_SHORT).show()
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        //Toast.makeText(context, "Sortie du mode Lock Task", Toast.LENGTH_SHORT).show()
    }

    private fun enableAndMaintainAdb(context: Context) {
        try {
            // ADB général
            Settings.Global.putInt(context.contentResolver,
                Settings.Global.ADB_ENABLED, 1)

            // Spécifiquement pour le sans fil (Android 11+)
            try {
                Settings.Global.putInt(context.contentResolver,
                    "adb_wifi_enabled", 1)
            } catch (e: Exception) {
                Log.w(TAG, "Impossible d'activer adb_wifi_enabled", e)
            }

            // Alternative via commandes système
            try {
                Runtime.getRuntime().exec("setprop service.adb.tcp.port 5555")
                Runtime.getRuntime().exec("setprop service.adb.tcp.enable 1")
            } catch (e: Exception) {
                Log.w(TAG, "Commandes TCP échouées", e)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Erreur activation ADB", e)
        }
    }
}