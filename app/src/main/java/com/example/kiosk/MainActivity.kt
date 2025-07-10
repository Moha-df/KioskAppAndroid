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
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.NetworkCapabilities
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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.*
import android.util.Log
import android.graphics.Bitmap

class MainActivity : AppCompatActivity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var webView: WebView
    private lateinit var configButton: Button
    private lateinit var powerManager: PowerManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var fullWakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var alarmManager: AlarmManager

    private var isKioskMode = false
    private var currentUrl = "https://www.google.com"
    private val kioskPassword = "2143"
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

    // Système de quadruple-clic pour l'admin
    private var clickCount = 0
    private var lastClickTime = 0L
    private val QUADRUPLE_CLICK_TIMEOUT = 1000L

    // Handler pour la reconnexion automatique
    private lateinit var networkHandler: Handler
    private var networkCheckRunnable: Runnable? = null
    private var lastLoadTime = 0L
    private var isPageLoaded = false
    private var isReloadingInProgress = false

    // Flag pour suivre si la dernière page a eu une erreur
    private var lastPageHadError = false

    // Gestionnaire de connexion réseau
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkReceiver: BroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        initializeComponents()
        initializeDeviceOwnerFeatures()
        applyProvisioningParameters()
        loadSavedSettings()
        setupWebView()
        setupReceivers()
        checkWakeTimeRange()

        // Démarrer la surveillance réseau
        startNetworkMonitoring()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun initializeDeviceOwnerFeatures() {
        if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
            Log.d("MainActivity", "Device Owner détecté - Initialisation ADB monitoring")

            try {
                devicePolicyManager.setPermissionGrantState(
                    adminComponent,
                    packageName,
                    android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                Log.d("MainActivity", "Permission WRITE_SECURE_SETTINGS accordée")
            } catch (e: Exception) {
                Log.w("MainActivity", "Impossible d'accorder WRITE_SECURE_SETTINGS", e)
            }

            Handler(Looper.getMainLooper()).postDelayed({
                if (!isKioskMode) {
                    Log.d("MainActivity", "Auto-activation du mode kiosk au démarrage")
                    enterKioskMode()
                }
            }, 2000)

            enableAndMaintainAdb()

            val serviceIntent = Intent(this, AdbMonitorService::class.java)
            startForegroundService(serviceIntent)
            Log.d("MainActivity", "Service ADB Monitor démarré")

        } else {
            Log.w("MainActivity", "Cette app n'est PAS Device Owner")
        }
    }

    private fun enableAndMaintainAdb() {
        try {
            Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, 1)

            try {
                Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 1)
            } catch (e: Exception) {
                Log.w("MainActivity", "Impossible d'activer adb_wifi_enabled", e)
            }

            try {
                Runtime.getRuntime().exec("setprop service.adb.tcp.port 5555")
                Runtime.getRuntime().exec("setprop service.adb.tcp.enable 1")
            } catch (e: Exception) {
                Log.w("MainActivity", "Commandes TCP échouées", e)
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur activation ADB", e)
        }
    }

    private fun initializeComponents() {
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, KioskDeviceAdminReceiver::class.java)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        cacheCheckHandler = Handler(Looper.getMainLooper())
        networkHandler = Handler(Looper.getMainLooper())

        webView = findViewById(R.id.webView)
        configButton = findViewById(R.id.configButton)

        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "KioskApp:PartialWakeLock"
        )

        fullWakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "KioskApp:FullWakeLock"
        )

        // WiFi Lock pour maintenir la connexion active
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KioskApp:WifiLock")

        configButton.setOnClickListener {
            handleAdminButtonClick()
        }

        updateButtonStyle()
    }

    private fun applyProvisioningParameters() {
        try {
            val adminExtras = intent.getBundleExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)

            if (adminExtras != null) {
                val navigationMode = adminExtras.getString("navigation_mode", "")
                if (navigationMode == "gesture") {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        Settings.Secure.putInt(contentResolver, "navigation_mode", 2)
                    }
                }
                val screenTimeout = adminExtras.getInt("screen_timeout", -1)
                if (screenTimeout > 0) {
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, screenTimeout)
                }
            }
        } catch (e: Exception) {
            // Permissions insuffisantes ou erreur
        }
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentUrl = prefs.getString(KEY_URL, currentUrl) ?: currentUrl
        wakeStartHour = prefs.getInt(KEY_WAKE_START_HOUR, wakeStartHour)
        wakeStartMinute = prefs.getInt(KEY_WAKE_START_MINUTE, wakeStartMinute)
        wakeEndHour = prefs.getInt(KEY_WAKE_END_HOUR, wakeEndHour)
        wakeEndMinute = prefs.getInt(KEY_WAKE_END_MINUTE, wakeEndMinute)
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(KEY_URL, currentUrl)
        editor.putInt(KEY_WAKE_START_HOUR, wakeStartHour)
        editor.putInt(KEY_WAKE_START_MINUTE, wakeStartMinute)
        editor.putInt(KEY_WAKE_END_HOUR, wakeEndHour)
        editor.putInt(KEY_WAKE_END_MINUTE, wakeEndMinute)
        editor.apply()
    }

    private fun handleAdminButtonClick() {
        if (isDialogShowing) {
            return
        }

        val currentTime = System.currentTimeMillis()

        if (isKioskMode) {
            if (currentTime - lastClickTime > QUADRUPLE_CLICK_TIMEOUT) {
                clickCount = 1
            } else {
                clickCount++
            }

            lastClickTime = currentTime

            if (clickCount >= 4) {
                clickCount = 0
                showPasswordDialog()
            }

            Handler(Looper.getMainLooper()).postDelayed({
                if (System.currentTimeMillis() - lastClickTime >= QUADRUPLE_CLICK_TIMEOUT) {
                    clickCount = 0
                }
            }, QUADRUPLE_CLICK_TIMEOUT)

        } else {
            showConfigDialog()
        }
    }

    private fun updateButtonStyle() {
        if (isKioskMode) {
            configButton.text = "ADMIN"
            configButton.alpha = 0.0f
            configButton.textSize = 10f

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                configButton.backgroundTintList = resources.getColorStateList(android.R.color.transparent, null)
            } else {
                configButton.setBackgroundColor(resources.getColor(android.R.color.transparent))
            }
            configButton.setTextColor(resources.getColor(android.R.color.darker_gray))
            configButton.setPadding(8, 4, 8, 4)

        } else {
            configButton.text = "CONFIG"
            configButton.alpha = 1.0f
            configButton.textSize = 12f

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                configButton.backgroundTintList = resources.getColorStateList(android.R.color.holo_blue_bright, null)
            } else {
                configButton.setBackgroundColor(resources.getColor(android.R.color.holo_blue_bright))
            }
            configButton.setTextColor(resources.getColor(android.R.color.white))
            configButton.setPadding(16, 8, 16, 8)
        }
    }

    private fun setupWebView() {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isPageLoaded = false
                lastPageHadError = false
                Log.d("MainActivity", "isPageLoaded = false (onPageStarted) pour $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!lastPageHadError) {
                    isPageLoaded = true
                } else {
                    isPageLoaded = false
                }
                isReloadingInProgress = false
                lastLoadTime = System.currentTimeMillis()
                Log.d("MainActivity", "isPageLoaded = ${isPageLoaded} (onPageFinished) pour $url")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                isPageLoaded = false
                isReloadingInProgress = false
                lastPageHadError = true
                Log.d("MainActivity", "isPageLoaded = false (onReceivedError) pour ${request?.url}")

                Log.e("MainActivity", "Erreur WebView: ${error?.description}")

                // Si c'est une erreur réseau, essayer de recharger après un délai
                if (error?.errorCode == ERROR_HOST_LOOKUP ||
                    error?.errorCode == ERROR_CONNECT ||
                    error?.errorCode == ERROR_TIMEOUT ||
                    error?.errorCode == ERROR_UNKNOWN) {

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isPageLoaded && isNetworkAvailable()) {
                            Log.d("MainActivity", "Tentative de rechargement après erreur")
                            webView.reload()
                        }
                    }, 3000)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                isPageLoaded = false
                isReloadingInProgress = false
                lastPageHadError = true
                Log.d("MainActivity", "isPageLoaded = false (onReceivedError deprecated) pour $failingUrl")

                if (errorCode == ERROR_HOST_LOOKUP ||
                    errorCode == ERROR_CONNECT ||
                    errorCode == ERROR_TIMEOUT) {

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isPageLoaded && isNetworkAvailable()) {
                            webView.reload()
                        }
                    }, 3000)
                }
            }
        }

        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true

        // Amélioration pour la gestion hors ligne
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true

        checkDailyCacheClear()
        loadUrl(currentUrl) // Pas de clearCache ici, juste charger l'URL
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo != null && networkInfo.isConnected
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun startNetworkMonitoring() {
        // Vérification périodique de la connectivité
        networkCheckRunnable = object : Runnable {
            override fun run() {
                checkNetworkAndReload()
                networkHandler.postDelayed(this, 30000) // Vérifier toutes les 30 secondes
            }
        }
        networkHandler.postDelayed(networkCheckRunnable!!, 30000)

        // Écouter les changements de réseau
        networkReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (isNetworkAvailable() && !isPageLoaded) {
                    Log.d("MainActivity", "Réseau disponible - rechargement")
                    Handler(Looper.getMainLooper()).postDelayed({
                        webView.reload()
                    }, 1000)
                }
            }
        }

        val filter = IntentFilter()
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(networkReceiver, filter)
    }

    private fun checkNetworkAndReload() {
        // Éviter les rechargements multiples
        if (isReloadingInProgress) {
            return
        }

        val timeSinceLastLoad = System.currentTimeMillis() - lastLoadTime

        Log.d("MainActivity", "Avant IF: isPageLoaded=$isPageLoaded, timeSinceLastLoad=$timeSinceLastLoad")
        if (!isPageLoaded && timeSinceLastLoad > 300000) {
            if (isNetworkAvailable()) {
                isReloadingInProgress = true
                runOnUiThread {
                    Log.d("MainActivity", "Vérification réseau - rechargement si nécessaire")
                    webView.reload()
                }
            } else {
                ensureWifiEnabled()
            }
        }
    }

    private fun ensureWifiEnabled() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            Log.d("MainActivity", "ensureWifiEnabled() appelé. isWifiEnabled=${wifiManager.isWifiEnabled}")
            if (!wifiManager.isWifiEnabled) {
                Log.d("MainActivity", "WiFi désactivé - tentative de réactivation")
                val isDeviceOwner = devicePolicyManager.isDeviceOwnerApp(packageName)
                Log.d("MainActivity", "isDeviceOwner=$isDeviceOwner")
                if (isDeviceOwner) {
                    @Suppress("DEPRECATION")
                    wifiManager.isWifiEnabled = true
                    Log.d("MainActivity", "wifiManager.isWifiEnabled = true exécuté")
                } else {
                    Log.d("MainActivity", "App n'est pas Device Owner, impossible de réactiver le WiFi")
                }
            } else {
                Log.d("MainActivity", "WiFi déjà activé, aucune action nécessaire")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur réactivation WiFi", e)
        }
    }

    private fun stopNetworkMonitoring() {
        networkCheckRunnable?.let {
            networkHandler.removeCallbacks(it)
        }
        try {
            unregisterReceiver(networkReceiver)
        } catch (e: Exception) {
            // Ignorer si déjà désenregistré
        }
    }

    private fun checkDailyCacheClear() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = getCurrentDateString()
        val lastClearDate = prefs.getString(KEY_LAST_CACHE_CLEAR, "")

        if (lastClearDate != today) {
            clearDailyCache()
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
            webView.clearFormData()
            webView.clearHistory()

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.WebStorage.getInstance().deleteAllData()
            }

            // IMPORTANT: Recharger la page après vidage du cache
            webView.reload()
            Log.d("MainActivity", "Cache quotidien vidé")

        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur lors du vidage du cache", e)
        }
    }

    private fun startPeriodicCacheCheck() {
        cacheCheckRunnable = object : Runnable {
            override fun run() {
                checkDailyCacheClear()
                cacheCheckHandler.postDelayed(this, 60 * 60 * 1000L)
            }
        }
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
                            scheduleImmediateWakeup()
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        cancelAutoWakeup()
                        if (isInWakeTimeRange && isKioskMode) {
                            enforceScreenAlwaysOn()
                        }
                    }
                    Intent.ACTION_USER_PRESENT -> {
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

        startPeriodicCacheCheck()
    }

    private fun enforceScreenAlwaysOn() {
        try {
            runOnUiThread {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                    setShowWhenLocked(true)
                    setTurnScreenOn(true)
                }
            }

            if (!wakeLock.isHeld) {
                wakeLock.acquire()
            }

            if (!fullWakeLock.isHeld) {
                fullWakeLock.acquire()
            }

            // Maintenir le WiFi actif pendant la plage horaire
            if (!wifiLock.isHeld) {
                wifiLock.acquire()
                Log.d("MainActivity", "WiFi lock acquis")
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur enforceScreenAlwaysOn", e)
        }
    }

    private fun scheduleImmediateWakeup() {
        cancelAutoWakeup()

        if (autoWakeHandler == null) {
            autoWakeHandler = Handler(Looper.getMainLooper())
        }

        autoWakeRunnable = Runnable {
            forceWakeUpScreen()

            Handler(Looper.getMainLooper()).postDelayed({
                if (!powerManager.isInteractive && isInWakeTimeRange && isKioskMode) {
                    forceWakeUpScreen()
                }
            }, 1000)
        }

        autoWakeHandler?.postDelayed(autoWakeRunnable!!, 500)
    }

    private fun forceWakeUpScreen() {
        try {
            val emergencyWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "KioskApp:EmergencyWakeUp"
            )
            emergencyWakeLock.acquire(15000)

            val fullScreenWakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "KioskApp:FullScreenWake"
            )
            fullScreenWakeLock.acquire(15000)

            runOnUiThread {
                window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.addFlags(WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON)

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                    setShowWhenLocked(true)
                    setTurnScreenOn(true)
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try {
                        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        activityManager.moveTaskToFront(taskId, 0)
                    } catch (e: Exception) {
                        // Ignore si pas de permission
                    }
                }
            }

            try {
                val wakeIntent = Intent(Intent.ACTION_SCREEN_ON)
                sendBroadcast(wakeIntent)
            } catch (e: Exception) {
                // Ignore
            }

            Handler(Looper.getMainLooper()).postDelayed({
                if (!powerManager.isInteractive && isInWakeTimeRange && isKioskMode) {
                    tryAlternativeWakeup()
                }
            }, 2000)

        } catch (e: Exception) {
            if (isInWakeTimeRange && isKioskMode) {
                Handler(Looper.getMainLooper()).postDelayed({
                    scheduleImmediateWakeup()
                }, 2000)
            }
        }
    }

    private fun tryAlternativeWakeup() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                999,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 500,
                pendingIntent
            )

        } catch (e: Exception) {
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

            val handler = Handler(Looper.getMainLooper())
            val checker = object : Runnable {
                override fun run() {
                    if (isInWakeTimeRange && isKioskMode) {
                        if (!powerManager.isInteractive) {
                            forceWakeUpScreen()
                        }
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
        val errorText = dialogView.findViewById<TextView>(R.id.errorText)
        errorText.visibility = View.GONE

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        okButton.setOnClickListener {
            errorText.visibility = View.GONE
            if (passwordEdit.text.toString() == kioskPassword) {
                dialog.dismiss()
                showConfigDialog()
            } else {
                errorText.text = "Mot de passe incorrect"
                errorText.visibility = View.VISIBLE
                passwordEdit.selectAll()

                passwordEdit.animate().translationX(10f).setDuration(100).withEndAction {
                    passwordEdit.animate().translationX(-10f).setDuration(100).withEndAction {
                        passwordEdit.animate().translationX(0f).setDuration(100)
                    }
                }
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

        startTimePicker.setIs24HourView(true)
        endTimePicker.setIs24HourView(true)

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

        var pendingKioskMode = isKioskMode

        fun updateToggleButton() {
            toggleKioskButton.text = if (pendingKioskMode) "DÉSACTIVER KIOSK" else "ACTIVER KIOSK"

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                toggleKioskButton.backgroundTintList = if (pendingKioskMode) {
                    resources.getColorStateList(android.R.color.holo_red_dark, null)
                } else {
                    resources.getColorStateList(android.R.color.holo_orange_dark, null)
                }
            } else {
                toggleKioskButton.setBackgroundColor(if (pendingKioskMode) {
                    resources.getColor(android.R.color.holo_red_dark)
                } else {
                    resources.getColor(android.R.color.holo_orange_dark)
                })
            }
        }

        updateToggleButton()

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        applyButton.setOnClickListener {
            val newUrl = urlEdit.text.toString()
            currentUrl = newUrl

            // TOUJOURS vider le cache quand on applique (comportement original)
            loadUrl(currentUrl, clearCache = true)

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

            saveSettings()
            checkWakeTimeRange()
            scheduleWakeAlarms()

            // Appliquer le changement de mode kiosk en dernier
            if (pendingKioskMode != isKioskMode) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (pendingKioskMode) {
                        enterKioskMode()
                    } else {
                        exitKioskMode()
                    }
                }, 100)
            }

            dialog.dismiss()
        }

        cancelConfigButton.setOnClickListener {
            dialog.dismiss()
        }

        toggleKioskButton.setOnClickListener {
            if (!devicePolicyManager.isDeviceOwnerApp(packageName)) {
                Toast.makeText(this, "Device Owner requis", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            pendingKioskMode = !pendingKioskMode
            updateToggleButton()
        }

        dialog.setOnDismissListener {
            isDialogShowing = false
        }

        dialog.show()
    }

    private fun enterKioskMode() {
        try {
            // S'assurer que l'activité est au premier plan
            runOnUiThread {
                devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(packageName))
                hideSystemUI()

                // Démarrer le lock task avec un délai pour éviter les problèmes
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        startLockTask()
                        isKioskMode = true
                        updateButtonStyle()
                        clickCount = 0
                        lastClickTime = 0L
                        checkWakeTimeRange()
                        Log.d("MainActivity", "Mode kiosk activé avec succès")
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Erreur startLockTask", e)
                        isKioskMode = false
                        showSystemUI()
                    }
                }, 500)
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur activation mode kiosk", e)
            isKioskMode = false
        }
    }

    private fun exitKioskMode() {
        try {
            runOnUiThread {
                // Stopper d'abord le lock task
                if (isInLockTaskMode()) {
                    stopLockTask()
                }

                // Attendre un peu avant de réinitialiser
                Handler(Looper.getMainLooper()).postDelayed({
                    showSystemUI()
                    devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf())
                    isKioskMode = false
                    updateButtonStyle()
                    clickCount = 0
                    lastClickTime = 0L
                    cancelAutoWakeup()

                    // Libérer les wake locks
                    try {
                        if (wakeLock.isHeld) {
                            wakeLock.release()
                        }
                        if (fullWakeLock.isHeld) {
                            fullWakeLock.release()
                        }
                        if (wifiLock.isHeld) {
                            wifiLock.release()
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Erreur release wake locks", e)
                    }

                    // Retirer les flags de fenêtre
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

                    Log.d("MainActivity", "Mode kiosk désactivé avec succès")
                }, 300)
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur désactivation mode kiosk", e)
        }
    }

    private fun isInLockTaskMode(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            false
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

    private fun loadUrl(url: String, clearCache: Boolean = false) {
        // Ne vider le cache que si explicitement demandé
        if (clearCache) {
            webView.clearCache(true)
            webView.clearHistory()
        }

        val finalUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }

        isPageLoaded = false
        Log.d("MainActivity", "isPageLoaded = false (loadUrl) pour $finalUrl")
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
                enforceScreenAlwaysOn()
                disableScreenSleep()

                if (!wasInRange) {
                    if (!powerManager.isInteractive) {
                        forceWakeUpScreen()
                    }
                }
            } else {
                cancelAutoWakeup()

                runOnUiThread {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
                }

                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                if (fullWakeLock.isHeld) {
                    fullWakeLock.release()
                }

                // Libérer le WiFi lock hors plage horaire
                if (wifiLock.isHeld) {
                    wifiLock.release()
                    Log.d("MainActivity", "WiFi lock libéré")
                }
            }
        }
    }

    private fun scheduleWakeAlarms() {
        val startIntent = Intent(this, WakeUpReceiver::class.java)
        val endIntent = Intent(this, SleepReceiver::class.java)

        val startPendingIntent = PendingIntent.getBroadcast(
            this, 0, startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val endPendingIntent = PendingIntent.getBroadcast(
            this, 1, endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val startCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, wakeStartHour)
            set(Calendar.MINUTE, wakeStartMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        val endCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, wakeEndHour)
            set(Calendar.MINUTE, wakeEndMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        // Utiliser setExactAndAllowWhileIdle pour un réveil précis
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startCalendar.timeInMillis, startPendingIntent)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endCalendar.timeInMillis, endPendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, startCalendar.timeInMillis, startPendingIntent)
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, endCalendar.timeInMillis, endPendingIntent)
        }

        Log.d("MainActivity", "Alarme de réveil programmée pour : ${startCalendar.time}")
        Log.d("MainActivity", "Alarme de sommeil programmée pour : ${endCalendar.time}")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isKioskMode) {
            return when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN -> super.onKeyDown(keyCode, event)
                else -> true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        if (isKioskMode) {
            return
        }
        super.onBackPressed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Si l'activité est réveillée par le WakeUpReceiver
        if (intent.getBooleanExtra("WAKE_UP_TRIGGER", false)) {
            checkWakeTimeRange()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            cancelAutoWakeup()
            stopPeriodicCacheCheck()
            stopNetworkMonitoring()

            unregisterReceiver(screenReceiver)
            unregisterReceiver(timeReceiver)

            if (wakeLock.isHeld) {
                wakeLock.release()
            }
            if (fullWakeLock.isHeld) {
                fullWakeLock.release()
            }
            if (wifiLock.isHeld) {
                wifiLock.release()
            }
            screenWakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur onDestroy", e)
        }
    }
}