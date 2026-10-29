package com.example.petaldrive

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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
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
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity(), OnMapReadyCallback {

    private data class Tile(val label: Int, val icon: Int, val color: Int, val action: () -> Unit)

    // --- views
    private lateinit var mapView: MapView
    private lateinit var dashboard: View
    private lateinit var gridPage: View
    private lateinit var zoomCol: View
    private lateinit var destBar: View
    private lateinit var destSub: TextView
    private lateinit var speedText: TextView
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
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pickController(it) }
    private val controllerCb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = renderNowPlaying()
        override fun onPlaybackStateChanged(state: PlaybackState?) = renderNowPlaying()
    }

    private fun <T : View> v(id: Int): T = findViewById(id)
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        hideSystemBars()

        dashboard = v(R.id.dashboard)
        gridPage = v(R.id.gridPage)
        zoomCol = v(R.id.zoomCol)
        destBar = v(R.id.destBar)
        destSub = v(R.id.destSub)
        speedText = v(R.id.speed)
        trackTitle = v(R.id.trackTitle)
        trackArtist = v(R.id.trackArtist)
        art = v(R.id.art)
        playBtn = v(R.id.btnPlay)

        mapView = v(R.id.mapView)
        mapView.onCreate(savedInstanceState?.getBundle(MAP_KEY))
        mapView.getMapAsync(this)

        // left rail
        v<View>(R.id.railMaps).setOnClickListener { showDashboard() }
        v<View>(R.id.railMusic).setOnClickListener { openCategory(Intent.CATEGORY_APP_MUSIC) }
        v<View>(R.id.railPhone).setOnClickListener { openIntent(Intent(Intent.ACTION_DIAL)) }
        v<View>(R.id.railHome).setOnClickListener { showGrid() }

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
        v<View>(R.id.searchCard).setOnClickListener { showSearch() }
        v<View>(R.id.mediaInfo).setOnClickListener { if (controller == null) askMediaAccess() }
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
    }

    override fun onPause() {
        stopLocationUpdates()
        unhookMedia()
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
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

    @Deprecated("Back returns to the dashboard instead of leaving the launcher")
    override fun onBackPressed() {
        if (gridPage.visibility == View.VISIBLE) showDashboard()
    }

    /** Touching the map by hand turns off follow-mode (but tapping the overlay buttons doesn't). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && dashboard.visibility == View.VISIBLE) {
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
        if (requestCode == REQ_LOCATION && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            onLocationGranted()
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
        if (loc.hasSpeed()) speedText.text = "${(loc.speed * 3.6f).roundToInt()} km/h"
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

    private fun showDashboard() { gridPage.visibility = View.GONE; dashboard.visibility = View.VISIBLE }
    private fun showGrid() { dashboard.visibility = View.GONE; gridPage.visibility = View.VISIBLE }

    private fun buildGrid() {
        val grid = v<GridLayout>(R.id.appGrid)
        val tiles = listOf(
            Tile(R.string.maps, R.drawable.ic_navigation, 0xFF0A84FF.toInt()) { showDashboard() },
            Tile(R.string.music, R.drawable.ic_music, 0xFFFF375F.toInt()) { openCategory(Intent.CATEGORY_APP_MUSIC) },
            Tile(R.string.phone, R.drawable.ic_phone, 0xFF30D158.toInt()) { openIntent(Intent(Intent.ACTION_DIAL)) },
            Tile(R.string.messages, R.drawable.ic_message, 0xFF30D158.toInt()) { openCategory(Intent.CATEGORY_APP_MESSAGING) }
        )
        tiles.forEach { grid.addView(makeTile(it)) }
    }

    private fun makeTile(t: Tile): View {
        val icon = ImageView(this).apply {
            setImageResource(t.icon)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(t.color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        }
        val label = TextView(this).apply {
            setText(t.label)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(icon)
            addView(label)
            setOnClickListener { t.action() }
        }
    }

    private fun openCategory(category: String) {
        openIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category))
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
        if (c == null) {
            trackTitle.setText(R.string.nothing_playing)
            trackArtist.setText(if (mediaAccessGranted()) R.string.start_playing else R.string.tap_allow_media)
            showArt(null)
            playBtn.setImageResource(R.drawable.ic_play)
            return
        }
        val md = c.metadata
        trackTitle.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: getString(R.string.unknown_track)
        trackArtist.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        showArt(md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART))
        playBtn.setImageResource(
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun showArt(bmp: android.graphics.Bitmap?) {
        if (bmp != null) {
            art.setPadding(0, 0, 0, 0)
            art.setImageBitmap(bmp)
        } else {
            art.setPadding(dp(14), dp(14), dp(14), dp(14))
            art.setImageResource(R.drawable.ic_music)
        }
    }

    companion object {
        private const val MAP_KEY = "MapViewBundleKey"
        private const val REQ_LOCATION = 1001
    }
}
