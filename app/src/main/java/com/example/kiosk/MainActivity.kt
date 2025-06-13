package com.example.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TimePicker
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var webView: WebView
    private lateinit var configButton: Button
    private lateinit var powerManager: PowerManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var fullWakeLock: PowerManager.WakeLock
    private lateinit var alarmManager: AlarmManager

    private var isKioskMode = false
    private var currentUrl = "https://www.google.com"
    private val kioskPassword = "2143" // Vous pouvez le modifier
    private var isDialogShowing = false

    // Plage horaire pour maintenir l'écran allumé
    private var wakeStartHour = 8
    private var wakeStartMinute = 0
    private var wakeEndHour = 18
    private var wakeEndMinute = 0
    private var isInWakeTimeRange = false

    // SharedPreferences pour sauvegarder les paramètres
    private val PREFS_NAME = "KioskSettings"
    private val KEY_URL = "current_url"
    private val KEY_WAKE_START_HOUR = "wake_start_hour"
    private val KEY_WAKE_START_MINUTE = "wake_start_minute"
    private val KEY_WAKE_END_HOUR = "wake_end_hour"
    private val KEY_WAKE_END_MINUTE = "wake_end_minute"
    private val KEY_LAST_CACHE_CLEAR = "last_cache_clear_date"

    private lateinit var screenReceiver: BroadcastReceiver
    private lateinit var timeReceiver: BroadcastReceiver

    // Nouvelles propriétés pour le réveil automatique
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var autoWakeHandler: Handler? = null
    private var autoWakeRunnable: Runnable? = null

    // Propriétés pour la vérification périodique du cache
    private lateinit var cacheCheckHandler: Handler
    private var cacheCheckRunnable: Runnable? = null

    // Système de triple-clic pour l'admin
    private var clickCount = 0
    private var lastClickTime = 0L
    private val QUADRUPLE_CLICK_TIMEOUT = 1000L // 1 seconde pour faire 4 clics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        initializeComponents()
        loadSavedSettings()
        setupWebView()
        setupReceivers()
        checkWakeTimeRange()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun initializeComponents() {
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, KioskDeviceAdminReceiver::class.java)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Initialiser le handler pour la vérification du cache
        cacheCheckHandler = Handler(Looper.getMainLooper())

        webView = findViewById(R.id.webView)
        configButton = findViewById(R.id.configButton)

        // Wake lock PARTIAL pour maintenir le processus actif
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "KioskApp:PartialWakeLock"
        )

        // Wake lock COMPLET pour maintenir l'écran allumé
        fullWakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "KioskApp:FullWakeLock"
        )

        configButton.setOnClickListener {
            handleAdminButtonClick()
        }

        // Initialiser le style du bouton
        updateButtonStyle()
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Charger l'URL sauvegardée (ou garder la valeur par défaut)
        currentUrl = prefs.getString(KEY_URL, currentUrl) ?: currentUrl

        // Charger les horaires sauvegardés (ou garder les valeurs par défaut)
        wakeStartHour = prefs.getInt(KEY_WAKE_START_HOUR, wakeStartHour)
        wakeStartMinute = prefs.getInt(KEY_WAKE_START_MINUTE, wakeStartMinute)
        wakeEndHour = prefs.getInt(KEY_WAKE_END_HOUR, wakeEndHour)
        wakeEndMinute = prefs.getInt(KEY_WAKE_END_MINUTE, wakeEndMinute)
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // Sauvegarder l'URL
        editor.putString(KEY_URL, currentUrl)

        // Sauvegarder les horaires
        editor.putInt(KEY_WAKE_START_HOUR, wakeStartHour)
        editor.putInt(KEY_WAKE_START_MINUTE, wakeStartMinute)
        editor.putInt(KEY_WAKE_END_HOUR, wakeEndHour)
        editor.putInt(KEY_WAKE_END_MINUTE, wakeEndMinute)

        // Appliquer les changements
        editor.apply()
    }

    private fun handleAdminButtonClick() {
        // Si une boîte de dialogue est déjà affichée, ne rien faire
        if (isDialogShowing) {
            return
        }

        val currentTime = System.currentTimeMillis()

        if (isKioskMode) {
            // En mode kiosk : nécessite un triple-clic rapide
            if (currentTime - lastClickTime > QUADRUPLE_CLICK_TIMEOUT) {
                // Reset si trop de temps écoulé
                clickCount = 1
            } else {
                clickCount++
            }

            lastClickTime = currentTime

            when (clickCount) {
                1 -> {
                    // Premier clic - pas de feedback visible pour rester discret
                }
                2 -> {
                    // Deuxième clic
                }
                3 -> {
                    // Triple-clic
                }
                4 -> {
                    //  la boîte de dialogue
                    clickCount = 0
                    showPasswordDialog()
                }
            }

            // Reset automatique après timeout
            Handler(Looper.getMainLooper()).postDelayed({
                if (System.currentTimeMillis() - lastClickTime >= QUADRUPLE_CLICK_TIMEOUT) {
                    clickCount = 0
                }
            }, QUADRUPLE_CLICK_TIMEOUT)

        } else {
            // Mode normal : ouverture directe
            showConfigDialog()
        }
    }

    private fun updateButtonStyle() {
        if (isKioskMode) {
            // Mode kiosk : bouton quasi invisible
            configButton.text = "ADMIN"
            configButton.alpha = 0.0f // Légèrement plus visible pour voir le texte
            configButton.textSize = 10f // Taille légèrement plus grande

            // Couleur très discrète
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                configButton.backgroundTintList = resources.getColorStateList(android.R.color.transparent, null)
            } else {
                configButton.setBackgroundColor(resources.getColor(android.R.color.transparent))
            }
            configButton.setTextColor(resources.getColor(android.R.color.darker_gray))

            // Ajuster le padding pour un meilleur affichage du texte
            configButton.setPadding(8, 4, 8, 4)

        } else {
            // Mode normal : bouton bien visible
            configButton.text = "CONFIG"
            configButton.alpha = 1.0f
            configButton.textSize = 12f

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                configButton.backgroundTintList = resources.getColorStateList(android.R.color.holo_blue_bright, null)
            } else {
                configButton.setBackgroundColor(resources.getColor(android.R.color.holo_blue_bright))
            }
            configButton.setTextColor(resources.getColor(android.R.color.white))

            // Padding normal
            configButton.setPadding(16, 8, 16, 8)
        }
    }

    private fun setupWebView() {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return false
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                // En cas d'erreur, essayer de recharger avec cache
                if (errorCode == ERROR_UNKNOWN || description?.contains("CACHE_MISS") == true) {
                    view?.settings?.cacheMode = WebSettings.LOAD_DEFAULT
                    view?.reload()
                }
            }
        }

        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true

        // Configuration moderne du cache
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT

        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false

        // Permettre le contenu mixte
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Activer les fonctionnalités web modernes
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true

        // Vérifier si le cache doit être vidé quotidiennement
        checkDailyCacheClear()

        loadUrl(currentUrl)
    }

    private fun checkDailyCacheClear() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = getCurrentDateString()
        val lastClearDate = prefs.getString(KEY_LAST_CACHE_CLEAR, "")

        if (lastClearDate != today) {
            // Nouveau jour = vider le cache
            clearDailyCache()

            // Sauvegarder la date du jour
            prefs.edit().putString(KEY_LAST_CACHE_CLEAR, today).apply()
        }
    }

    private fun getCurrentDateString(): String {
        val calendar = Calendar.getInstance()
        return "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.MONTH)}-${calendar.get(Calendar.DAY_OF_MONTH)}"
    }

    private fun clearDailyCache() {
        try {
            // Vider le cache WebView
            webView.clearCache(true)

            // Vider aussi les données stockées localement
            webView.clearFormData()
            webView.clearHistory()

            // Pour Android 21+, vider aussi le stockage Web
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.WebStorage.getInstance().deleteAllData()
            }

            // IMPORTANT: Recharger la page après vidage du cache
            webView.reload()

        } catch (e: Exception) {
            // Log l'erreur si nécessaire
        }
    }

    // Démarrer la vérification périodique du cache
    private fun startPeriodicCacheCheck() {
        cacheCheckRunnable = object : Runnable {
            override fun run() {
                checkDailyCacheClear()
                // Revérifier toutes les heures
                cacheCheckHandler.postDelayed(this, 60 * 60 * 1000L) // 1 heure
            }
        }
        // Première vérification dans 1 heure
        cacheCheckHandler.postDelayed(cacheCheckRunnable!!, 60 * 60 * 1000L)
    }

    private fun stopPeriodicCacheCheck() {
        cacheCheckRunnable?.let { runnable ->
            cacheCheckHandler.removeCallbacks(runnable)
        }
        cacheCheckRunnable = null
    }

    private fun setupReceivers() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        if (isInWakeTimeRange && isKioskMode) {
                            // L'écran s'est éteint pendant la plage horaire - réveil immédiat
                            scheduleImmediateWakeup()
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        // Écran rallumé - maintenir allumé si dans la plage
                        cancelAutoWakeup()
                        if (isInWakeTimeRange && isKioskMode) {
                            enforceScreenAlwaysOn()
                        }
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        // Déverrouillage utilisateur
                        if (isInWakeTimeRange && isKioskMode) {
                            enforceScreenAlwaysOn()
                        }
                    }
                }
            }
        }

        timeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                checkWakeTimeRange()
                // Vérifier aussi le cache quotidiennement à chaque minute
                checkDailyCacheClear()
            }
        }

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        val timeFilter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
        }

        registerReceiver(screenReceiver, screenFilter)
        registerReceiver(timeReceiver, timeFilter)

        // Démarrer la vérification périodique du cache
        startPeriodicCacheCheck()
    }

    private fun enforceScreenAlwaysOn() {
        try {
            runOnUiThread {
                // Flags de fenêtre pour maintenir l'écran
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)

                // Empêcher la mise en veille automatique
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                    setShowWhenLocked(true)
                    setTurnScreenOn(true)
                }
            }

            // Acquérir les wake locks si pas encore fait
            if (!wakeLock.isHeld) {
                wakeLock.acquire()
            }

            if (!fullWakeLock.isHeld) {
                fullWakeLock.acquire()
            }

        } catch (e: Exception) {
            // Log l'erreur si nécessaire
        }
    }

    private fun scheduleImmediateWakeup() {
        cancelAutoWakeup()

        if (autoWakeHandler == null) {
            autoWakeHandler = Handler(Looper.getMainLooper())
        }

        autoWakeRunnable = Runnable {
            forceWakeUpScreen()

            // Vérifier si l'écran est bien allumé après 1 seconde
            Handler(Looper.getMainLooper()).postDelayed({
                if (!powerManager.isInteractive && isInWakeTimeRange && isKioskMode) {
                    // Si l'écran n'est toujours pas allumé, essayer encore
                    forceWakeUpScreen()
                }
            }, 1000)
        }

        // Réveil IMMÉDIAT (500ms seulement)
        autoWakeHandler?.postDelayed(autoWakeRunnable!!, 500)
    }

    private fun forceWakeUpScreen() {
        try {
            // Créer plusieurs wake locks très puissants
            val emergencyWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "KioskApp:EmergencyWakeUp"
            )
            emergencyWakeLock.acquire(15000) // 15 secondes

            // Wake lock additionnel pour forcer le réveil
            val fullScreenWakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "KioskApp:FullScreenWake"
            )
            fullScreenWakeLock.acquire(15000)

            runOnUiThread {
                // Flags de fenêtre agressifs
                window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.addFlags(WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON)

                // Pour Android récents
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                    setShowWhenLocked(true)
                    setTurnScreenOn(true)
                }

                // Forcer l'activité visible
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try {
                        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        activityManager.moveTaskToFront(taskId, 0)
                    } catch (e: Exception) {
                        // Ignore si pas de permission
                    }
                }
            }

            // Essayer de déclencher une Intent pour réveiller
            try {
                val wakeIntent = Intent(Intent.ACTION_SCREEN_ON)
                sendBroadcast(wakeIntent)
            } catch (e: Exception) {
                // Ignore
            }

            // Programmer un réveil récursif si ça ne marche pas
            Handler(Looper.getMainLooper()).postDelayed({
                if (!powerManager.isInteractive && isInWakeTimeRange && isKioskMode) {
                    // Encore en veille, essayer une autre méthode
                    tryAlternativeWakeup()
                }
            }, 2000)

        } catch (e: Exception) {
            // En cas d'échec, reprogrammer
            if (isInWakeTimeRange && isKioskMode) {
                Handler(Looper.getMainLooper()).postDelayed({
                    scheduleImmediateWakeup()
                }, 2000)
            }
        }
    }

    private fun tryAlternativeWakeup() {
        try {
            // Méthode alternative avec PendingIntent
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                999,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Utiliser AlarmManager pour forcer le réveil
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 500,
                pendingIntent
            )

        } catch (e: Exception) {
            // Dernière tentative dans 3 secondes
            Handler(Looper.getMainLooper()).postDelayed({
                if (!powerManager.isInteractive && isInWakeTimeRange && isKioskMode) {
                    forceWakeUpScreen()
                }
            }, 3000)
        }
    }

    private fun cancelAutoWakeup() {
        autoWakeRunnable?.let { runnable ->
            autoWakeHandler?.removeCallbacks(runnable)
        }
        autoWakeRunnable = null
    }

    private fun disableScreenSleep() {
        try {
            enforceScreenAlwaysOn()

            // Programmer un vérificateur périodique
            val handler = Handler(Looper.getMainLooper())
            val checker = object : Runnable {
                override fun run() {
                    if (isInWakeTimeRange && isKioskMode) {
                        if (!powerManager.isInteractive) {
                            forceWakeUpScreen()
                        }
                        // Revérifier toutes les 30 secondes
                        handler.postDelayed(this, 30000)
                    }
                }
            }
            handler.postDelayed(checker, 30000)

        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun showPasswordDialog() {
        isDialogShowing = true
        val dialogView = layoutInflater.inflate(R.layout.dialog_password, null)
        val passwordEdit = dialogView.findViewById<EditText>(R.id.passwordEdit)
        val okButton = dialogView.findViewById<Button>(R.id.okButton)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.setCanceledOnTouchOutside(false)

        // Forcer les coins arrondis
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Gérer les clics sur nos boutons personnalisés
        okButton.setOnClickListener {
            if (passwordEdit.text.toString() == kioskPassword) {
                dialog.dismiss()
                showConfigDialog()
            } else {
                // Mot de passe incorrect - pas de feedback
            }
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            isDialogShowing = false
        }

        dialog.show()
    }

    private fun showConfigDialog() {
        isDialogShowing = true
        val dialogView = layoutInflater.inflate(R.layout.dialog_config, null)
        val urlEdit = dialogView.findViewById<EditText>(R.id.urlEdit)
        val startTimePicker = dialogView.findViewById<TimePicker>(R.id.startTimePicker)
        val endTimePicker = dialogView.findViewById<TimePicker>(R.id.endTimePicker)
        val toggleKioskButton = dialogView.findViewById<Button>(R.id.toggleKioskButton)
        val applyButton = dialogView.findViewById<Button>(R.id.applyButton)
        val cancelConfigButton = dialogView.findViewById<Button>(R.id.cancelConfigButton)

        urlEdit.setText(currentUrl)

        // Vérifier la version Android pour TimePicker
        startTimePicker.setIs24HourView(true) // Forcer format 24h
        endTimePicker.setIs24HourView(true)   // Forcer format 24h

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            startTimePicker.hour = wakeStartHour
            startTimePicker.minute = wakeStartMinute
            endTimePicker.hour = wakeEndHour
            endTimePicker.minute = wakeEndMinute
        } else {
            @Suppress("DEPRECATION")
            startTimePicker.currentHour = wakeStartHour
            @Suppress("DEPRECATION")
            startTimePicker.currentMinute = wakeStartMinute
            @Suppress("DEPRECATION")
            endTimePicker.currentHour = wakeEndHour
            @Suppress("DEPRECATION")
            endTimePicker.currentMinute = wakeEndMinute
        }

        // État local du toggle (ne modifie pas le vrai mode immédiatement)
        var pendingKioskMode = isKioskMode

        // Fonction pour mettre à jour l'affichage du bouton
        fun updateToggleButton() {
            toggleKioskButton.text = if (pendingKioskMode) "DÉSACTIVER KIOSK" else "ACTIVER KIOSK"

            // Changer la couleur du bouton
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                toggleKioskButton.backgroundTintList = if (pendingKioskMode) {
                    resources.getColorStateList(android.R.color.holo_red_dark, null)
                } else {
                    resources.getColorStateList(android.R.color.holo_orange_dark, null)
                }
            } else {
                // Fallback pour versions plus anciennes
                toggleKioskButton.setBackgroundColor(if (pendingKioskMode) {
                    resources.getColor(android.R.color.holo_red_dark)
                } else {
                    resources.getColor(android.R.color.holo_orange_dark)
                })
            }
        }

        // Initialiser l'affichage du bouton
        updateToggleButton()

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.setCanceledOnTouchOutside(false)

        // Forcer les coins arrondis
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Gérer le bouton Appliquer
        applyButton.setOnClickListener {
            // Récupérer la nouvelle URL
            val newUrl = urlEdit.text.toString()

            // TOUJOURS vider le cache quand on applique
            currentUrl = newUrl
            loadUrl(currentUrl)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                wakeStartHour = startTimePicker.hour
                wakeStartMinute = startTimePicker.minute
                wakeEndHour = endTimePicker.hour
                wakeEndMinute = endTimePicker.minute
            } else {
                @Suppress("DEPRECATION")
                wakeStartHour = startTimePicker.currentHour
                @Suppress("DEPRECATION")
                wakeStartMinute = startTimePicker.currentMinute
                @Suppress("DEPRECATION")
                wakeEndHour = endTimePicker.currentHour
                @Suppress("DEPRECATION")
                wakeEndMinute = endTimePicker.currentMinute
            }

            // SAUVEGARDER LES NOUVEAUX PARAMÈTRES
            saveSettings()

            checkWakeTimeRange()
            scheduleWakeAlarms()

            // Appliquer le changement de mode kiosk SEULEMENT maintenant
            if (pendingKioskMode != isKioskMode) {
                if (pendingKioskMode) {
                    enterKioskMode()
                } else {
                    exitKioskMode()
                }
            }

            dialog.dismiss()
        }

        // Gérer le bouton Annuler
        cancelConfigButton.setOnClickListener {
            dialog.dismiss()
        }

        // Le bouton toggle change seulement l'état local, sans fermer la popup
        toggleKioskButton.setOnClickListener {
            if (!devicePolicyManager.isDeviceOwnerApp(packageName)) {
                return@setOnClickListener
            }

            // Inverser l'état local seulement
            pendingKioskMode = !pendingKioskMode
            updateToggleButton()
        }

        dialog.setOnDismissListener {
            isDialogShowing = false
        }

        dialog.show()
    }

    private fun toggleKioskMode() {
        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) {
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
            // Configuration des packages autorisés en lock task
            devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(packageName))

            // Masquer l'interface système
            hideSystemUI()

            // Démarrer le lock task
            startLockTask()

            isKioskMode = true

            // Mettre à jour le style du bouton pour le rendre quasi invisible
            updateButtonStyle()

            // Reset du compteur de clics
            clickCount = 0
            lastClickTime = 0L

            // Vérifier si on doit maintenir l'écran allumé
            checkWakeTimeRange()

        } catch (e: Exception) {
            // Erreur lors de l'activation du mode kiosk
        }
    }

    private fun exitKioskMode() {
        try {
            // Arrêter le lock task
            stopLockTask()

            // Réactiver l'interface système
            showSystemUI()

            // Vider la liste des packages autorisés
            devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf())

            isKioskMode = false

            // Remettre le bouton visible
            updateButtonStyle()

            // Reset du compteur de clics
            clickCount = 0
            lastClickTime = 0L

            // Annuler les réveils automatiques
            cancelAutoWakeup()

            // Libérer les wake locks si nécessaire
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
            if (fullWakeLock.isHeld) {
                fullWakeLock.release()
            }

        } catch (e: Exception) {
            // Erreur lors de la désactivation du mode kiosk
        }
    }

    private fun hideSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
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
    }

    private fun showSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun loadUrl(url: String) {
        webView.clearCache(true)
        webView.clearHistory()

        // Vérifier que l'URL a un protocole
        val finalUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }

        webView.loadUrl(finalUrl)
    }

    private fun checkWakeTimeRange() {
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentTime = currentHour * 60 + currentMinute

        val startTime = wakeStartHour * 60 + wakeStartMinute
        val endTime = wakeEndHour * 60 + wakeEndMinute

        val wasInRange = isInWakeTimeRange
        isInWakeTimeRange = if (startTime <= endTime) {
            currentTime in startTime..endTime
        } else {
            currentTime >= startTime || currentTime <= endTime
        }

        if (isKioskMode) {
            if (isInWakeTimeRange) {
                // DANS LA PLAGE - écran TOUJOURS allumé
                enforceScreenAlwaysOn()
                disableScreenSleep()

                if (!wasInRange) {
                    // Vient d'entrer dans la plage - réveil immédiat si nécessaire
                    if (!powerManager.isInteractive) {
                        forceWakeUpScreen()
                    }
                }
            } else {
                // HORS PLAGE - comportement normal
                cancelAutoWakeup()

                runOnUiThread {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
                }

                // Libérer les wake locks
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                if (fullWakeLock.isHeld) {
                    fullWakeLock.release()
                }
            }
        }
    }

    private fun scheduleWakeAlarms() {
        // Programmer des alarmes pour entrer/sortir de la plage horaire
        val startIntent = Intent(this, WakeUpReceiver::class.java)
        val endIntent = Intent(this, SleepReceiver::class.java)

        val startPendingIntent = PendingIntent.getBroadcast(this, 0, startIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val endPendingIntent = PendingIntent.getBroadcast(this, 1, endIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val startCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, wakeStartHour)
            set(Calendar.MINUTE, wakeStartMinute)
            set(Calendar.SECOND, 0)
        }

        val endCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, wakeEndHour)
            set(Calendar.MINUTE, wakeEndMinute)
            set(Calendar.SECOND, 0)
        }

        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, startCalendar.timeInMillis, AlarmManager.INTERVAL_DAY, startPendingIntent)
        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, endCalendar.timeInMillis, AlarmManager.INTERVAL_DAY, endPendingIntent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isKioskMode) {
            // Bloquer toutes les touches en mode kiosk sauf volume
            return when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN -> super.onKeyDown(keyCode, event)
                else -> true // Bloquer toutes les autres touches
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        if (isKioskMode) {
            // En mode kiosk, ne pas permettre la sortie
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            cancelAutoWakeup()
            stopPeriodicCacheCheck() // Arrêter la vérification du cache
            unregisterReceiver(screenReceiver)
            unregisterReceiver(timeReceiver)

            if (wakeLock.isHeld) {
                wakeLock.release()
            }
            if (fullWakeLock.isHeld) {
                fullWakeLock.release()
            }
            screenWakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
}