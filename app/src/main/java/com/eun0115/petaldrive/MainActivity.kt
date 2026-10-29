package com.eun0115.petaldrive

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import android.os.Bundle
import android.provider.Settings
import android.provider.CallLog
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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
import com.huawei.hms.maps.CameraUpdateFactory
import com.huawei.hms.maps.HuaweiMap
import com.huawei.hms.maps.MapView
import com.huawei.hms.maps.OnMapReadyCallback
import com.huawei.hms.maps.model.LatLng
import com.huawei.hms.maps.model.MapStyleOptions
import com.huawei.hms.maps.model.Marker
import com.huawei.hms.maps.model.MarkerOptions
import java.io.IOException
import java.net.URL
import java.text.DateFormat
import java.util.Locale
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity(), OnMapReadyCallback {

    private data class Tile(val label: Int, val icon: Int, val color: Int, val action: () -> Unit)
    private enum class PhoneTab { DIALER, RECENTS }

    // --- views
    private lateinit var mapView: MapView
    private lateinit var dashboard: View
    private lateinit var dashboardSidePanel: LinearLayout
    private lateinit var gridPage: View
    private lateinit var zoomCol: View
    private lateinit var destBar: View
    private lateinit var destSub: TextView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var art: ImageView
    private lateinit var playBtn: ImageView

    // --- map / location state
    private var map: HuaweiMap? = null
    private var lastFix: Location? = null
    private var destination: LatLng? = null
    private var destMarker: Marker? = null
    private var locationGranted = false
    private var following = true
    private var firstFix = true

    

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
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private var requestedArtworkUri: String? = null

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

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        hideSystemBars()

        dashboard = v(R.id.dashboard)
        dashboardSidePanel = v(R.id.dashboardSidePanel)
        dashboard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDashboardSidePanel()
        }
        gridPage = v(R.id.gridPage)
        zoomCol = v(R.id.zoomCol)
        destBar = v(R.id.destBar)
        destSub = v(R.id.destSub)
        trackTitle = v(R.id.trackTitle)
        trackArtist = v(R.id.trackArtist)
        art = v(R.id.art)
        art.clipToOutline = true
        playBtn = v(R.id.btnPlay)

        mapView = v(R.id.mapView)
        mapView.onCreate(savedInstanceState?.getBundle(MAP_KEY))
        mapView.getMapAsync(this)

        // left rail
        v<View>(R.id.railMaps).setOnClickListener { showDashboard() }
        v<View>(R.id.railMusic).setOnClickListener { openMusicPage() }
        v<View>(R.id.mediaCard).setOnClickListener { openMusicPage() }
        v<View>(R.id.railPhone).setOnClickListener { openPhonePage() }
        v<View>(R.id.railHome).setOnClickListener {
            if (gridPage.visibility == View.VISIBLE) showDashboard() else showGrid()
        }

        // map controls
        v<View>(R.id.zoomIn).setOnClickListener { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
        v<View>(R.id.zoomOut).setOnClickListener { map?.animateCamera(CameraUpdateFactory.zoomOut()) }
        v<View>(R.id.recenter).setOnClickListener {
            following = true
            lastFix?.let { center(it, 16f) }
        }
        v<View>(R.id.destGo).setOnClickListener { destination?.let { startNavigation(it) } }
        v<View>(R.id.destClear).setOnClickListener { clearDestination() }

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
    }

    override fun onStart() { super.onStart(); mapView.onStart() }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
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
        unhookMedia()
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onDestroy() {
        artworkExecutor.shutdownNow()
        mapView.onDestroy()
        super.onDestroy()
    }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val b = outState.getBundle(MAP_KEY) ?: Bundle().also { outState.putBundle(MAP_KEY, it) }
        mapView.onSaveInstanceState(b)
    }

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
            ev.actionMasked == MotionEvent.ACTION_DOWN && dashboard.visibility == View.VISIBLE
        ) {
            val x = ev.rawX.toInt()
            val y = ev.rawY.toInt()
            if (hit(mapView, x, y) && !hit(zoomCol, x, y) && !hit(destBar, x, y)) following = false
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

    override fun onMapReady(hMap: HuaweiMap) {
        map = hMap
        try {
            if (BuildConfig.PETAL_STYLE_ID.isNotBlank()) hMap.setStyleId(BuildConfig.PETAL_STYLE_ID)
            else hMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.mapstyle_night))
        } catch (e: Exception) {
            // Styling is cosmetic; fall back to the default map.
        }
        hMap.uiSettings.apply {
            isZoomControlsEnabled = false
            isMyLocationButtonEnabled = false
            isCompassEnabled = false
        }
        hMap.setOnMapLongClickListener { setDestination(it) }
        if (locationGranted) hMap.isMyLocationEnabled = true
        lastFix?.let { center(it, 16f, animate = false) }
    }

    private fun center(loc: Location, zoom: Float? = null, animate: Boolean = true) {
        val m = map ?: return
        val ll = LatLng(loc.latitude, loc.longitude)
        val update = if (zoom != null) CameraUpdateFactory.newLatLngZoom(ll, zoom) else CameraUpdateFactory.newLatLng(ll)
        if (animate) m.animateCamera(update) else m.moveCamera(update)
    }

    private fun setDestination(p: LatLng) {
        destMarker?.remove()
        destination = p
        destMarker = map?.addMarker(MarkerOptions().position(p).title(getString(R.string.dropped_pin)))
        following = false
        updateDestBar()
    }

    private fun clearDestination() {
        destMarker?.remove()
        destMarker = null
        destination = null
        updateDestBar()
    }

    private fun updateDestBar() {
        val d = destination
        if (d == null) {
            destBar.visibility = View.GONE
            return
        }
        destBar.visibility = View.VISIBLE
        val fix = lastFix
        destSub.text = if (fix == null) {
            getString(R.string.waiting_gps)
        } else {
            val out = FloatArray(1)
            Location.distanceBetween(fix.latitude, fix.longitude, d.latitude, d.longitude, out)
            "${fmtDist(out[0])} · ${getString(R.string.straight_line)}"
        }
    }

    private fun fmtDist(m: Float) =
        if (m < 1000f) "${m.roundToInt()} m" else String.format(Locale.getDefault(), "%.1f km", m / 1000f)

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
        }
    }

    private fun onLocationGranted() {
        locationGranted = true
        map?.isMyLocationEnabled = true
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
        } else if (following) {
            center(loc)
        }
        updateDestBar()
    }

    // ------------------------------------------------------------------ Petal Maps hand-off

    /** Opens turn-by-turn in the Petal Maps app (falls back to petalmaps.com if the app isn't there). */
    private fun startNavigation(dest: LatLng) {
        val from = lastFix?.let { "saddr=${it.latitude},${it.longitude}&" } ?: ""
        val to = "daddr=${dest.latitude},${dest.longitude}"
        val app = Uri.parse("mapapp://navigation?$from$to&language=${Locale.getDefault().language}&type=drive")
        val web = Uri.parse("https://www.petalmaps.com/navigation/?$from$to&type=drive&utm_source=$packageName")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, app))
        } catch (e: ActivityNotFoundException) {
            openIntent(Intent(Intent.ACTION_VIEW, web))
        }
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
            Tile(R.string.messages, R.drawable.ic_message, 0xFF30D158.toInt()) { openMessagesPage() }
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
        private const val MAP_KEY = "MapViewBundleKey"
        private const val REQ_LOCATION = 1001
        private const val REQ_CALL_LOG = 1002
        private const val REQ_CALL_PHONE = 1003
    }
}
