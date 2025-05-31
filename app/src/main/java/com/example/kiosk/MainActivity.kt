package com.example.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var toggleButton: Button
    private var isKioskMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Initialiser les composants Device Admin
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, KioskDeviceAdminReceiver::class.java)

        // Initialiser le bouton
        toggleButton = findViewById(R.id.toggleKioskButton)
        updateButtonText()

        toggleButton.setOnClickListener {
            toggleKioskMode()
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun toggleKioskMode() {
        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) {
            Toast.makeText(this, "L'app doit être Device Owner pour utiliser le mode Kiosk", Toast.LENGTH_LONG).show()
            return
        }

        if (isKioskMode) {
            exitKioskMode()
        } else {
            enterKioskMode()
        }
    }

    private fun enterKioskMode() {
        try {
            // Activer le mode immersif complet
            hideSystemUI()

            // Démarrer le Lock Task Mode
            startLockTask()

            // Activer les restrictions de l'écran de verrouillage
            devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(packageName))

            isKioskMode = true
            updateButtonText()

            Toast.makeText(this, "Mode Kiosk ACTIVÉ", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Erreur lors de l'activation du mode Kiosk: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exitKioskMode() {
        try {
            // Sortir du Lock Task Mode
            stopLockTask()

            // Réactiver l'interface système
            showSystemUI()

            // Désactiver les restrictions
            devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf())

            isKioskMode = false
            updateButtonText()

            Toast.makeText(this, "Mode Kiosk DÉSACTIVÉ", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Erreur lors de la désactivation du mode Kiosk: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hideSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+ (API 30+)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Android 10 et versions antérieures
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
        }

        // Garder l'écran allumé
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+ (API 30+)
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            // Android 10 et versions antérieures
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }

        // Retirer le flag keep screen on
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updateButtonText() {
        toggleButton.text = if (isKioskMode) {
            "DÉSACTIVER MODE KIOSK"
        } else {
            "ACTIVER MODE KIOSK"
        }
    }

    override fun onBackPressed() {
        if (isKioskMode) {
            // En mode Kiosk, empêcher la sortie par le bouton retour
            Toast.makeText(this, "Mode Kiosk actif - Utilisez le bouton pour désactiver", Toast.LENGTH_SHORT).show()
        } else {
            super.onBackPressed()
        }
    }
}