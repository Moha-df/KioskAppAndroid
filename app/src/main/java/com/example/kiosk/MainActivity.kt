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
import java.lang.reflect.Method
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout

class MainActivity : AppCompatActivity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var toggleButton: Button
    private var isKioskMode = false

    // Variables pour la solution Samsung
    private var overlayView: View? = null
    private var statusBarBlocker: View? = null
    private var navBarBlocker: View? = null
    private var windowManager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hideSystemUIRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, KioskDeviceAdminReceiver::class.java)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
            setupSamsungKioskFeatures()
        }

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

        // Initialiser le runnable pour masquer l'UI système
        hideSystemUIRunnable = Runnable {
            if (isKioskMode) {
                hideSystemUIRadical()
            }
        }
    }

    private fun setupSamsungKioskFeatures() {
        try {
            devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(packageName))

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                devicePolicyManager.setLockTaskFeatures(adminComponent, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }

            devicePolicyManager.setKeyguardDisabled(adminComponent, true)

            // Désactiver les paramètres Samsung spécifiques
            disableSamsungFeaturesViaReflection()

            // Désactiver la barre de statut via Device Owner
            devicePolicyManager.setStatusBarDisabled(adminComponent, true)

        } catch (e: Exception) {
            Toast.makeText(this, "Erreur setup Samsung: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun disableSamsungFeaturesViaReflection() {
        try {
            val samsungSettings = arrayOf(
                "edge_panels_enabled",
                "navigation_bar_gesture_while_hidden",
                "navigation_bar_gesture_hint",
                "assistant_gesture_enabled",
                "any_screen_enabled",
                "cocktail_bar_enabled",
                "people_edge_enabled",
                "task_edge_enabled",
                "multi_window_edge_enabled",
                "navigation_bar_policy"
            )

            for (setting in samsungSettings) {
                try {
                    devicePolicyManager.setSystemSetting(adminComponent, setting, "0")
                } catch (e: Exception) {
                    // Certains settings peuvent ne pas exister
                }
            }

            // Désactiver complètement la navigation bar via réflexion
            try {
                val globalClass = Class.forName("android.provider.Settings\$Global")
                val putStringMethod = globalClass.getMethod("putString",
                    android.content.ContentResolver::class.java,
                    String::class.java,
                    String::class.java
                )

                putStringMethod.invoke(null, contentResolver, "navigation_bar_policy", "0")
                putStringMethod.invoke(null, contentResolver, "policy_control", "immersive.full=*")

            } catch (e: Exception) {
                // Réflexion pas toujours possible
            }

        } catch (e: Exception) {
            // Ignore les erreurs
        }
    }

    private fun toggleKioskMode() {
        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) {
            Toast.makeText(this, "Device Owner requis", Toast.LENGTH_LONG).show()
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
            // 1. Configuration Device Owner
            devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(packageName))

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                // Désactiver TOUTES les fonctionnalités en mode Lock Task
                devicePolicyManager.setLockTaskFeatures(adminComponent,
                    DevicePolicyManager.LOCK_TASK_FEATURE_NONE
                )
            }

            // 2. Désactiver complètement les barres système et les boutons
            devicePolicyManager.setStatusBarDisabled(adminComponent, true)
            devicePolicyManager.setKeyguardDisabled(adminComponent, true)

            // Désactiver spécifiquement les boutons de navigation
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                try {
                    // Désactiver le bouton home
                    devicePolicyManager.setGlobalSetting(adminComponent, "device_provisioned", "0")

                    // Désactiver les boutons de navigation
                    devicePolicyManager.setSystemSetting(adminComponent, "buttons_show_on_screen_navkeys", "0")
                    devicePolicyManager.setSystemSetting(adminComponent, "navigation_bar_visible", "0")

                    // Mode immersif complet
                    devicePolicyManager.setGlobalSetting(adminComponent, "policy_control", "immersive.full=*")

                } catch (e: Exception) {
                    // Certains settings peuvent ne pas être disponibles
                }
            }

            // 3. Créer les overlays de blocage
            createBlockingOverlays()

            // 4. Masquer l'UI système
            hideSystemUIRadical()

            // 5. Démarrer Lock Task
            startLockTask()

            // 6. Démarrer le monitoring continu
            startSystemUIMonitoring()

            isKioskMode = true
            updateButtonText()

            Toast.makeText(this, "Mode Kiosk activé", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Erreur mode kiosk: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createBlockingOverlays() {
        // Créer l'overlay principal pour bloquer les gestes
        createMainOverlay()

        // Créer des barres blanches pour couvrir les barres système
        createSystemBarBlockers()
    }

    private fun createSystemBarBlockers() {
        try {
            // Créer une barre qui couvre TOUTE la zone du bas (y compris le bouton back)
            navBarBlocker = View(this).apply {
                setBackgroundColor(resources.getColor(android.R.color.black, theme))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    getNavigationBarHeight() + 50 // Ajouter un peu plus de hauteur
                ).apply {
                    gravity = android.view.Gravity.BOTTOM
                }

                // S'assurer que cette vue intercepte tous les touches
                isClickable = true
                isFocusable = true
                setOnTouchListener { _, _ -> true }
            }

            // Barre du haut pour la status bar
            statusBarBlocker = View(this).apply {
                setBackgroundColor(resources.getColor(android.R.color.black, theme))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    getStatusBarHeight()
                ).apply {
                    gravity = android.view.Gravity.TOP
                }
            }

            // Ajouter les barres à la fenêtre
            val rootView = findViewById<ViewGroup>(android.R.id.content)
            rootView.addView(navBarBlocker)
            rootView.addView(statusBarBlocker)

            // S'assurer qu'elles sont au premier plan
            navBarBlocker?.bringToFront()
            statusBarBlocker?.bringToFront()

        } catch (e: Exception) {
            Toast.makeText(this, "Erreur création barres noires", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        // Valeur par défaut si non trouvé
        return if (result > 0) result else 100
    }

    private fun getNavigationBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        // Valeur par défaut pour tablette Samsung
        return if (result > 0) result else 150
    }

    private fun createMainOverlay() {
        overlayView = View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                val screenHeight = resources.displayMetrics.heightPixels
                val screenWidth = resources.displayMetrics.widthPixels
                val edgeThreshold = 350 // Zone très large pour Samsung

                when {
                    event.y < edgeThreshold ||
                            event.y > screenHeight - edgeThreshold ||
                            event.x < edgeThreshold ||
                            event.x > screenWidth - edgeThreshold -> true
                    else -> false
                }
            }
        }

        // Ajouter l'overlay à la fenêtre de l'app
        val rootView = findViewById<ViewGroup>(android.R.id.content)
        val layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        rootView.addView(overlayView, layoutParams)
    }

    private fun hideSystemUIRadical() {
        // Configuration la plus agressive pour Samsung
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SECURE
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)

            window.insetsController?.let { controller ->
                // BEHAVIOR_DEFAULT au lieu de BEHAVIOR_SHOW_BARS_BY_TOUCH
                // Cela empêche complètement l'affichage des barres
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_DEFAULT

                controller.hide(
                    WindowInsets.Type.statusBars() or
                            WindowInsets.Type.navigationBars() or
                            WindowInsets.Type.systemGestures() or
                            WindowInsets.Type.mandatorySystemGestures() or
                            WindowInsets.Type.tappableElement() or
                            WindowInsets.Type.displayCutout() or
                            WindowInsets.Type.captionBar()
                )
            }

            // Listener agressif qui masque immédiatement les barres
            window.decorView.setOnApplyWindowInsetsListener { view, insets ->
                window.insetsController?.hide(
                    WindowInsets.Type.statusBars() or
                            WindowInsets.Type.navigationBars() or
                            WindowInsets.Type.systemGestures()
                )
                WindowInsets.CONSUMED
            }

        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LOW_PROFILE
                    )
        }

        supportActionBar?.hide()

        // Hack spécifique Samsung - désactiver les barres via Device Policy Manager
        if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
            try {
                // Désactiver complètement la navigation bar
                devicePolicyManager.setStatusBarDisabled(adminComponent, true)

                // Paramètres Samsung spécifiques pour masquer la navigation
                devicePolicyManager.setGlobalSetting(adminComponent, "navigation_bar_visible", "0")
                devicePolicyManager.setGlobalSetting(adminComponent, "navigation_bar_mode", "0")
                devicePolicyManager.setGlobalSetting(adminComponent, "button_order", "0")

                // Forcer la navigation gestuelle (sans boutons)
                devicePolicyManager.setSystemSetting(adminComponent, "navigation_mode", "2")

                // Désactiver les hints de gestes
                devicePolicyManager.setSystemSetting(adminComponent, "navigation_bar_gesture_hint", "0")

            } catch (e: Exception) {
                // Pas grave si ça échoue
            }
        }
    }

    private fun startSystemUIMonitoring() {
        // Monitoring continu pour re-masquer l'UI système et maintenir les barres blanches au premier plan
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isKioskMode) {
                    hideSystemUIRadical()

                    // S'assurer que les barres blanches restent au premier plan
                    statusBarBlocker?.bringToFront()
                    navBarBlocker?.bringToFront()

                    handler.postDelayed(this, 500) // Vérifier toutes les 500ms
                }
            }
        }, 500)
    }

    private fun exitKioskMode() {
        try {
            stopLockTask()

            // Arrêter le monitoring
            handler.removeCallbacksAndMessages(null)

            // Retirer tous les overlays
            overlayView?.let {
                val rootView = findViewById<ViewGroup>(android.R.id.content)
                rootView.removeView(it)
            }

            statusBarBlocker?.let {
                val rootView = findViewById<ViewGroup>(android.R.id.content)
                rootView.removeView(it)
            }

            navBarBlocker?.let {
                val rootView = findViewById<ViewGroup>(android.R.id.content)
                rootView.removeView(it)
            }

            overlayView = null
            statusBarBlocker = null
            navBarBlocker = null

            showSystemUI()

            devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf())

            isKioskMode = false
            updateButtonText()

            Toast.makeText(this, "Mode Kiosk désactivé", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Erreur désactivation: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())

            // Restaurer l'apparence normale des barres
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }

        // Restaurer les couleurs par défaut des barres système
        window.statusBarColor = resources.getColor(android.R.color.transparent, theme)
        window.navigationBarColor = resources.getColor(android.R.color.transparent, theme)

        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
            try {
                devicePolicyManager.setKeyguardDisabled(adminComponent, false)
                devicePolicyManager.setStatusBarDisabled(adminComponent, false)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun updateButtonText() {
        toggleButton.text = if (isKioskMode) {
            "DÉSACTIVER MODE KIOSK"
        } else {
            "ACTIVER MODE KIOSK"
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (isKioskMode) {
            val screenHeight = resources.displayMetrics.heightPixels
            val screenWidth = resources.displayMetrics.widthPixels
            val edgeThreshold = 350

            when {
                ev.y < edgeThreshold ||
                        ev.y > screenHeight - edgeThreshold ||
                        ev.x < edgeThreshold ||
                        ev.x > screenWidth - edgeThreshold -> {
                    // Re-masquer immédiatement l'UI système si un geste est détecté
                    hideSystemUIRadical()
                    return true
                }
                ev.pointerCount > 1 -> {
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isKioskMode) {
            hideSystemUIRadical()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isKioskMode) {
            handler.post {
                hideSystemUIRadical()
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (isKioskMode) {
            hideSystemUIRadical()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isKioskMode) {
            handler.post {
                hideSystemUIRadical()
            }
        }
    }

    override fun onBackPressed() {
        if (isKioskMode) {
            // Bloquer complètement le bouton back en mode kiosk
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)

        // Nettoyer tous les overlays
        val rootView = findViewById<ViewGroup>(android.R.id.content)

        overlayView?.let {
            rootView.removeView(it)
        }

        statusBarBlocker?.let {
            rootView.removeView(it)
        }

        navBarBlocker?.let {
            rootView.removeView(it)
        }
    }
}