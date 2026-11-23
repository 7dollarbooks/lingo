package com.livetranslate.headphones.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.livetranslate.headphones.AppSettings
import com.livetranslate.headphones.TranslationSession
import com.livetranslate.headphones.service.TranslationForegroundService
import com.livetranslate.headphones.text.TextSink
import com.livetranslate.headphones.text.TextSource
import com.livetranslate.headphones.watch.MoyoungWatchClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.resume

class NavigationSession(
    private val context: Context,
    private val settings: AppSettings,
    private val textSink: TextSink,
    private val watch: MoyoungWatchClient,
    private val speech: TranslationSession,
    private val directory: OsmDirectory = OsmDirectory(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var routeJob: Job? = null
    private var route: OsrmRoute? = null
    private var destination: LatLng? = null
    private var mode: TravelMode = TravelMode.WALK
    private var maneuverIndex = 0
    private var spokenIndex = -1
    private var lastRerouteMs = 0L
    private var speechHeld = false
    private var watchHeld = false
    private var foregroundHeld = false
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onFix(LatLng(location.latitude, location.longitude))
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _mode = MutableStateFlow(TravelMode.WALK)
    val travelMode: StateFlow<TravelMode> = _mode.asStateFlow()

    fun setTravelMode(next: TravelMode) {
        _mode.value = next
        mode = next
    }

    fun start(destinationText: String) {
        val query = destinationText.trim()
        if (query.isEmpty()) {
            _status.value = "Enter a destination"
            return
        }
        if (!locationGranted()) {
            _status.value = "Location permission is off"
            return
        }
        stop()
        routeJob = scope.launch {
            _active.value = true
            _status.value = "Finding $query"
            holdWatch()
            holdForeground("Finding $query")
            if (!speech.acquireSpeech()) {
                stop("Phone speaker or mic is not available")
                return@launch
            }
            speechHeld = true
            val here = currentFix()
            ensureActive()
            if (here == null) {
                stop("No GPS fix yet")
                return@launch
            }
            val dest = directory.geocode(query)
            ensureActive()
            if (dest == null) {
                stop("No OpenStreetMap match for that place")
                return@launch
            }
            destination = dest
            mode = _mode.value
            if (!loadRoute(here, dest)) {
                ensureActive()
                stop("No route from OpenStreetMap")
                return@launch
            }
            ensureActive()
            startListening()
            publishCurrent(forceSpeak = true)
        }
    }

    fun stop(message: String = "Navigation stopped") {
        routeJob?.cancel()
        route = null
        destination = null
        maneuverIndex = 0
        spokenIndex = -1
        stopListening()
        releaseWatch()
        if (speechHeld) {
            speech.releaseSpeech()
            speechHeld = false
        }
        _active.value = false
        releaseForeground()
        _status.value = message
    }

    private fun holdWatch() {
        if (watchHeld) return
        watch.hold()
        watchHeld = true
    }

    private fun releaseWatch() {
        if (!watchHeld) return
        watch.release()
        watchHeld = false
    }

    private suspend fun loadRoute(from: LatLng, to: LatLng): Boolean {
        val next = directory.route(settings.valhallaBaseUrl(), from, to, mode)
        if (next == null) {
            _status.value = "No route from OpenStreetMap"
            return false
        }
        route = next
        maneuverIndex = 0
        spokenIndex = -1
        lastRerouteMs = System.currentTimeMillis()
        return true
    }

    private fun onFix(here: LatLng) {
        val current = route ?: return
        val dest = destination ?: return
        val off = ValhallaRoute.distanceToShapeMeters(here, current.shape)
        val now = System.currentTimeMillis()
        if (current.shape.isNotEmpty() && off > mode.offRouteMeters && now - lastRerouteMs > 15_000) {
            lastRerouteMs = now
            scope.launch {
                _status.value = "Recalculating"
                val loaded = loadRoute(here, dest)
                ensureActive()
                if (loaded && _active.value) publishCurrent(forceSpeak = true)
            }
            return
        }
        advanceIfReached(here)
    }

    private fun advanceIfReached(here: LatLng) {
        val current = route ?: return
        val nextIndex = maneuverIndex + 1
        val next = current.maneuvers.getOrNull(nextIndex) ?: return
        val point = current.shape.getOrNull(next.beginShapeIndex) ?: return
        if (ValhallaRoute.haversineMeters(here, point) <= mode.announceMeters) {
            maneuverIndex = nextIndex
            publishCurrent(forceSpeak = false)
        }
    }

    private fun publishCurrent(forceSpeak: Boolean) {
        val instruction = route?.let { ValhallaRoute.nextInstruction(it, maneuverIndex) } ?: return
        _status.value = instruction
        textSink.publish(TextSource.NAVIGATION, "Navigation", instruction, announce = false)
        holdForeground(instruction)
        if (forceSpeak || spokenIndex != maneuverIndex) {
            spokenIndex = maneuverIndex
            scope.launch { speech.speakLine(instruction) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentFix(): LatLng? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = provider(manager) ?: return null
        val last = manager.getLastKnownLocation(provider)
        if (last != null && System.currentTimeMillis() - last.time < 60_000) {
            return LatLng(last.latitude, last.longitude)
        }
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val signal = android.os.CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                if (cont.isActive) {
                    cont.resume(location?.let { LatLng(it.latitude, it.longitude) })
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startListening() {
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val provider = provider(manager) ?: return
        manager.requestLocationUpdates(provider, 2_000L, 5f, listener, Looper.getMainLooper())
    }

    private fun stopListening() {
        context.getSystemService(LocationManager::class.java)?.removeUpdates(listener)
    }

    private fun provider(manager: LocationManager): String? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { manager.isProviderEnabled(it) }

    private fun locationGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun holdForeground(note: String) {
        foregroundHeld = true
        val intent = Intent(context, TranslationForegroundService::class.java).apply {
            action = TranslationForegroundService.ACTION_HOLD_LOCATION
            putExtra(TranslationForegroundService.EXTRA_NOTE, note)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun releaseForeground() {
        if (!foregroundHeld) return
        foregroundHeld = false
        val intent = Intent(context, TranslationForegroundService::class.java).apply {
            action = TranslationForegroundService.ACTION_RELEASE_LOCATION
        }
        context.startService(intent)
    }
}
