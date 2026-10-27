package com.livetranslate.headphones.watch

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.CancellationSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Fills the ST9 weather screen the way DaFit does: current conditions, sunrise and sunset,
 * the place name, and the coming days. The watch asks with command 100; the Watch screen can ask too.
 */
class WatchWeatherPusher(
    private val context: Context,
    private val watch: MoyoungWatchClient,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastSentMs = 0L

    private val _status = MutableStateFlow("Weather not sent")
    val status: StateFlow<String> = _status.asStateFlow()

    fun onWatchAsked() = push(force = false)

    fun onConnected() = push(force = false)

    fun pushFromButton() = push(force = true)

    private fun push(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSentMs < QUIET_MS) return
        scope.launch { send(now) }
    }

    private suspend fun send(startedMs: Long) {
        if (!locationGranted()) {
            _status.value = "Location permission is off"
            return
        }
        val fix = oneFix()
        if (fix == null) {
            _status.value = "No GPS fix for weather"
            return
        }
        _status.value = "Fetching weather"
        val place = reversePlace(fix.first, fix.second) ?: "Here"
        val body = get(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${fix.first}&longitude=${fix.second}" +
                "&current=temperature_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
                "&timezone=auto&forecast_days=8",
        )
        val report = body?.let { WatchWeather.parseOpenMeteo(it, place) }
        if (report == null) {
            _status.value = "Weather service did not answer"
            return
        }
        if (!watch.isReady()) {
            _status.value = "Watch is not connected"
            return
        }
        val clock = Calendar.getInstance()
        watch.sendCommand(WatchWeather.CMD_TODAY, WatchWeather.today(report))
        watch.sendCommand(WatchWeather.CMD_SUNRISE, WatchWeather.sunrise(report))
        watch.sendCommand(
            WatchWeather.CMD_LOCATION,
            WatchWeather.location(clock.get(Calendar.HOUR_OF_DAY), clock.get(Calendar.MINUTE), place),
        )
        watch.sendCommand(WatchWeather.CMD_FUTURE, WatchWeather.future(report))
        lastSentMs = startedMs
        _status.value = "${report.currentC}°C $place"
    }

    private fun reversePlace(lat: Double, lon: Double): String? {
        val body = get(
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lon",
        ) ?: return null
        return WatchWeather.parsePlace(body)
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull()
    }

    private fun locationGranted(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun oneFix(): Pair<Double, Double>? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { manager.isProviderEnabled(it) } ?: return null
        val last = manager.getLastKnownLocation(provider)
        if (last != null && System.currentTimeMillis() - last.time < 15 * 60_000L) {
            return last.latitude to last.longitude
        }
        val location = suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { found ->
                if (cont.isActive) cont.resume(found)
            }
        }
        return location?.let { it.latitude to it.longitude }
    }

    companion object {
        private const val QUIET_MS = 15 * 60_000L
        private const val USER_AGENT = "Lingo/1.0 (Android; watch weather)"
    }
}
