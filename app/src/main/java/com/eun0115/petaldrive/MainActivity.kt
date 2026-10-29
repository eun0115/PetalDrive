package com.eun0115.petaldrive

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Build
import android.os.BatteryManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.provider.Settings
import android.provider.CallLog
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Locale
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt
import kotlin.math.hypot

class MainActivity : Activity() {

    private data class Tile(val label: Int, val icon: Int, val color: Int, val action: () -> Unit)
    private data class RoutePoint(val latitude: Double, val longitude: Double)
    private data class DrivingRoute(
        val geometry: String,
        val summary: String,
        val points: List<RoutePoint>
    )
    private data class PlaceResult(val name: String, val latitude: Double, val longitude: Double)
    private enum class PhoneTab { DIALER, RECENTS }
    private enum class RouteEndpoint { FROM, TO }

    // --- views
    private lateinit var mapView: WebView
    private lateinit var mapCard: FrameLayout
    private lateinit var dashboard: View
    private lateinit var dashboardSidePanel: LinearLayout
    private lateinit var gridPage: View
    private lateinit var zoomCol: View
    private lateinit var destBar: View
    private lateinit var destSub: TextView
    private lateinit var routeToView: EditText
    private lateinit var networkTypeView: TextView
    private lateinit var signalBarsView: SignalBarsView
    private lateinit var batteryPercentView: TextView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var art: ImageView
    private lateinit var playBtn: ImageView

    // --- map / location state
    private var lastFix: Location? = null
    private var routeOrigin: Location? = null
    private var routeOriginName: String? = null
    private var destination: Location? = null
    private var destinationName: String? = null
    private var routeEndpointSelection: RouteEndpoint? = null
    private var routeGeometry: String? = null
    private var routePoints: List<RoutePoint> = emptyList()
    private var routeSummary: String? = null
    private var navigationActive = false
    private var navigationStartPending = false
    private var rerouteInProgress = false
    private var mapPageReady = false
    private var mapErrorShown = false
    private var routeRequestId = 0
    private var locationGranted = false
    private var following = true
    private var firstFix = true
    private var offRouteFixCount = 0
    private var lastRerouteAt = 0L

    

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        // Required on API < 30, where these are abstract.
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // --- media state
    private val sessionMgr by lazy { getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager }
    private val listenerComp by lazy { ComponentName(this, MediaListener::class.java) }
    private var controller: MediaController? = null
    private var musicPage: FrameLayout? = null
    private var musicArt: ImageView? = null
    private var musicTitle: TextView? = null
    private var musicArtist: TextView? = null
    private var musicPlay: ImageView? = null
    private var lastPlayingState: Boolean? = null
    private var utilityPage: FrameLayout? = null
    private var utilityBody: FrameLayout? = null
    private var utilityScreen: String? = null
    private var phoneTabContainer: FrameLayout? = null
    private var dialerTabView: TextView? = null
    private var recentsTabView: TextView? = null
    private var phoneTab = PhoneTab.DIALER
    private var dialInput: EditText? = null
    private var pendingCallNumber: String? = null
    private val musicHandler = Handler(Looper.getMainLooper())
    private val tripEndedHandler = Handler(Looper.getMainLooper())
    private var tripEndedStatusClear: Runnable? = null
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private val routeExecutor = Executors.newSingleThreadExecutor()
    private val placeSearchExecutor = Executors.newSingleThreadExecutor()
    private var routeRequestTask: Future<*>? = null
    private var lastPlaceSearchAt = 0L
    private var requestedArtworkUri: String? = null
    private var batteryReceiverRegistered = false
    private var telephonyCallback: TelephonyCallback? = null
    private var signalMonitoringActive = false
    private var networkDisplayOverride = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryIndicator(intent)
        }
    }
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            super.onSignalStrengthsChanged(signalStrength)
            signalBarsView.setLevel(signalStrength.level)
            updateNetworkTypeLabel()
        }

        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            updateNetworkTypeLabel(networkType)
        }

        override fun onServiceStateChanged(serviceState: android.telephony.ServiceState?) {
            updateNetworkTypeLabel()
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pickController(it) }
    private val controllerCb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = renderNowPlaying()
        override fun onPlaybackStateChanged(state: PlaybackState?) = renderNowPlaying()
    }

    private fun <T : View> v(id: Int): T = findViewById(id)
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun dp(x: Float) = (x * resources.displayMetrics.density).roundToInt()

    private fun updateDashboardSidePanel() {
        if (!::dashboardSidePanel.isInitialized || dashboard.width == 0) return

        val availableWidthDp = dashboard.width / resources.displayMetrics.density
        val sidePanelWidth = dp((availableWidthDp * 0.34f).coerceIn(200f, 560f))
        val params = dashboardSidePanel.layoutParams as LinearLayout.LayoutParams
        if (params.width != sidePanelWidth || params.weight != 0f) {
            params.width = sidePanelWidth
            params.weight = 0f
            dashboardSidePanel.layoutParams = params
        }
    }

    private fun configureResponsiveNavigation() {
        val configuration = resources.configuration
        val useBottomNavigation =
            configuration.smallestScreenWidthDp >= 600 || configuration.screenWidthDp >= 960
        if (!useBottomNavigation) return

        val root = v<LinearLayout>(R.id.dashboardRoot)
        val rail = v<LinearLayout>(R.id.mainNavRail)
        val content = v<FrameLayout>(R.id.dashboardContentHost)
        root.orientation = LinearLayout.VERTICAL
        root.removeView(rail)
        root.removeView(content)
        root.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                bottomMargin = dp(8)
            }
        )
        rail.orientation = LinearLayout.HORIZONTAL
        rail.gravity = Gravity.CENTER_VERTICAL
        rail.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val status = rail.findViewById<LinearLayout>(R.id.statusIndicators)
        status.orientation = LinearLayout.HORIZONTAL
        (status.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = 0
            marginStart = 0
        }
        (status.getChildAt(1).layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = 0
            marginStart = dp(8)
        }
        status.requestLayout()
        listOf(R.id.railMusic, R.id.railPhone).forEach { id ->
            val params = rail.findViewById<View>(id).layoutParams as LinearLayout.LayoutParams
            params.topMargin = 0
            params.marginStart = dp(12)
        }
        val contentParams = content.layoutParams as LinearLayout.LayoutParams
        contentParams.marginStart = 0
        content.layoutParams = contentParams
        root.addView(rail)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        hideSystemBars()
        configureResponsiveNavigation()

        dashboard = v(R.id.dashboard)
        dashboardSidePanel = v(R.id.dashboardSidePanel)
        dashboard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDashboardSidePanel()
        }
        gridPage = v(R.id.gridPage)
        mapCard = v(R.id.mapCard)
        mapCard.clipToOutline = true
        mapCard.outlineProvider = ViewOutlineProvider.BACKGROUND
        zoomCol = v(R.id.zoomCol)
        destBar = v(R.id.destBar)
        destSub = v(R.id.destSub)
        routeToView = v(R.id.routeTo)
        networkTypeView = v(R.id.networkType)
        signalBarsView = v(R.id.signalBars)
        batteryPercentView = v(R.id.batteryPercent)
        trackTitle = v(R.id.trackTitle)
        trackArtist = v(R.id.trackArtist)
        art = v(R.id.art)
        art.clipToOutline = true
        playBtn = v(R.id.btnPlay)

        mapView = v(R.id.mapView)
        mapView.clipToOutline = true
        mapView.outlineProvider = ViewOutlineProvider.BACKGROUND
        configureMapView()

        // left rail
        v<View>(R.id.railMaps).setOnClickListener { showDashboard() }
        v<View>(R.id.railMusic).setOnClickListener { openMusicPage() }
        v<View>(R.id.mediaCard).setOnClickListener { openMusicPage() }
        v<View>(R.id.railPhone).setOnClickListener { openPhonePage() }
        v<View>(R.id.railHome).setOnClickListener {
            if (gridPage.visibility == View.VISIBLE) showDashboard() else showGrid()
        }

        // map controls
        v<View>(R.id.zoomIn).setOnClickListener { runMapScript("window.petalDriveMap.zoomBy(1)") }
        v<View>(R.id.zoomOut).setOnClickListener { runMapScript("window.petalDriveMap.zoomBy(-1)") }
        v<View>(R.id.recenter).setOnClickListener {
            following = true
            lastFix?.let { center(it, 16f) }
        }
        v<View>(R.id.destGo).setOnClickListener {
            val query = routeToView.text.toString().trim()
            if (query.isNotEmpty() && query != destinationName) {
                searchPlaces(query, RouteEndpoint.TO)
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(routeToView.windowToken, 0)
                routeToView.clearFocus()
            } else if (destination != null && routeOriginForNavigation() != null) {
                startRouteOnMap()
            } else {
                val message = if (destination == null) {
                    R.string.route_choose_destination
                } else {
                    R.string.waiting_gps
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
        v<View>(R.id.destEnd).setOnClickListener { stopBuiltInNavigation() }
        v<View>(R.id.destClear).setOnClickListener { clearRouteEndpoints() }
        routeToView.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = routeToView.text.toString().trim()
                if (query.isNotEmpty()) {
                    searchPlaces(query, RouteEndpoint.TO)
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(routeToView.windowToken, 0)
                    routeToView.clearFocus()
                }
                true
            } else {
                false
            }
        }
        routeToView.setOnLongClickListener {
            selectRouteEndpoint(RouteEndpoint.TO)
            Toast.makeText(this, R.string.pick_to_map, Toast.LENGTH_SHORT).show()
            true
        }
        v<View>(R.id.statusIndicators).apply {
            contentDescription = getString(R.string.enable_signal_details)
            setOnClickListener { requestSignalPermission() }
        }

        // cards
        v<View>(R.id.btnPlay).setOnClickListener {
            controller?.let { c ->
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
                else c.transportControls.play()
            } ?: askMediaAccess()
        }
        v<View>(R.id.btnNext).setOnClickListener { controller?.transportControls?.skipToNext() }
        v<View>(R.id.btnPrev).setOnClickListener { controller?.transportControls?.skipToPrevious() }
        buildGrid()
        renderNowPlaying()
        ensureLocation()
        updateBatteryIndicator(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        updateNetworkTypeLabel()
        updateDestBar()
    }

    private fun configureMapView() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        mapView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        mapView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ) = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                if (request.url.scheme == "https" &&
                    request.url.host == "www.openstreetmap.org"
                ) {
                    openIntent(Intent(Intent.ACTION_VIEW, request.url))
                }
                return true
            }
        }
        mapView.addJavascriptInterface(MapBridge(), "PetalDrive")
        mapView.loadUrl("https://appassets.androidplatform.net/assets/map/index.html")
    }

    inner class MapBridge {
        @JavascriptInterface
        fun onMapReady() {
            runOnUiThread {
                mapPageReady = true
                lastFix?.let { center(it, 16f) }
                routeOrigin?.let { origin ->
                    runMapScript("window.petalDriveMap.setOrigin(${origin.latitude},${origin.longitude})")
                }
                destination?.let { dest ->
                    runMapScript("window.petalDriveMap.setDestination(${dest.latitude},${dest.longitude})")
                }
                routeGeometry?.let { runMapScript("window.petalDriveMap.setRoute($it)") }
                mapView.postDelayed({ runMapScript("window.petalDriveMap.invalidateSize()") }, 200L)
            }
        }

        @JavascriptInterface
        fun onMapPointSelected(latitude: Double, longitude: Double) {
            runOnUiThread {
                if (latitude.isFinite() && longitude.isFinite() &&
                    latitude in -90.0..90.0 && longitude in -180.0..180.0
                ) {
                    when (routeEndpointSelection ?: RouteEndpoint.TO) {
                        RouteEndpoint.FROM -> setRouteOrigin(latitude, longitude)
                        RouteEndpoint.TO -> setDestination(latitude, longitude)
                    }
                }
            }
        }

        @JavascriptInterface
        fun onTileError() {
            runOnUiThread {
                if (!mapErrorShown) {
                    mapErrorShown = true
                    Toast.makeText(
                        this@MainActivity,
                        R.string.map_tiles_unavailable,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun runMapScript(script: String) {
        if (mapPageReady) mapView.evaluateJavascript(script, null)
    }

    private fun updateBatteryIndicator(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) {
            val percent = (level * 100f / scale).roundToInt().coerceIn(0, 100)
            batteryPercentView.text = getString(R.string.battery, percent)
            batteryPercentView.contentDescription = getString(R.string.battery_accessibility, percent)
        }
    }

    private fun requestSignalPermission() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            Toast.makeText(this, R.string.network_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            startSignalMonitoring()
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setMessage(R.string.signal_permission_explanation)
            .setPositiveButton(R.string.continue_label) { _, _ ->
                requestPermissions(
                    arrayOf(Manifest.permission.READ_PHONE_STATE),
                    REQ_PHONE_STATE
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startSignalMonitoring() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        ) {
            updateNetworkTypeLabel()
            return
        }
        if (signalMonitoringActive) return
        val telephony = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(),
                    TelephonyCallback.SignalStrengthsListener,
                    TelephonyCallback.DisplayInfoListener,
                    TelephonyCallback.DataConnectionStateListener,
                    TelephonyCallback.ServiceStateListener {
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                        signalBarsView.setLevel(signalStrength.level)
                        updateNetworkTypeLabel()
                    }

                    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                        networkDisplayOverride = telephonyDisplayInfo.overrideNetworkType
                        updateNetworkTypeLabel()
                    }

                    override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                        updateNetworkTypeLabel(networkType)
                    }

                    override fun onServiceStateChanged(serviceState: android.telephony.ServiceState) {
                        updateNetworkTypeLabel()
                    }
                }
                telephony.registerTelephonyCallback(
                    androidx.core.content.ContextCompat.getMainExecutor(this),
                    callback
                )
                telephonyCallback = callback
            } else {
                telephony.listen(
                    phoneStateListener,
                    PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                        PhoneStateListener.LISTEN_DATA_CONNECTION_STATE or
                        PhoneStateListener.LISTEN_SERVICE_STATE
                )
            }
            signalMonitoringActive = true
            updateNetworkTypeLabel(telephony.dataNetworkType)
        } catch (e: SecurityException) {
            Log.w(TAG, "Phone status permission was revoked", e)
            signalBarsView.setLevel(0)
            updateNetworkTypeLabel()
        }
    }

    private fun stopSignalMonitoring() {
        if (!signalMonitoringActive) return
        val telephony = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let(telephony::unregisterTelephonyCallback)
            telephonyCallback = null
        } else {
            telephony.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        }
        signalMonitoringActive = false
    }

    private fun updateNetworkTypeLabel(networkType: Int? = null) {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)

        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            val telephony = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val type = networkType ?: try {
                telephony.dataNetworkType
            } catch (e: SecurityException) {
                Log.w(TAG, "Unable to read mobile network type", e)
                TelephonyManager.NETWORK_TYPE_UNKNOWN
            }
            val overrideLabel = when (networkDisplayOverride) {
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G+"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "4G+"
                else -> null
            }
            val label = overrideLabel ?: when (type) {
                TelephonyManager.NETWORK_TYPE_GPRS -> "G"
                TelephonyManager.NETWORK_TYPE_EDGE -> "E"
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                TelephonyManager.NETWORK_TYPE_EHRPD,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA -> "3G"
                TelephonyManager.NETWORK_TYPE_HSPAP -> "H+"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                else -> null
            }
            if (label != null) {
                networkTypeView.text = label
                return
            }
        }

        networkTypeView.text = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> ""
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                getString(R.string.network_cellular)
            else -> getString(R.string.network_unknown)
        }
    }

    override fun onStart() { super.onStart() }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, batteryFilter)
        batteryReceiverRegistered = true
        startSignalMonitoring()
        startLocationUpdates()
        hookMedia()
        MediaListener.onMessageNotificationsChanged = {
            if (utilityScreen == "messages") refreshMessagesPage()
        }
        if (utilityScreen == "messages") refreshMessagesPage()
    }

    override fun onPause() {
        MediaListener.onMessageNotificationsChanged = null
        stopLocationUpdates()
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver)
            batteryReceiverRegistered = false
        }
        stopSignalMonitoring()
        unhookMedia()
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() { super.onStop() }
    override fun onDestroy() {
        clearTripEndedStatusTimeout()
        artworkExecutor.shutdownNow()
        placeSearchExecutor.shutdownNow()
        routeRequestId++
        routeRequestTask?.cancel(true)
        routeExecutor.shutdownNow()
        mapView.removeJavascriptInterface("PetalDrive")
        mapView.destroy()
        super.onDestroy()
    }
    override fun onLowMemory() { super.onLowMemory() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Back returns to the previous PetalDrive screen")
    override fun onBackPressed() {
        when {
            utilityPage != null -> closeUtilityPage()
            musicPage != null -> closeMusicPage()
            gridPage.visibility == View.VISIBLE -> showDashboard()
            else -> super.onBackPressed()
        }
    }

    /** Touching the map by hand turns off follow-mode (but tapping the overlay buttons doesn't). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (utilityPage == null && musicPage == null &&
            ev.actionMasked == MotionEvent.ACTION_DOWN &&
            dashboard.visibility == View.VISIBLE
        ) {
            val x = ev.rawX.toInt()
            val y = ev.rawY.toInt()
            if (hit(mapView, x, y) && !hit(zoomCol, x, y) && !hit(destBar, x, y)) {
                following = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hit(view: View, x: Int, y: Int): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val r = Rect()
        return view.getGlobalVisibleRect(r) && r.contains(x, y)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ------------------------------------------------------------------ map

    private fun center(loc: Location, zoom: Float? = null) {
        runMapScript(
            "window.petalDriveMap.setUserLocation(" +
                "${loc.latitude},${loc.longitude},true,${zoom ?: "null"})"
        )
    }

    private fun updateUserLocation(loc: Location) {
        runMapScript(
            "window.petalDriveMap.setUserLocation(${loc.latitude},${loc.longitude},false,null)"
        )
    }

    private fun setDestination(
        latitude: Double,
        longitude: Double,
        name: String = getString(R.string.pinned_point)
    ) {
        clearTripEndedStatusTimeout()
        val dest = Location("map").apply {
            this.latitude = latitude
            this.longitude = longitude
        }
        destination = dest
        destinationName = name
        routeGeometry = null
        routePoints = emptyList()
        routeSummary = null
        navigationActive = false
        navigationStartPending = false
        rerouteInProgress = false
        routeEndpointSelection = null
        following = false
        runMapScript("window.petalDriveMap.setDestination($latitude,$longitude)")
        updateDestBar()
        routeOriginForNavigation()?.let { requestDrivingRoute(it, dest) }
    }

    private fun setRouteOrigin(latitude: Double, longitude: Double, name: String = getString(R.string.pinned_point)) {
        clearTripEndedStatusTimeout()
        val origin = Location("map").apply {
            this.latitude = latitude
            this.longitude = longitude
        }
        routeOrigin = origin
        routeOriginName = name
        routeGeometry = null
        routePoints = emptyList()
        routeSummary = null
        navigationActive = false
        navigationStartPending = false
        rerouteInProgress = false
        routeEndpointSelection = null
        following = false
        runMapScript("window.petalDriveMap.setOrigin($latitude,$longitude)")
        updateDestBar()
        destination?.let { requestDrivingRoute(origin, it) }
    }

    private fun clearRouteEndpoints() {
        clearTripEndedStatusTimeout()
        routeRequestId++
        routeRequestTask?.cancel(true)
        routeRequestTask = null
        routeOrigin = null
        routeOriginName = null
        destination = null
        destinationName = null
        routeGeometry = null
        routePoints = emptyList()
        routeSummary = null
        navigationActive = false
        navigationStartPending = false
        rerouteInProgress = false
        routeEndpointSelection = null
        routeToView.clearFocus()
        routeToView.text.clear()
        runMapScript("window.petalDriveMap.clearOrigin()")
        runMapScript("window.petalDriveMap.clearDestination()")
        updateDestBar()
    }

    private fun routeOriginForNavigation() = routeOrigin ?: lastFix

    private fun selectRouteEndpoint(endpoint: RouteEndpoint) {
        routeEndpointSelection = endpoint
        updateDestBar()
    }

    private fun searchPlaces(query: String, endpoint: RouteEndpoint) {
        Toast.makeText(this, R.string.place_searching, Toast.LENGTH_SHORT).show()
        placeSearchExecutor.execute {
            try {
                val results = fetchPlaces(query)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (results.isEmpty()) {
                        Toast.makeText(this, R.string.place_no_results, Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val labels = results.map { it.name }.toTypedArray()
                    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle(R.string.search_place_title)
                        .setItems(labels) { _, index ->
                            val place = results[index]
                            routeToView.clearFocus()
                            when (endpoint) {
                                RouteEndpoint.FROM ->
                                    setRouteOrigin(place.latitude, place.longitude, place.name)
                                RouteEndpoint.TO ->
                                    setDestination(place.latitude, place.longitude, place.name)
                            }
                            runMapScript(
                                "window.petalDriveMap.focusLocation(${place.latitude},${place.longitude},13)"
                            )
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            } catch (e: IOException) {
                Log.w(TAG, "Nominatim place search failed", e)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val message = if (e.message?.contains("429") == true) {
                        R.string.place_search_rate_limited
                    } else {
                        R.string.place_search_failed
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            } catch (e: JSONException) {
                Log.w(TAG, "Could not read Nominatim search results", e)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, R.string.place_search_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun fetchPlaces(query: String): List<PlaceResult> {
        val elapsed = android.os.SystemClock.elapsedRealtime() - lastPlaceSearchAt
        if (elapsed < NOMINATIM_MIN_INTERVAL_MS) {
            try {
                Thread.sleep(NOMINATIM_MIN_INTERVAL_MS - elapsed)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Place search was interrupted", e)
            }
        }
        lastPlaceSearchAt = android.os.SystemClock.elapsedRealtime()

        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val connection = URL(
            "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q=$encodedQuery"
        ).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 18_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "User-Agent",
                "PetalDrive/0.1 (com.eun0115.petaldrive)"
            )
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw IOException("Nominatim returned HTTP $statusCode")
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONArray(response)
            return (0 until json.length()).mapNotNull { index ->
                val result = json.getJSONObject(index)
                val latitude = result.getString("lat").toDoubleOrNull()
                val longitude = result.getString("lon").toDoubleOrNull()
                val name = result.optString("display_name").trim()
                if (latitude == null || longitude == null || name.isEmpty() ||
                    latitude !in -90.0..90.0 || longitude !in -180.0..180.0
                ) {
                    null
                } else {
                    PlaceResult(name, latitude, longitude)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun swapRouteEndpoints() {
        val currentDestination = destination
        val currentOrigin = routeOriginForNavigation()
        if (currentDestination == null || currentOrigin == null) {
            Toast.makeText(this, R.string.route_need_points, Toast.LENGTH_SHORT).show()
            return
        }

        routeOrigin = currentDestination
        val currentOriginName = routeOriginName ?: getString(R.string.current_location)
        routeOriginName = destinationName ?: getString(R.string.pinned_point)
        destination = currentOrigin
        destinationName = currentOriginName
        routeGeometry = null
        routePoints = emptyList()
        routeSummary = null
        navigationActive = false
        navigationStartPending = false
        rerouteInProgress = false
        routeEndpointSelection = null
        runMapScript("window.petalDriveMap.setOrigin(${currentDestination.latitude},${currentDestination.longitude})")
        runMapScript("window.petalDriveMap.setDestination(${currentOrigin.latitude},${currentOrigin.longitude})")
        requestDrivingRoute(currentDestination, currentOrigin)
        updateDestBar()
    }

    private fun startRouteOnMap() {
        val origin = routeOriginForNavigation() ?: return
        val dest = destination ?: return
        if (routeGeometry == null) {
            navigationStartPending = true
            navigationActive = false
            if (routeSummary != getString(R.string.finding_route)) {
                requestDrivingRoute(origin, dest)
            }
            updateDestBar()
            focusRoute(origin)
            return
        }

        navigationStartPending = false
        navigationActive = true
        following = true
        updateDestBar()
        focusRoute(origin)
    }

    private fun focusRoute(origin: Location) {
        val focus = lastFix ?: origin
        mapView.postDelayed({
            runMapScript(
                "window.petalDriveMap.focusLocation(" +
                    "${focus.latitude},${focus.longitude},17)"
            )
        }, 250L)
    }

    private fun stopBuiltInNavigation() {
        clearTripEndedStatusTimeout()
        routeRequestId++
        routeRequestTask?.cancel(true)
        routeRequestTask = null
        navigationActive = false
        navigationStartPending = false
        rerouteInProgress = false
        following = false
        offRouteFixCount = 0
        routeGeometry = null
        routePoints = emptyList()
        routeSummary = getString(R.string.trip_ended)
        runMapScript("window.petalDriveMap.clearRoute()")
        updateDestBar()
        val clearStatus = Runnable {
            tripEndedStatusClear = null
            if (!isFinishing && !isDestroyed &&
                !navigationActive &&
                routeSummary == getString(R.string.trip_ended)
            ) {
                routeSummary = getString(R.string.route_ready)
                updateDestBar()
            }
        }
        tripEndedStatusClear = clearStatus
        tripEndedHandler.postDelayed(clearStatus, TRIP_ENDED_STATUS_DURATION_MS)
    }

    private fun clearTripEndedStatusTimeout() {
        tripEndedStatusClear?.let(tripEndedHandler::removeCallbacks)
        tripEndedStatusClear = null
    }

    private fun checkForOffRoute(location: Location) {
        if (!navigationActive || navigationStartPending || routePoints.size < 2) return
        if (!location.hasAccuracy() || location.accuracy > MAX_REROUTE_ACCURACY_METERS) {
            offRouteFixCount = 0
            return
        }

        if (distanceToRouteMeters(location) <= OFF_ROUTE_THRESHOLD_METERS) {
            offRouteFixCount = 0
            return
        }

        offRouteFixCount++
        if (offRouteFixCount < OFF_ROUTE_FIXES_REQUIRED ||
            rerouteInProgress || routeRequestTask?.isDone == false
        ) return

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRerouteAt < REROUTE_COOLDOWN_MS) return
        val dest = destination ?: return
        lastRerouteAt = now
        offRouteFixCount = 0
        rerouteInProgress = true
        requestDrivingRoute(location, dest, isReroute = true)
    }

    private fun distanceToRouteMeters(location: Location): Double {
        val latitudeScale = Math.toRadians(1.0) * EARTH_RADIUS_METERS
        val longitudeScale = latitudeScale * kotlin.math.cos(Math.toRadians(location.latitude))
        var shortestDistance = Double.POSITIVE_INFINITY
        var previous = routePoints.first()

        for (index in 1 until routePoints.size) {
            val current = routePoints[index]
            val startX = (previous.longitude - location.longitude) * longitudeScale
            val startY = (previous.latitude - location.latitude) * latitudeScale
            val endX = (current.longitude - location.longitude) * longitudeScale
            val endY = (current.latitude - location.latitude) * latitudeScale
            val deltaX = endX - startX
            val deltaY = endY - startY
            val segmentLengthSquared = deltaX * deltaX + deltaY * deltaY
            val projection = if (segmentLengthSquared == 0.0) {
                0.0
            } else {
                (-(startX * deltaX + startY * deltaY) / segmentLengthSquared).coerceIn(0.0, 1.0)
            }
            val closestX = startX + projection * deltaX
            val closestY = startY + projection * deltaY
            shortestDistance = minOf(shortestDistance, hypot(closestX, closestY))
            previous = current
        }
        return shortestDistance
    }

    private fun requestDrivingRoute(origin: Location, dest: Location, isReroute: Boolean = false) {
        clearTripEndedStatusTimeout()
        val requestId = ++routeRequestId
        routeRequestTask?.cancel(true)
        val previousSummary = routeSummary
        routeSummary = getString(if (isReroute) R.string.rerouting else R.string.finding_route)
        updateDestBar()
        routeRequestTask = routeExecutor.submit {
            try {
                val route = fetchDrivingRoute(origin, dest)
                runOnUiThread {
                    if (requestId != routeRequestId || destination !== dest) return@runOnUiThread
                    if (isReroute) rerouteInProgress = false
                    routeGeometry = route.geometry
                    routePoints = route.points
                    offRouteFixCount = 0
                    routeSummary = route.summary
                    runMapScript("window.petalDriveMap.setRoute(${route.geometry},${!isReroute})")
                    if (navigationStartPending) {
                        navigationStartPending = false
                        navigationActive = true
                        following = true
                        focusRoute(origin)
                    }
                    updateDestBar()
                }
            } catch (e: IOException) {
                Log.w(TAG, "OSRM route request failed", e)
                showRouteError(requestId, dest, isReroute, previousSummary)
            } catch (e: JSONException) {
                Log.w(TAG, "Could not read OSRM route response", e)
                showRouteError(requestId, dest, isReroute, previousSummary)
            }
        }
    }

    private fun fetchDrivingRoute(origin: Location, dest: Location): DrivingRoute {
        val url = String.format(
            Locale.US,
            "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=false",
            origin.longitude,
            origin.latitude,
            dest.longitude,
            dest.latitude
        )
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 18_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "PetalDrive/1.0 (Android)")
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) throw IOException("OSRM returned HTTP $statusCode")

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            if (json.optString("code") != "Ok") {
                throw IOException("OSRM did not find a drivable route")
            }
            val route = json.getJSONArray("routes").getJSONObject(0)
            val distance = route.getDouble("distance")
            val minutes = (route.getDouble("duration") / 60.0).roundToInt()
            val durationText = if (minutes < 60) {
                "$minutes min"
            } else {
                "${minutes / 60} hr ${minutes % 60} min"
            }
            val distanceKm = String.format(Locale.getDefault(), "%.1f", distance / 1000.0)
            val summary = getString(R.string.route_estimate, durationText, distanceKm)
            val coordinates = route.getJSONObject("geometry").getJSONArray("coordinates")
            val points = buildList(coordinates.length()) {
                for (index in 0 until coordinates.length()) {
                    val coordinate = coordinates.getJSONArray(index)
                    add(RoutePoint(coordinate.getDouble(1), coordinate.getDouble(0)))
                }
            }
            if (points.size < 2) throw IOException("OSRM route geometry is incomplete")
            return DrivingRoute(coordinates.toString(), summary, points)
        } finally {
            connection.disconnect()
        }
    }

    private fun showRouteError(
        requestId: Int,
        dest: Location,
        isReroute: Boolean,
        previousSummary: String?
    ) {
        runOnUiThread {
            if (requestId != routeRequestId || destination !== dest) return@runOnUiThread
            if (isReroute) rerouteInProgress = false
            if (isReroute) {
                routeSummary = previousSummary ?: getString(R.string.route_unavailable)
            } else {
                routeGeometry = null
                routePoints = emptyList()
                routeSummary = getString(R.string.route_unavailable)
                navigationStartPending = false
                navigationActive = false
                runMapScript("window.petalDriveMap.clearRoute()")
            }
            updateDestBar()
            Toast.makeText(this, R.string.route_request_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateDestBar() {
        destBar.visibility = View.VISIBLE
        if (!routeToView.hasFocus()) {
            routeToView.setText(
                when {
                    routeEndpointSelection == RouteEndpoint.TO -> ""
                    else -> destinationName.orEmpty()
                }
            )
        }
        routeToView.contentDescription = getString(R.string.search_to)

        val goButton = v<View>(R.id.destGo)
        goButton.isEnabled = !navigationActive
        goButton.alpha = if (goButton.isEnabled) 1f else 0.55f
        (goButton as? TextView)?.setText(
            when {
                navigationStartPending -> R.string.starting_route
                else -> R.string.go
            }
        )
        goButton.visibility = if (navigationActive) View.GONE else View.VISIBLE
        v<View>(R.id.destEnd).visibility = if (navigationActive) View.VISIBLE else View.GONE
        v<View>(R.id.destClear).visibility =
            if (destination != null && !navigationActive) View.VISIBLE else View.GONE

        destBar.visibility = View.VISIBLE
        destSub.text = when {
            routeEndpointSelection == RouteEndpoint.FROM -> getString(R.string.pick_from_map)
            routeEndpointSelection == RouteEndpoint.TO -> getString(R.string.pick_to_map)
            navigationStartPending && routeSummary == getString(R.string.finding_route) ->
                getString(R.string.finding_route)
            routeSummary != null && routeGeometry != null -> routeSummary
            routeSummary == getString(R.string.finding_route) -> getString(R.string.finding_route)
            routeSummary != null -> routeSummary
            destination == null -> getString(R.string.route_choose_destination)
            routeOriginForNavigation() == null -> getString(R.string.waiting_gps)
            else -> getString(R.string.finding_route)
        }
        val hasRouteEstimate = routeGeometry != null && routeSummary != null
        destSub.setTextColor(
            getColor(if (hasRouteEstimate) R.color.text_primary else R.color.text_secondary)
        )
        destSub.textSize = if (hasRouteEstimate) 14f else 12f
        destSub.setTypeface(
            null,
            if (hasRouteEstimate) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
    }

    // ------------------------------------------------------------------ location

    private fun ensureLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            onLocationGranted()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_LOCATION -> if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                onLocationGranted()
            }
            REQ_CALL_LOG -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    showPhoneTab(PhoneTab.RECENTS)
                } else {
                    Toast.makeText(this, "Call history permission was not granted.", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_CALL_PHONE -> {
                val number = pendingCallNumber
                pendingCallNumber = null
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && number != null) {
                    placeCall(number)
                } else {
                    Toast.makeText(this, "Phone permission was not granted.", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_PHONE_STATE -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    startSignalMonitoring()
                } else {
                    updateNetworkTypeLabel()
                }
            }
        }
    }

    private fun onLocationGranted() {
        locationGranted = true
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (!locationGranted) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!lm.isProviderEnabled(p)) continue
                lm.requestLocationUpdates(p, 1500L, 3f, locListener)
                if (lastFix == null) lm.getLastKnownLocation(p)?.let { onFix(it) }
            } catch (e: SecurityException) {
                // e.g. only coarse permission granted: GPS provider is refused, network still works
            }
        }
    }

    private fun stopLocationUpdates() {
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locListener)
    }

    private fun onFix(loc: Location) {
        lastFix = loc
        if (firstFix) {
            firstFix = false
            center(loc, 16f)
        } else {
            updateUserLocation(loc)
            if (following) center(loc)
        }
        val dest = destination
        if (dest != null && routeSummary == null) {
            routeOriginForNavigation()?.let { requestDrivingRoute(it, dest) }
        }
        checkForOffRoute(loc)
        updateDestBar()
    }

    private fun showSearch() {
        val input = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.where_to)
            .setView(input)
            .setPositiveButton(R.string.search) { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) {
                    openIntent(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.petalmaps.com/search/?q=${Uri.encode(q)}&utm_source=$packageName")))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ app grid

    private fun showDashboard() = transitionScreens(
        from = gridPage,
        to = dashboard,
        enterFrom = -dp(18).toFloat()
    )

    private fun showGrid() = transitionScreens(
        from = dashboard,
        to = gridPage,
        enterFrom = dp(18).toFloat()
    )

    private fun transitionScreens(from: View, to: View, enterFrom: Float) {
        if (to.visibility == View.VISIBLE) return

        from.animate().cancel()
        to.animate().cancel()
        from.visibility = View.VISIBLE
        from.alpha = 1f
        from.translationX = 0f
        to.alpha = 0f
        to.translationX = enterFrom
        to.visibility = View.VISIBLE
        to.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(220)
            .start()
        from.animate()
            .alpha(0f)
            .translationX(-enterFrom / 2f)
            .setDuration(180)
            .withEndAction {
                from.visibility = View.GONE
                from.alpha = 1f
                from.translationX = 0f
            }
            .start()
    }

    private fun animateArtwork(view: ImageView, bitmap: android.graphics.Bitmap?) {
        view.animate().cancel()
        view.alpha = 0f
        if (bitmap == null) {
            view.scaleType = ImageView.ScaleType.CENTER_INSIDE
            view.setPadding(dp(14), dp(14), dp(14), dp(14))
            view.setImageResource(R.drawable.ic_music)
        } else {
            view.scaleType = if (view === art) {
                ImageView.ScaleType.CENTER_CROP
            } else {
                ImageView.ScaleType.FIT_CENTER
            }
            view.setPadding(0, 0, 0, 0)
            view.setImageBitmap(bitmap)
        }
        view.animate().alpha(1f).setDuration(220).start()
    }

    private fun animatePlaybackChange(view: ImageView, playing: Boolean) {
        view.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        if (lastPlayingState != null && lastPlayingState != playing) {
            view.animate().cancel()
            view.scaleX = 0.8f
            view.scaleY = 0.8f
            view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .start()
        }
    }

    private fun buildGrid() {
        val grid = v<GridLayout>(R.id.appGrid)
        val tiles = listOf(
            Tile(R.string.maps, R.drawable.ic_navigation, 0xFF0A84FF.toInt()) { showDashboard() },
            Tile(R.string.music, R.drawable.ic_music, 0xFFFF375F.toInt()) { openMusicApp() },
            Tile(R.string.phone, R.drawable.ic_phone, 0xFF30D158.toInt()) { openPhonePage() },
            Tile(R.string.messages, R.drawable.ic_message, 0xFF30D158.toInt()) { openMessagesPage() },
            Tile(R.string.settings, R.drawable.ic_settings, 0xFF8E8E93.toInt()) {
                openIntent(Intent(Settings.ACTION_SETTINGS))
            }
        )
        tiles.forEach { grid.addView(makeTile(it)) }
    }

    private fun makeTile(t: Tile): View {
        val scale = (resources.configuration.smallestScreenWidthDp / 700f).coerceIn(1f, 1.6f)
        val iconSize = dp(72f * scale)
        val icon = ImageView(this).apply {
            setImageResource(t.icon)
            val iconPadding = dp(18f * scale)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            background = GradientDrawable().apply {
                cornerRadius = dp(22f * scale).toFloat()
                setColor(t.color)
            }
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        }
        val label = TextView(this).apply {
            setText(t.label)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f * scale
            gravity = Gravity.CENTER
            setPadding(0, dp(6f * scale), 0, 0)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val horizontalPadding = dp(14f * scale)
            val verticalPadding = dp(10f * scale)
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            addView(icon)
            addView(label)
            setOnClickListener { t.action() }
        }
    }

    private fun createUtilityPage(title: String, screen: String): FrameLayout {
        utilityPage?.let {
            it.animate().cancel()
            (it.parent as? ViewGroup)?.removeView(it)
        }
        val page = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(12, 12, 15))
            isFocusableInTouchMode = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = getString(R.string.cancel)
            isClickable = true
            isFocusable = true
            setOnClickListener { closeUtilityPage() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        val heading = TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(heading, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(header, LinearLayout.LayoutParams(-1, dp(52)))

        val body = FrameLayout(this)
        content.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(content, FrameLayout.LayoutParams(-1, -1))

        val root = findViewById<FrameLayout>(android.R.id.content)
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        page.bringToFront()
        page.alpha = 0f
        page.translationY = dp(16).toFloat()
        page.animate().alpha(1f).translationY(0f).setDuration(200).start()

        utilityPage = page
        utilityBody = body
        utilityScreen = screen
        return body
    }

    private fun closeUtilityPage() {
        val page = utilityPage ?: return
        page.animate().cancel()
        page.animate()
            .alpha(0f)
            .translationY(dp(16).toFloat())
            .setDuration(160)
            .withEndAction {
                (page.parent as? ViewGroup)?.removeView(page)
                if (utilityPage === page) {
                    utilityPage = null
                    utilityBody = null
                    utilityScreen = null
                    phoneTabContainer = null
                    dialerTabView = null
                    recentsTabView = null
                    dialInput = null
                }
                page.alpha = 1f
                page.translationY = 0f
                hideSystemBars()
            }
            .start()
    }

    private fun openPhonePage() {
        phoneTab = PhoneTab.DIALER
        val body = createUtilityPage("Phone", "phone")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val tabContainer = FrameLayout(this)
        content.addView(tabContainer, LinearLayout.LayoutParams(-1, 0, 1f))

        val tabBarFrame = FrameLayout(this)
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFF171719.toInt())
                setStroke(dp(1), 0xFF343438.toInt())
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val dialerTab = utilityTab("Dialer")
        val recentsTab = utilityTab("Recents")
        tabBar.addView(dialerTab, LinearLayout.LayoutParams(0, -1, 1f))
        tabBar.addView(recentsTab, LinearLayout.LayoutParams(0, -1, 1f))
        tabBarFrame.addView(
            tabBar,
            FrameLayout.LayoutParams(dp(300), dp(48), Gravity.CENTER)
        )
        tabBarFrame.addOnLayoutChangeListener { frame, _, _, _, _, _, _, _, _ ->
            val maxWidth = (frame.width - dp(32)).coerceAtLeast(dp(180))
            val params = tabBar.layoutParams as FrameLayout.LayoutParams
            val width = minOf(dp(300), maxWidth)
            if (params.width != width) {
                params.width = width
                tabBar.layoutParams = params
            }
        }
        content.addView(tabBarFrame, LinearLayout.LayoutParams(-1, dp(56)))

        body.addView(content, FrameLayout.LayoutParams(-1, -1))
        phoneTabContainer = tabContainer
        dialerTabView = dialerTab
        recentsTabView = recentsTab
        dialerTab.setOnClickListener { showPhoneTab(PhoneTab.DIALER) }
        recentsTab.setOnClickListener { showPhoneTab(PhoneTab.RECENTS) }
        showPhoneTab(phoneTab)
    }

    private fun utilityTab(label: String) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(0xFF929298.toInt())
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat() }
    }

    private fun showPhoneTab(tab: PhoneTab) {
        phoneTab = tab
        updatePhoneTabIndicator(dialerTabView, tab == PhoneTab.DIALER)
        updatePhoneTabIndicator(recentsTabView, tab == PhoneTab.RECENTS)
        val container = phoneTabContainer ?: return
        container.removeAllViews()
        if (tab == PhoneTab.DIALER) {
            showDialer(container)
            return
        }
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            val permissionView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            permissionView.addView(utilityText("Allow access to show recent calls.", 18f))
            val allow = utilityButton("Allow call history access", 0xFF0A84FF.toInt())
            allow.setOnClickListener {
                requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), REQ_CALL_LOG)
            }
            permissionView.addView(
                allow,
                LinearLayout.LayoutParams(-1, dp(56)).apply {
                    topMargin = dp(16)
                }
            )
            container.addView(permissionView, FrameLayout.LayoutParams(-1, -1))
            return
        }
        showCallLog(container)
    }

    private fun updatePhoneTabIndicator(tab: View?, selected: Boolean) {
        val button = tab as? TextView ?: return
        button.setTextColor(if (selected) Color.WHITE else 0xFF929298.toInt())
        button.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            if (selected) setColor(0xFF343438.toInt()) else setColor(Color.TRANSPARENT)
        }
    }

    private fun showDialer(container: FrameLayout) {
        val dialer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        container.addView(dialer, FrameLayout.LayoutParams(-1, -1))

        val number = EditText(this).apply {
            hint = "Phone number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            textSize = 22f
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            showSoftInputOnFocus = false
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF88888E.toInt())
            setPadding(0, dp(8), 0, dp(8))
        }
        dialInput = number

        val keypad = GridLayout(this).apply {
            columnCount = 3
            rowCount = 4
        }
        val keypadPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        dialer.addView(keypadPanel, LinearLayout.LayoutParams(0, -1, 1f))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
        keys.forEachIndexed { index, key ->
            val keyView = utilityButton(key, 0xFF28282C.toInt()).apply {
                textSize = 26f
                background = null
                setOnClickListener {
                    val cursor = number.selectionStart.coerceAtLeast(0)
                    number.text.insert(cursor, key)
                    number.setSelection((cursor + key.length).coerceAtMost(number.length()))
                }
            }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / 3, 1f),
                GridLayout.spec(index % 3, 1f)
            ).apply {
                width = 0
                height = 0
                setMargins(dp(5), dp(4), dp(5), dp(4))
            }
            keypad.addView(keyView, params)
            keyView.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val cellDp = minOf(right - left, bottom - top) / resources.displayMetrics.density
                keyView.textSize = (cellDp * 0.34f).coerceIn(18f, 28f)
            }
        }
        keypadPanel.addView(
            keypad,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        val numberPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(4), 0)
        }
        dialer.addView(numberPanel, LinearLayout.LayoutParams(dp(220), -1))
        dialer.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val availableWidth = view.width - view.paddingLeft - view.paddingRight
            if (availableWidth > 0) {
                val availableDp = availableWidth / resources.displayMetrics.density
                val panelMaxDp = (availableDp - 180f).coerceAtLeast(100f)
                val panelWidthDp = (availableDp * 0.32f)
                    .coerceAtMost(minOf(panelMaxDp, 320f))
                    .coerceAtLeast(minOf(140f, availableDp * 0.28f))
                val panelParams = numberPanel.layoutParams as LinearLayout.LayoutParams
                val panelWidth = dp(panelWidthDp)
                if (panelParams.width != panelWidth || panelParams.weight != 0f) {
                    panelParams.width = panelWidth
                    panelParams.weight = 0f
                    numberPanel.layoutParams = panelParams
                }
                number.textSize = (panelWidthDp * 0.075f).coerceIn(18f, 26f)
            }
        }
        numberPanel.addView(number, LinearLayout.LayoutParams(-1, dp(64)))

        val delete = utilityButton("Delete", 0xFF28282C.toInt())
        delete.textSize = 13f
        delete.background = null
        delete.setTextColor(0xFFB8B8BE.toInt())
        delete.setOnClickListener {
            val cursor = number.selectionStart
            if (cursor > 0) {
                number.text.delete(cursor - 1, cursor)
                number.setSelection(cursor - 1)
            }
        }
        val call = utilityButton("Call", 0xFF30D158.toInt())
        call.textSize = 16f
        call.setOnClickListener {
            val enteredNumber = number.text.toString().trim()
            if (enteredNumber.isBlank()) {
                Toast.makeText(this, "Enter a phone number first.", Toast.LENGTH_SHORT).show()
            } else {
                confirmCall(enteredNumber)
            }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(delete, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
            marginEnd = dp(6)
        })
        actions.addView(call, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
            marginStart = dp(6)
        })
        numberPanel.addView(actions, LinearLayout.LayoutParams(-1, dp(56)).apply {
            topMargin = dp(12)
        })
    }

    private fun showCallLog(container: FrameLayout) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }
        val scroll = ScrollView(this).apply {
            addView(list)
        }
        container.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
            CallLog.Calls.TYPE
        )
        val cursor = try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )
        } catch (e: SecurityException) {
            Toast.makeText(this, "Unable to read call history permission.", Toast.LENGTH_LONG).show()
            null
        }
        if (cursor == null) {
            list.addView(utilityText("Call history couldn't be loaded.", 18f))
            return
        }

        var count = 0
        cursor.use { calls ->
            val numberColumn = calls.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameColumn = calls.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val dateColumn = calls.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val typeColumn = calls.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            while (calls.moveToNext() && count < 50) {
                val phoneNumber = calls.getString(numberColumn).orEmpty()
                val contact = calls.getString(nameColumn)?.takeIf { it.isNotBlank() } ?: phoneNumber
                val timestamp = calls.getLong(dateColumn)
                val callType = when (calls.getInt(typeColumn)) {
                    CallLog.Calls.INCOMING_TYPE -> "Incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    CallLog.Calls.REJECTED_TYPE -> "Rejected"
                    else -> "Call"
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (phoneNumber.isNotBlank()) confirmCall(phoneNumber)
                    }
                }
                row.addView(utilityText(contact, 16f, bold = true))
                row.addView(
                    utilityText(
                        "$phoneNumber  ·  $callType  ·  ${
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(timestamp))
                        }",
                        12f,
                        0xFFB8B8BE.toInt()
                    )
                )
                list.addView(row, LinearLayout.LayoutParams(-1, -2))
                val divider = View(this).apply { setBackgroundColor(0xFF303034.toInt()) }
                list.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
                count++
            }
        }
        if (count == 0) list.addView(utilityText("No recent calls.", 18f))
    }

    private fun confirmCall(number: String) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Place call?")
            .setMessage(number)
            .setPositiveButton("Call") { _, _ -> placeCall(number) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun placeCall(number: String) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            Toast.makeText(this, "Calling isn't available on this device.", Toast.LENGTH_LONG).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCallNumber = number
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL_PHONE)
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
        } catch (e: SecurityException) {
            Toast.makeText(this, "Phone permission is required to place calls.", Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Calling isn't available on this device.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openMessagesPage() {
        val body = createUtilityPage("Messages", "messages")
        refreshMessagesPage(body)
    }

    private fun refreshMessagesPage(body: FrameLayout? = utilityBody) {
        if (utilityScreen != "messages") return
        val container = body ?: return
        container.removeAllViews()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }
        val scroll = ScrollView(this).apply {
            addView(content)
        }
        container.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val refresh = ImageView(this).apply {
            setImageResource(R.drawable.ic_refresh)
            contentDescription = "Refresh messages"
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF28282C.toInt())
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            isFocusable = true
        }
        refresh.setOnClickListener { refreshMessagesPage() }
        val messageHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        messageHeader.addView(
            utilityText("Read-only message previews", 14f, 0xFFB8B8BE.toInt()),
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )
        messageHeader.addView(refresh, LinearLayout.LayoutParams(dp(40), dp(40)))
        content.addView(messageHeader, LinearLayout.LayoutParams(-1, dp(48)).apply {
            bottomMargin = dp(8)
        })

        if (!NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
            val accessButton = utilityButton("Enable notification access", 0xFF0A84FF.toInt())
            accessButton.setOnClickListener {
                openIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            content.addView(accessButton, LinearLayout.LayoutParams(-1, dp(56)).apply {
                topMargin = dp(16)
            })
            return
        }

        val previews = MediaListener.activeListener?.getMessagePreviews().orEmpty()
        if (previews.isEmpty()) {
            content.addView(
                utilityText(
                    "No active message previews. New previews appear here while their notifications remain active.",
                    18f
                ),
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(24)
                }
            )
            return
        }

        previews.forEach { preview ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = utilityCardBackground()
            }
            card.addView(utilityText(preview.title, 18f, bold = true))
            card.addView(
                utilityText(
                    "${preview.source}  ·  ${
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(preview.timestamp))
                    }",
                    13f,
                    0xFFB8B8BE.toInt()
                )
            )
            card.addView(
                utilityText(preview.text, 16f),
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(8)
                }
            )
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
            })
        }
    }

    private fun utilityText(
        value: String,
        size: Float,
        color: Int = Color.WHITE,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun utilityButton(label: String, color: Int) = utilityText(label, 18f).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(color)
        }
        isClickable = true
        isFocusable = true
    }

    private fun utilityCardBackground() = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(0xFF1C1C1E.toInt())
    }

    private fun openMusicApp() {
        val categoryIntent = Intent.makeMainSelectorActivity(
            Intent.ACTION_MAIN,
            Intent.CATEGORY_APP_MUSIC
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(categoryIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // Some music players don't register Android's music-app category.
        }

        val musicKeywords = listOf(
            "music", "spotify", "apple", "youtube music", "vlc", "poweramp",
            "tidal", "deezer", "amazon", "foobar", "aimp", "musicolet", "jetaudio"
        )
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val label = resolveInfo.loadLabel(packageManager).toString()
                val searchableName = "${activityInfo.packageName} $label".lowercase(Locale.ROOT)
                if (musicKeywords.none(searchableName::contains)) return@mapNotNull null
                activityInfo to label
            }
            .distinctBy { it.first.packageName }
            .sortedBy { it.second.lowercase(Locale.ROOT) }

        when (candidates.size) {
            0 -> Toast.makeText(this, "No music player app was found.", Toast.LENGTH_LONG).show()
            1 -> launchMusicActivity(candidates.first().first)
            else -> AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Choose music app")
                .setItems(candidates.map { it.second }.toTypedArray()) { _, index ->
                    launchMusicActivity(candidates[index].first)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun launchMusicActivity(activity: android.content.pm.ActivityInfo) {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(activity.packageName, activity.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "The selected music app is unavailable.", Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "The selected music app can't be opened.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openIntent(i: Intent) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ now playing

    private fun mediaAccessGranted() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun askMediaAccess() {
        openIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun hookMedia() {
        if (!mediaAccessGranted()) {
            controller = null
            renderNowPlaying()
            return
        }
        try {
            sessionMgr.addOnActiveSessionsChangedListener(sessionsListener, listenerComp)
            pickController(sessionMgr.getActiveSessions(listenerComp))
        } catch (e: SecurityException) {
            controller = null
            renderNowPlaying()
        }
    }

    private fun unhookMedia() {
        try { sessionMgr.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) {}
        controller?.unregisterCallback(controllerCb)
        controller = null
    }

    private fun pickController(list: List<MediaController>?) {
        controller?.unregisterCallback(controllerCb)
        controller = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        controller?.registerCallback(controllerCb)
        renderNowPlaying()
    }

private fun renderNowPlaying() {
    val c = controller
    val md = c?.metadata

    val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        ?: getString(R.string.nothing_playing)
    val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        ?: if (mediaAccessGranted()) getString(R.string.start_playing)
           else getString(R.string.tap_allow_media)

    val bitmap = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        ?: md?.description?.iconBitmap
    val artworkUri = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
        ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
        ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        ?: md?.description?.iconUri?.toString()

    trackTitle.text = title
    trackArtist.text = artist

    showArt(bitmap)

    val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
    animatePlaybackChange(playBtn, playing)

    // Full-screen player
    musicTitle?.text = title
    musicArtist?.text = artist
    musicPlay?.let { animatePlaybackChange(it, playing) }
    lastPlayingState = playing

    if (bitmap != null) {
        requestedArtworkUri = null
        musicArt?.let { animateArtwork(it, bitmap) }
    } else {
        loadArtworkUri(artworkUri)
        if (artworkUri.isNullOrBlank()) {
            requestedArtworkUri = null
            musicArt?.let { animateArtwork(it, null) }
        }
    }

}

    private fun loadArtworkUri(uriValue: String?) {
        if (uriValue.isNullOrBlank() || requestedArtworkUri == uriValue) return
        requestedArtworkUri = uriValue
        val uri = Uri.parse(uriValue)
        if (uri.scheme !in setOf("content", "android.resource", "https")) {
            Log.w(TAG, "Unsupported album artwork URI scheme: ${uri.scheme}")
            return
        }
        musicArt?.let { animateArtwork(it, null) }

        artworkExecutor.execute {
            val bitmap = try {
                if (uri.scheme == "https") {
                    val connection = URL(uriValue).openConnection().apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                    }
                    connection.getInputStream().use { BitmapFactory.decodeStream(it) }
                } else {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Unable to load album artwork from media session URI", e)
                null
            } catch (e: SecurityException) {
                Log.w(TAG, "Unable to load album artwork from media session URI", e)
                null
            }
            if (bitmap == null) {
                Log.w(TAG, "Media session artwork URI returned no image")
                return@execute
            }
            musicHandler.post {
                if (requestedArtworkUri != uriValue) return@post
                showArt(bitmap)
                musicArt?.let { animateArtwork(it, bitmap) }
            }
        }
    }

    private fun showArt(bmp: android.graphics.Bitmap?) {
        art.clipToOutline = true
        animateArtwork(art, bmp)
    }

private fun buildMusicPage() {
    if (musicPage != null) return

    fun text(
        value: String,
        size: Float,
        color: Int = Color.WHITE
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    fun button(value: String, size: Float) = text(value, size).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setBackgroundResource(R.drawable.bg_circle)
    }

    fun iconButton(icon: Int, size: Int, circular: Boolean = false) =
        ImageView(this).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(size / 3), dp(size / 3), dp(size / 3), dp(size / 3))
            if (circular) setBackgroundResource(R.drawable.bg_circle)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.music)
        }

    val page = FrameLayout(this).apply {
        setBackgroundColor(Color.rgb(20, 20, 24))
        isFocusableInTouchMode = true
    }

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(10), dp(20), dp(12))
    }

    // Header
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val close = iconButton(R.drawable.ic_back, 40).apply {
        setPadding(dp(8), dp(8), dp(8), dp(8))
        contentDescription = getString(R.string.cancel)
    }
    header.addView(
        close,
        LinearLayout.LayoutParams(dp(40), dp(48)).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
    )

    val heading = text("NOW PLAYING", 12f, 0xFFDDDDDD.toInt()).apply {
        gravity = Gravity.CENTER_VERTICAL
        letterSpacing = 0.12f
    }
    header.addView(
        heading,
        LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(4)
        }
    )

    content.addView(
        header,
        LinearLayout.LayoutParams(-1, dp(48))
    )

    val playerControls = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    val title = text("Nothing playing", 27f).apply {
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    val artist = text("Start music in another app", 17f, 0xFFD0D0D0.toInt())

    playerControls.addView(title)
    playerControls.addView(
        artist,
        LinearLayout.LayoutParams(-1, dp(38)).apply {
            topMargin = dp(5)
        }
    )

    // Transport controls
    val controls = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    val shuffle = iconButton(R.drawable.ic_shuffle, 48)
    val previous = iconButton(R.drawable.ic_skip_previous, 52)
    val play = iconButton(
        if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) {
            R.drawable.ic_pause
        } else {
            R.drawable.ic_play
        },
        68,
        circular = true
    )
    val next = iconButton(R.drawable.ic_skip_next, 52)
    val repeat = iconButton(R.drawable.ic_repeat, 48)

    controls.addView(shuffle, LinearLayout.LayoutParams(dp(48), dp(52)))
    controls.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
    controls.addView(previous, LinearLayout.LayoutParams(dp(52), dp(52)))
    controls.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
    controls.addView(play, LinearLayout.LayoutParams(dp(68), dp(68)))
    controls.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
    controls.addView(next, LinearLayout.LayoutParams(dp(52), dp(52)))
    controls.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
    controls.addView(repeat, LinearLayout.LayoutParams(dp(48), dp(52)))

    playerControls.addView(
        controls,
        LinearLayout.LayoutParams(-2, dp(76)).apply {
            topMargin = dp(10)
            gravity = Gravity.CENTER_HORIZONTAL
        }
    )

    val artwork = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.ic_music)
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(0xFF303038.toInt())
        }
        clipToOutline = true
        contentDescription = "Album artwork"
    }
    musicArt = artwork

    val artworkFrame = FrameLayout(this).apply {
        addView(
            artwork,
            FrameLayout.LayoutParams(1, 1, Gravity.CENTER)
        )
    }

    artworkFrame.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        val side = minOf(view.width, view.height, dp(360))
        val params = artwork.layoutParams
        if (side > 0 && (params.width != side || params.height != side)) {
            params.width = side
            params.height = side
            artwork.layoutParams = params
        }
    }

    val isPortrait = resources.configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_PORTRAIT
    val body = LinearLayout(this).apply {
        orientation = if (isPortrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        translationY = -dp(24).toFloat()
    }
    val artworkParams = if (isPortrait) {
        LinearLayout.LayoutParams(-1, 0, 1f)
    } else {
        LinearLayout.LayoutParams(0, -1, 1f)
    }
    val controlsParams = if (isPortrait) {
        LinearLayout.LayoutParams(-1, -2)
    } else {
        LinearLayout.LayoutParams(0, -1, 1f)
    }
    if (isPortrait) {
        body.addView(artworkFrame, artworkParams)
        body.addView(playerControls, controlsParams)
    } else {
        body.addView(playerControls, controlsParams)
        body.addView(artworkFrame, artworkParams)
    }
    content.addView(
        body,
        LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(8)
        }
    )

    page.addView(
        content,
        FrameLayout.LayoutParams(-1, -1)
    )

    // Store views for updates from the media session.
    musicTitle = title
    musicArtist = artist
    musicPlay = play

    close.setOnClickListener { closeMusicPage() }
    play.setOnClickListener { togglePlayback() }
    previous.setOnClickListener {
        controller?.transportControls?.skipToPrevious()
    }
    next.setOnClickListener {
        controller?.transportControls?.skipToNext()
    }

    shuffle.setOnClickListener {
    val c = controller

    val action = c?.playbackState?.customActions?.firstOrNull {
        it.name.contains("shuffle", ignoreCase = true) ||
            it.action.contains("shuffle", ignoreCase = true)
    }

    if (c != null && action != null) {
        c.transportControls.sendCustomAction(action.action, null)
    } else {
        Toast.makeText(
            this,
            "Shuffle isn't supported by the current music app.",
            Toast.LENGTH_SHORT
        ).show()
    }
}

    repeat.setOnClickListener {
    val c = controller

    val action = c?.playbackState?.customActions?.firstOrNull {
        it.name.contains("repeat", ignoreCase = true) ||
            it.action.contains("repeat", ignoreCase = true)
    }

    if (c != null && action != null) {
        c.transportControls.sendCustomAction(action.action, null)
    } else {
        Toast.makeText(
            this,
            "Repeat isn't supported by the current music app.",
            Toast.LENGTH_SHORT
        ).show()
    }
}

    musicPage = page

    val root = findViewById<FrameLayout>(android.R.id.content)
    root.addView(page, FrameLayout.LayoutParams(-1, -1))
    page.bringToFront()
    page.alpha = 0f
    page.translationY = dp(20).toFloat()
    page.animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(240)
        .start()

    renderNowPlaying()
}

private fun openMusicPage() {
    if (!mediaAccessGranted()) {
        askMediaAccess()
        return
    }
    buildMusicPage()
}

private fun closeMusicPage() {
    val page = musicPage ?: return
    page.animate().cancel()
    page.animate()
        .alpha(0f)
        .translationY(dp(20).toFloat())
        .setDuration(180)
        .withEndAction {
            (page.parent as? ViewGroup)?.removeView(page)
            if (musicPage === page) {
                musicPage = null
                musicArt = null
                musicTitle = null
                musicArtist = null
                musicPlay = null
            }
            page.alpha = 1f
            page.translationY = 0f
            hideSystemBars()
        }
        .start()
}

private fun togglePlayback() {
    val c = controller
    if (c == null) {
        askMediaAccess()
        return
    }

    if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
        c.transportControls.pause()
    } else {
        c.transportControls.play()
    }
}

    companion object {
        private const val TAG = "PetalDrive"
        private const val NOMINATIM_MIN_INTERVAL_MS = 1_000L
        private const val OFF_ROUTE_THRESHOLD_METERS = 65.0
        private const val MAX_REROUTE_ACCURACY_METERS = 40f
        private const val OFF_ROUTE_FIXES_REQUIRED = 2
        private const val REROUTE_COOLDOWN_MS = 15_000L
        private const val TRIP_ENDED_STATUS_DURATION_MS = 5_000L
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val REQ_LOCATION = 1001
        private const val REQ_CALL_LOG = 1002
        private const val REQ_CALL_PHONE = 1003
        private const val REQ_PHONE_STATE = 1004
    }
}
